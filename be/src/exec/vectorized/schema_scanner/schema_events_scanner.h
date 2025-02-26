// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

#pragma once

#include "exec/vectorized/schema_scanner.h"
#include "gen_cpp/FrontendService_types.h"

namespace starrocks::vectorized {

class SchemaEventsScanner : public SchemaScanner {
public:
    SchemaEventsScanner();
    virtual ~SchemaEventsScanner();

private:
    static SchemaScanner::ColumnDesc _s_cols_events[];
};

} // namespace starrocks::vectorized