// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/exec/parquet_reader.h

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include <arrow/api.h>
#include <arrow/buffer.h>
#include <arrow/io/api.h>
#include <arrow/io/file.h>
#include <arrow/io/interfaces.h>
#include <parquet/api/reader.h>
#include <parquet/api/writer.h>
#include <parquet/arrow/reader.h>
#include <parquet/arrow/writer.h>
#include <parquet/exception.h>
#include <stdint.h>

#include <map>
#include <string>

#include "common/status.h"
#include "gen_cpp/FileBrokerService_types.h"
#include "gen_cpp/PlanNodes_types.h"
#include "gen_cpp/Types_types.h"

namespace starrocks {

class ExecEnv;
class TBrokerRangeDesc;
class TNetworkAddress;
class RuntimeState;
class Tuple;
class SlotDescriptor;
class MemPool;
class FileReader;

class ParquetFile : public arrow::io::RandomAccessFile {
public:
    ParquetFile(FileReader* file);
    virtual ~ParquetFile();
    arrow::Result<int64_t> Read(int64_t nbytes, void* buffer) override;
    arrow::Result<int64_t> ReadAt(int64_t position, int64_t nbytes, void* out) override;
    arrow::Result<int64_t> GetSize() override;
    arrow::Status Seek(int64_t position) override;
    arrow::Result<std::shared_ptr<arrow::Buffer>> Read(int64_t nbytes) override;
    arrow::Result<int64_t> Tell() const override;
    arrow::Status Close() override;
    bool closed() const override;

private:
    FileReader* _file;
    int64_t _pos = 0;
};

// Reader of broker parquet file
class ParquetReaderWrap {
public:
    ParquetReaderWrap(FileReader* file_reader, int32_t num_of_columns_from_file);
    ParquetReaderWrap(std::shared_ptr<arrow::io::RandomAccessFile>&& parquet_file, int32_t num_of_columns_from_file);
    virtual ~ParquetReaderWrap();

    // Read
    Status read(Tuple* tuple, const std::vector<SlotDescriptor*>& tuple_slot_descs, MemPool* mem_pool, bool* eof);
    void close();
    Status size(int64_t* size);
    Status init_parquet_reader(const std::vector<SlotDescriptor*>& tuple_slot_descs, const std::string& timezone);
    Status read_record_batch(const std::vector<SlotDescriptor*>& tuple_slot_descs, bool* eof);
    const std::shared_ptr<arrow::RecordBatch>& get_batch();
    const std::vector<std::shared_ptr<arrow::DataType>>& get_column_types();

private:
    void fill_slot(Tuple* tuple, SlotDescriptor* slot_desc, MemPool* mem_pool, const uint8_t* value, int32_t len);
    Status column_indices(const std::vector<SlotDescriptor*>& tuple_slot_descs);
    Status set_field_null(Tuple* tuple, const SlotDescriptor* slot_desc);
    Status handle_timestamp(const std::shared_ptr<arrow::TimestampArray>& ts_array, uint8_t* buf, int32_t* wbtyes);

private:
    const int32_t _num_of_columns_from_file;
    parquet::ReaderProperties _properties;
    std::shared_ptr<arrow::io::RandomAccessFile> _parquet;

    // parquet file reader object
    std::shared_ptr<::arrow::RecordBatchReader> _rb_batch;
    std::shared_ptr<arrow::RecordBatch> _batch;
    std::unique_ptr<parquet::arrow::FileReader> _reader;
    std::shared_ptr<parquet::FileMetaData> _file_metadata;
    std::map<std::string, int> _map_column; // column-name <---> column-index
    std::vector<int> _parquet_column_ids;
    std::vector<std::shared_ptr<arrow::DataType>> _parquet_column_type;
    int _total_groups; // groups in a parquet file
    int _current_group;

    int _rows_of_group; // rows in a group.
    int _current_line_of_group;
    int _current_line_of_batch;

    std::string _timezone;
};

} // namespace starrocks
