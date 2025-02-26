// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/olap/schema.h

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

#include <vector>

#include "runtime/descriptors.h"
#include "storage/aggregate_func.h"
#include "storage/field.h"
#include "storage/row_cursor_cell.h"
#include "storage/tablet_schema.h"
#include "storage/types.h"

namespace starrocks {

// The class is used to represent row's format in memory.  Each row contains
// multiple columns, some of which are key-columns (the rest are value-columns).
// NOTE: If both key-columns and value-columns exist, then the key-columns
// must be placed before value-columns.
//
// To compare two rows whose schemas are different, but they are from the same origin
// we store all column schema maybe accessed here. And default access through column id
class Schema {
public:
    Schema(const TabletSchema& tablet_schema) {
        size_t num_columns = tablet_schema.num_columns();
        std::vector<ColumnId> col_ids(num_columns);
        std::vector<TabletColumn> columns;
        columns.reserve(num_columns);

        size_t num_key_columns = 0;
        for (uint32_t cid = 0; cid < num_columns; ++cid) {
            col_ids[cid] = cid;
            const TabletColumn& column = tablet_schema.column(cid);
            if (column.is_key()) {
                ++num_key_columns;
            }
            columns.push_back(column);
        }

        _init(columns, col_ids, num_key_columns);
    }

    // All the columns of one table may exist in the columns param, but col_ids is only a subset.
    Schema(const std::vector<TabletColumn>& columns, const std::vector<ColumnId>& col_ids) {
        size_t num_key_columns = 0;
        for (const auto& c : columns) {
            if (c.is_key()) {
                ++num_key_columns;
            }
        }

        _init(columns, col_ids, num_key_columns);
    }

    // Only for UT
    Schema(const std::vector<TabletColumn>& columns, size_t num_key_columns) {
        std::vector<ColumnId> col_ids(columns.size());
        for (uint32_t cid = 0; cid < columns.size(); ++cid) {
            col_ids[cid] = cid;
        }

        _init(columns, col_ids, num_key_columns);
    }

    Schema(const std::vector<const Field*>& cols, size_t num_key_columns) {
        std::vector<ColumnId> col_ids(cols.size());
        for (uint32_t cid = 0; cid < cols.size(); ++cid) {
            col_ids[cid] = cid;
        }

        _init(cols, col_ids, num_key_columns);
    }

    Schema(const Schema&);
    Schema& operator=(const Schema& other);

    ~Schema();

    const std::vector<Field*>& columns() const { return _cols; }
    const Field* column(ColumnId cid) const { return _cols[cid]; }

    size_t num_key_columns() const { return _num_key_columns; }
    size_t schema_size() const { return _schema_size; }

    size_t column_offset(ColumnId cid) const { return _col_offsets[cid]; }

    // TODO(lingbin): What is the difference between colun_size() and index_size()
    size_t column_size(ColumnId cid) const { return _cols[cid]->size(); }

    size_t index_size(ColumnId cid) const { return _cols[cid]->index_size(); }

    bool is_null(const char* row, int index) const { return *reinterpret_cast<const bool*>(row + _col_offsets[index]); }
    void set_is_null(void* row, uint32_t cid, bool is_null) const {
        *reinterpret_cast<bool*>((char*)row + _col_offsets[cid]) = is_null;
    }

    size_t num_columns() const { return _cols.size(); }
    size_t num_column_ids() const { return _col_ids.size(); }
    const std::vector<ColumnId>& column_ids() const { return _col_ids; }

    // Generate a new schema based this schema, replace the field according to the input new_types
    Status convert_to(const std::vector<FieldType>& new_types, bool* converted,
                      std::unique_ptr<Schema>* new_schema) const;

    std::string debug_string() const;

private:
    Schema() {}

    void _init(const std::vector<TabletColumn>& cols, const std::vector<ColumnId>& col_ids, size_t num_key_columns);
    void _init(const std::vector<const Field*>& cols, const std::vector<ColumnId>& col_ids, size_t num_key_columns);

    void _copy_from(const Schema& other);

    // NOTE: The ColumnId here represents the sequential index number (starting from 0) of
    // a column in current row, not the unique id-identifier of each column
    std::vector<ColumnId> _col_ids;
    // NOTE: Both _cols[cid] and _col_offsets[cid] can only be accessed when the cid is
    // contained in _col_ids
    std::vector<Field*> _cols;
    // The value of each item indicates the starting offset of the corresponding column in
    // current row. e.g. _col_offsets[idx] is the offset of _cols[idx] (idx must in _col_ids)
    std::vector<size_t> _col_offsets;

    size_t _num_key_columns;
    size_t _schema_size;
};

} // namespace starrocks
