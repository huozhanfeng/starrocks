// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

#include "exec/vectorized/schema_scanner/schema_events_scanner.h"

#include "runtime/primitive_type.h"
#include "runtime/string_value.h"

namespace starrocks::vectorized {

SchemaScanner::ColumnDesc SchemaEventsScanner::_s_cols_events[] = {
        //   name,       type,          size,                     is_null
        {"EVENT_CATALOG", TYPE_VARCHAR, sizeof(StringValue), false},
        {"EVENT_SCHEMA", TYPE_VARCHAR, sizeof(StringValue), false},
        {"EVENT_NAME", TYPE_VARCHAR, sizeof(StringValue), false},
        {"DEFINER", TYPE_VARCHAR, sizeof(StringValue), false},
        {"TIME_ZONE", TYPE_VARCHAR, sizeof(StringValue), false},
        {"EVENT_BODY", TYPE_VARCHAR, sizeof(StringValue), false},
        {"EVENT_DEFINITION", TYPE_VARCHAR, sizeof(StringValue), false},
        {"EVENT_TYPE", TYPE_BIGINT, sizeof(StringValue), false},
        {"EXECUTE_AT", TYPE_DATETIME, sizeof(DateTimeValue), true},
        {"INTERVAL_VALUE", TYPE_VARCHAR, sizeof(StringValue), true},
        {"INTERVAL_FIELD", TYPE_VARCHAR, sizeof(StringValue), true},
        {"SQL_MODE", TYPE_VARCHAR, sizeof(StringValue), false},
        {"STARTS", TYPE_DATETIME, sizeof(DateTimeValue), true},
        {"ENDS", TYPE_DATETIME, sizeof(DateTimeValue), true},
        {"STATUS", TYPE_VARCHAR, sizeof(StringValue), false},
        {"ON_COMPLETION", TYPE_VARCHAR, sizeof(StringValue), false},
        {"CREATED", TYPE_DATETIME, sizeof(DateTimeValue), false},
        {"LAST_ALTERED", TYPE_DATETIME, sizeof(DateTimeValue), false},
        {"LAST_EXECUTED", TYPE_DATETIME, sizeof(DateTimeValue), true},
        {"EVENT_COMMENT", TYPE_VARCHAR, sizeof(StringValue), false},
        {"ORIGINATOR", TYPE_VARCHAR, sizeof(StringValue), false},
        {"CHARACTER_SET_CLIENT", TYPE_VARCHAR, sizeof(StringValue), false},
        {"COLLATION_CONNECTION", TYPE_VARCHAR, sizeof(StringValue), false},
        {"DATABASE_COLLATION", TYPE_VARCHAR, sizeof(StringValue), false},
};

SchemaEventsScanner::SchemaEventsScanner()
        : SchemaScanner(_s_cols_events, sizeof(_s_cols_events) / sizeof(SchemaScanner::ColumnDesc)) {}

SchemaEventsScanner::~SchemaEventsScanner() {}

} // namespace starrocks::vectorized
