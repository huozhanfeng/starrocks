// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/qe/SessionVariable.java

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

package com.starrocks.qe;

import com.starrocks.catalog.Catalog;
import com.starrocks.common.FeMetaVersion;
import com.starrocks.common.io.Text;
import com.starrocks.common.io.Writable;
import com.starrocks.common.util.CompressionUtils;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.qe.VariableMgr.VarAttr;
import com.starrocks.thrift.TCompressionType;
import com.starrocks.thrift.TQueryOptions;
import org.json.JSONObject;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;

// System variable
public class SessionVariable implements Serializable, Writable, Cloneable {
    public static final String EXEC_MEM_LIMIT = "exec_mem_limit";
    public static final String QUERY_TIMEOUT = "query_timeout";
    public static final String MAX_EXECUTION_TIME = "max_execution_time";
    public static final String IS_REPORT_SUCCESS = "is_report_success";
    public static final String PROFILING = "profiling";
    public static final String SQL_MODE = "sql_mode";
    public static final String RESOURCE_VARIABLE = "resource_group";
    public static final String AUTO_COMMIT = "autocommit";
    public static final String TX_ISOLATION = "tx_isolation";
    public static final String CHARACTER_SET_CLIENT = "character_set_client";
    public static final String CHARACTER_SET_CONNNECTION = "character_set_connection";
    public static final String CHARACTER_SET_RESULTS = "character_set_results";
    public static final String CHARACTER_SET_SERVER = "character_set_server";
    public static final String COLLATION_CONNECTION = "collation_connection";
    public static final String COLLATION_DATABASE = "collation_database";
    public static final String COLLATION_SERVER = "collation_server";
    public static final String SQL_AUTO_IS_NULL = "SQL_AUTO_IS_NULL";
    public static final String SQL_SELECT_LIMIT = "sql_select_limit";
    public static final String MAX_ALLOWED_PACKET = "max_allowed_packet";
    public static final String AUTO_INCREMENT_INCREMENT = "auto_increment_increment";
    public static final String QUERY_CACHE_TYPE = "query_cache_type";
    public static final String INTERACTIVE_TIMTOUT = "interactive_timeout";
    public static final String WAIT_TIMEOUT = "wait_timeout";
    public static final String NET_WRITE_TIMEOUT = "net_write_timeout";
    public static final String NET_READ_TIMEOUT = "net_read_timeout";
    public static final String TIME_ZONE = "time_zone";
    public static final String SQL_SAFE_UPDATES = "sql_safe_updates";
    public static final String NET_BUFFER_LENGTH = "net_buffer_length";
    public static final String CODEGEN_LEVEL = "codegen_level";
    // mem limit can't smaller than bufferpool's default page size
    public static final int MIN_EXEC_MEM_LIMIT = 2097152;
    public static final String BATCH_SIZE = "batch_size";
    public static final String DISABLE_STREAMING_PREAGGREGATIONS = "disable_streaming_preaggregations";
    public static final String STREAMING_PREAGGREGATION_MODE = "streaming_preaggregation_mode";
    public static final String DISABLE_COLOCATE_JOIN = "disable_colocate_join";
    public static final String PARALLEL_FRAGMENT_EXEC_INSTANCE_NUM = "parallel_fragment_exec_instance_num";
    public static final String ENABLE_INSERT_STRICT = "enable_insert_strict";
    public static final String ENABLE_SPILLING = "enable_spilling";
    public static final String PREFER_JOIN_METHOD = "prefer_join_method";
    // if set to true, some of stmt will be forwarded to master FE to get result
    public static final String FORWARD_TO_MASTER = "forward_to_master";
    // user can set instance num after exchange, no need to be equal to nums of before exchange
    public static final String PARALLEL_EXCHANGE_INSTANCE_NUM = "parallel_exchange_instance_num";
    /*
     * configure the mem limit of load process on BE.
     * Previously users used exec_mem_limit to set memory limits.
     * To maintain compatibility, the default value of load_mem_limit is 0,
     * which means that the load memory limit is still using exec_mem_limit.
     * Users can set a value greater than zero to explicitly specify the load memory limit.
     * This variable is mainly for INSERT operation, because INSERT operation has both query and load part.
     * Using only the exec_mem_limit variable does not make a good distinction of memory limit between the two parts.
     */
    public static final String LOAD_MEM_LIMIT = "load_mem_limit";
    public static final String USE_V2_ROLLUP = "use_v2_rollup";
    public static final String TEST_MATERIALIZED_VIEW = "test_materialized_view";
    public static final String REWRITE_COUNT_DISTINCT_TO_BITMAP_HLL = "rewrite_count_distinct_to_bitmap_hll";
    public static final String EVENT_SCHEDULER = "event_scheduler";
    public static final String STORAGE_ENGINE = "storage_engine";
    public static final String DIV_PRECISION_INCREMENT = "div_precision_increment";

    // see comment of `starrocks_max_scan_key_num` and `max_pushdown_conditions_per_column` in BE config
    public static final String MAX_SCAN_KEY_NUM = "max_scan_key_num";
    public static final String MAX_PUSHDOWN_CONDITIONS_PER_COLUMN = "max_pushdown_conditions_per_column";

    // vectorized engine flag
    public static final String ENABLE_VECTORIZED_ENGINE = "enable_vectorized_engine";

    // use new execution engine instead of the old one if enable_pipeline_engine is true,
    // the new execution engine split a fragment into pipelines, then create several drivers
    // from the pipeline for parallel executing, threads from global pool pick out the
    // ready-to-run drivers to execute and switch the blocked drivers off cores;
    // the old one put each fragment into a thread, then pull final result from the root fragment,
    // leaf fragments always waiting for disk IO's completion and intermediate fragments wait
    // for chunk arrival and blocked on receive queues of its exchange node, so among
    // threads allocated for a query in the old execution engine, only small number of
    // them do the real work on core.
    public static final String ENABLE_PIPELINE_ENGINE = "enable_pipeline_engine";

    public static final String PIPELINE_QUERY_THREADS = "query_threads";

    public static final String PIPELINE_SCAN_MODE = "pipeline_scan_mode";

    public static final String PIPELINE_QUERY_EXPIRE_SECONDS = "pipeline_query_expire_seconds";

    // vectorized insert flag
    public static final String ENABLE_VECTORIZED_INSERT = "enable_vectorized_insert";

    // hash join right table push down
    public static final String HASH_JOIN_PUSH_DOWN_RIGHT_TABLE = "hash_join_push_down_right_table";

    // disable join reorder
    public static final String DISABLE_JOIN_REORDER = "disable_join_reorder";

    // the maximum time, in seconds, waiting for an insert statement's transaction state
    // transfer from COMMITTED to VISIBLE.
    // If the time exceeded but the transaction state is not VISIBLE, the transaction will
    // still be considered as succeeded and an OK packet will be sent to the client, but
    // the affected records may not be visible to the subsequent queries, even if it's in
    // the same session.
    public static final String TRANSACTION_VISIBLE_WAIT_TIMEOUT = "tx_visible_wait_timeout";
    // only for Aliyun DTS, no actual use.
    public static final String FOREIGN_KEY_CHECKS = "foreign_key_checks";

    // force schedule local be for HybridBackendSelector
    // only for hive external table now
    public static final String FORCE_SCHEDULE_LOCAL = "force_schedule_local";

    // --------  New planner session variables start --------
    public static final String ENABLE_CBO = "enable_cbo";
    public static final String ENABLE_CBO_META = "enable_cbo_meta";
    public static final String ENABLE_NEW_PLANNER_PUSH_DOWN_JOIN_TO_AGG =
            "enable_new_planner_push_down_join_to_agg";
    public static final String NEW_PLANER_AGG_STAGE = "new_planner_agg_stage";
    public static final String BROADCAST_ROW_LIMIT = "broadcast_row_limit";
    public static final String NEW_PLANNER_OPTIMIZER_TIMEOUT = "new_planner_optimize_timeout";
    public static final String ENABLE_GROUPBY_USE_OUTPUT_ALIAS = "enable_groupby_use_output_alias";
    public static final String ENABLE_QUERY_DUMP = "enable_query_dump";

    public static final String CBO_MAX_REORDER_NODE_USE_EXHAUSTIVE = "cbo_max_reorder_node_use_exhaustive";
    public static final String CBO_ENABLE_DP_JOIN_REORDER = "cbo_enable_dp_join_reorder";
    public static final String CBO_MAX_REORDER_NODE_USE_DP = "cbo_max_reorder_node_use_dp";
    public static final String CBO_ENABLE_GREEDY_JOIN_REORDER = "cbo_enable_greedy_join_reorder";
    // --------  New planner session variables end --------

    // Type of compression of transmitted data
    // Different compression algorithms may be chosen in different hardware environments. For example,
    // in the case of insufficient network bandwidth, but excess CPU resources, an algorithm with a
    // higher compression ratio may be chosen to use more CPU and make the overall query time lower.
    public static final String TRANSMISSION_COMPRESSION_TYPE = "transmission_compression_type";

    public static final String RUNTIME_JOIN_FILTER_PUSH_DOWN_LIMIT = "runtime_join_filter_push_down_limit";
    public static final String ENABLE_GLOBAL_RUNTIME_FILTER = "enable_global_runtime_filter";

    // use vectorized engine
    @VariableMgr.VarAttr(name = ENABLE_VECTORIZED_ENGINE, alias = "vectorized_engine_enable")
    private boolean vectorizedEngineEnable = true;

    @VariableMgr.VarAttr(name = ENABLE_PIPELINE_ENGINE)
    private boolean enablePipelineEngine = false;

    // use vectorized insert
    @VariableMgr.VarAttr(name = ENABLE_VECTORIZED_INSERT, alias = "vectorized_insert_enable")
    private boolean vectorizedInsertEnable = true;

    // max memory used on every backend.
    @VariableMgr.VarAttr(name = EXEC_MEM_LIMIT)
    public long maxExecMemByte = 2147483648L;

    @VariableMgr.VarAttr(name = ENABLE_SPILLING)
    public boolean enableSpilling = false;

    // query timeout in second.
    @VariableMgr.VarAttr(name = QUERY_TIMEOUT)
    private int queryTimeoutS = 300;

    // query timeout in millisecond, currently nouse, only for compatible.
    @VariableMgr.VarAttr(name = MAX_EXECUTION_TIME)
    private long maxExecutionTime = 3000000;

    // if true, need report to coordinator when plan fragment execute successfully.
    @VariableMgr.VarAttr(name = IS_REPORT_SUCCESS)
    private boolean isReportSucc = false;
    // only for Aliyun DTS, useless.
    @VariableMgr.VarAttr(name = PROFILING)
    private boolean openProfile = false;

    // Set sqlMode to empty string
    @VariableMgr.VarAttr(name = SQL_MODE)
    private long sqlMode = 0L;

    @VariableMgr.VarAttr(name = RESOURCE_VARIABLE)
    private String resourceGroup = "normal";

    // this is used to make mysql client happy
    @VariableMgr.VarAttr(name = AUTO_COMMIT)
    private boolean autoCommit = true;

    // this is used to make c3p0 library happy
    @VariableMgr.VarAttr(name = TX_ISOLATION)
    private String txIsolation = "REPEATABLE-READ";

    // this is used to make c3p0 library happy
    @VariableMgr.VarAttr(name = CHARACTER_SET_CLIENT)
    private String charsetClient = "utf8";
    @VariableMgr.VarAttr(name = CHARACTER_SET_CONNNECTION)
    private String charsetConnection = "utf8";
    @VariableMgr.VarAttr(name = CHARACTER_SET_RESULTS)
    private String charsetResults = "utf8";
    @VariableMgr.VarAttr(name = CHARACTER_SET_SERVER)
    private String charsetServer = "utf8";
    @VariableMgr.VarAttr(name = COLLATION_CONNECTION)
    private String collationConnection = "utf8_general_ci";
    @VariableMgr.VarAttr(name = COLLATION_DATABASE)
    private String collationDatabase = "utf8_general_ci";

    @VariableMgr.VarAttr(name = COLLATION_SERVER)
    private String collationServer = "utf8_general_ci";

    // this is used to make c3p0 library happy
    @VariableMgr.VarAttr(name = SQL_AUTO_IS_NULL)
    private boolean sqlAutoIsNull = false;

    public static final long DEFAULT_SELECT_LIMIT = 9223372036854775807L;
    @VariableMgr.VarAttr(name = SQL_SELECT_LIMIT)
    private long sqlSelectLimit = DEFAULT_SELECT_LIMIT;

    // this is used to make c3p0 library happy
    @VariableMgr.VarAttr(name = MAX_ALLOWED_PACKET)
    private int maxAllowedPacket = 1048576;

    @VariableMgr.VarAttr(name = AUTO_INCREMENT_INCREMENT)
    private int autoIncrementIncrement = 1;

    // this is used to make c3p0 library happy
    @VariableMgr.VarAttr(name = QUERY_CACHE_TYPE)
    private int queryCacheType = 0;

    // The number of seconds the server waits for activity on an interactive connection before closing it
    @VariableMgr.VarAttr(name = INTERACTIVE_TIMTOUT)
    private int interactiveTimeout = 3600;

    // The number of seconds the server waits for activity on a noninteractive connection before closing it.
    @VariableMgr.VarAttr(name = WAIT_TIMEOUT)
    private int waitTimeout = 28800;

    // The number of seconds to wait for a block to be written to a connection before aborting the write
    @VariableMgr.VarAttr(name = NET_WRITE_TIMEOUT)
    private int netWriteTimeout = 60;

    // The number of seconds to wait for a block to be written to a connection before aborting the write
    @VariableMgr.VarAttr(name = NET_READ_TIMEOUT)
    private int netReadTimeout = 60;

    // The current time zone
    @VariableMgr.VarAttr(name = TIME_ZONE)
    private String timeZone = TimeUtils.getSystemTimeZone().getID();

    @VariableMgr.VarAttr(name = PARALLEL_EXCHANGE_INSTANCE_NUM)
    private int exchangeInstanceParallel = -1;

    @VariableMgr.VarAttr(name = SQL_SAFE_UPDATES)
    private int sqlSafeUpdates = 0;

    // only
    @VariableMgr.VarAttr(name = NET_BUFFER_LENGTH, flag = VariableMgr.READ_ONLY)
    private int netBufferLength = 16384;

    // if true, need report to coordinator when plan fragment execute successfully.
    @VariableMgr.VarAttr(name = CODEGEN_LEVEL)
    private int codegenLevel = 0;

    @VariableMgr.VarAttr(name = BATCH_SIZE)
    private int batchSize = 1024;

    @VariableMgr.VarAttr(name = DISABLE_STREAMING_PREAGGREGATIONS)
    private boolean disableStreamPreaggregations = false;

    @VariableMgr.VarAttr(name = STREAMING_PREAGGREGATION_MODE)
    private String streamingPreaggregationMode = "auto"; // auto, force_streaming, force_preaggregation

    @VariableMgr.VarAttr(name = DISABLE_COLOCATE_JOIN)
    private boolean disableColocateJoin = false;

    @VariableMgr.VarAttr(name = PREFER_JOIN_METHOD)
    private String preferJoinMethod = "broadcast";

    /*
     * the parallel exec instance num for one Fragment in one BE
     * 1 means disable this feature
     */
    @VariableMgr.VarAttr(name = PARALLEL_FRAGMENT_EXEC_INSTANCE_NUM)
    private int parallelExecInstanceNum = 1;

    @VariableMgr.VarAttr(name = PIPELINE_QUERY_THREADS)
    private int pipelineQueryThreads = 1;

    // 1 means that ScanOperators use async io, otherwise, use sync io instead.
    @VariableMgr.VarAttr(name = PIPELINE_SCAN_MODE)
    private int pipelineScanMode = 1;

    // a query that can not make any progress for more than pipelineQueryExpireSeconds
    // (300s in default) will be canceled.
    @VariableMgr.VarAttr(name = PIPELINE_QUERY_EXPIRE_SECONDS)
    private int pipelineQueryExpireSeconds = 300;

    @VariableMgr.VarAttr(name = ENABLE_INSERT_STRICT)
    private boolean enableInsertStrict = true;

    @VariableMgr.VarAttr(name = FORWARD_TO_MASTER)
    private boolean forwardToMaster = false;

    @VariableMgr.VarAttr(name = LOAD_MEM_LIMIT)
    private long loadMemLimit = 0L;

    @VariableMgr.VarAttr(name = USE_V2_ROLLUP)
    private boolean useV2Rollup = false;

    // TODO(ml): remove it after test
    @VariableMgr.VarAttr(name = TEST_MATERIALIZED_VIEW)
    private boolean testMaterializedView = false;

    @VariableMgr.VarAttr(name = REWRITE_COUNT_DISTINCT_TO_BITMAP_HLL)
    private boolean rewriteCountDistinct = true;

    // compatible with some mysql client connect, say DataGrip of JetBrains
    @VariableMgr.VarAttr(name = EVENT_SCHEDULER)
    private String eventScheduler = "OFF";
    @VariableMgr.VarAttr(name = STORAGE_ENGINE)
    private String storageEngine = "olap";
    @VariableMgr.VarAttr(name = DIV_PRECISION_INCREMENT)
    private int divPrecisionIncrement = 4;

    // -1 means unset, BE will use its config value
    @VariableMgr.VarAttr(name = MAX_SCAN_KEY_NUM)
    private int maxScanKeyNum = -1;
    @VariableMgr.VarAttr(name = MAX_PUSHDOWN_CONDITIONS_PER_COLUMN)
    private int maxPushdownConditionsPerColumn = -1;

    @VariableMgr.VarAttr(name = HASH_JOIN_PUSH_DOWN_RIGHT_TABLE)
    private boolean hashJoinPushDownRightTable = true;

    @VariableMgr.VarAttr(name = DISABLE_JOIN_REORDER)
    private boolean disableJoinReorder = false;

    @VariableMgr.VarAttr(name = CBO_MAX_REORDER_NODE_USE_EXHAUSTIVE)
    private int cboMaxReorderNodeUseExhaustive = 4;

    @VariableMgr.VarAttr(name = CBO_ENABLE_DP_JOIN_REORDER)
    private boolean cboEnableDPJoinReorder = true;

    @VariableMgr.VarAttr(name = CBO_MAX_REORDER_NODE_USE_DP)
    private long cboMaxReorderNodeUseDP = 10;

    @VariableMgr.VarAttr(name = CBO_ENABLE_GREEDY_JOIN_REORDER)
    private boolean cboEnableGreedyJoinReorder = true;

    @VariableMgr.VarAttr(name = TRANSACTION_VISIBLE_WAIT_TIMEOUT)
    private long transactionVisibleWaitTimeout = 10;

    // only for Aliyun DTS, useless.
    @VariableMgr.VarAttr(name = FOREIGN_KEY_CHECKS)
    private boolean foreignKeyChecks = true;

    @VariableMgr.VarAttr(name = FORCE_SCHEDULE_LOCAL)
    private boolean forceScheduleLocal = false;

    @VariableMgr.VarAttr(name = ENABLE_CBO_META, alias = ENABLE_CBO, show = ENABLE_CBO)
    private boolean enableCbo = true;

    @VariableMgr.VarAttr(name = ENABLE_NEW_PLANNER_PUSH_DOWN_JOIN_TO_AGG)
    private boolean enableNewPlannerPushDownJoinToAgg = false;

    @VariableMgr.VarAttr(name = BROADCAST_ROW_LIMIT)
    private long broadcastRowCountLimit = 15000000;

    @VariableMgr.VarAttr(name = NEW_PLANNER_OPTIMIZER_TIMEOUT)
    private long optimizerExecuteTimeout = 3000;

    @VariableMgr.VarAttr(name = ENABLE_QUERY_DUMP)
    private boolean enable_query_dump = false;

    // value should be 0~4
    // 0 represents automatic selection, and 1, 2, 3, and 4 represent forced selection of AGG of
    // corresponding stages respectively. However, stages 3 and 4 can only be generated in
    // single-column distinct scenarios
    @VariableMgr.VarAttr(name = NEW_PLANER_AGG_STAGE)
    private int new_planner_agg_stage = 0;

    @VariableMgr.VarAttr(name = TRANSMISSION_COMPRESSION_TYPE)
    private String transmission_compression_type = "LZ4";

    @VariableMgr.VarAttr(name = RUNTIME_JOIN_FILTER_PUSH_DOWN_LIMIT)
    private long runtimeJoinFilterPushDownLimit = 1024000;

    @VariableMgr.VarAttr(name = ENABLE_GLOBAL_RUNTIME_FILTER)
    private boolean enableGlobalRuntimeFilter = true;

    //In order to be compatible with the logic of the old planner,
    //When the column name is the same as the alias name,
    //the alias will be used as the groupby column if set to true.
    @VariableMgr.VarAttr(name = ENABLE_GROUPBY_USE_OUTPUT_ALIAS)
    private boolean enableGroupbyUseOutputAlias = false;

    public long getMaxExecMemByte() {
        return maxExecMemByte;
    }

    public long getLoadMemLimit() {
        return loadMemLimit;
    }

    public int getQueryTimeoutS() {
        return queryTimeoutS;
    }

    public boolean isReportSucc() {
        return isReportSucc;
    }

    public void setReportSuccess(boolean isReportSuccess) {
        this.isReportSucc = isReportSuccess;
    }

    public int getWaitTimeoutS() {
        return waitTimeout;
    }

    public long getSqlMode() {
        return sqlMode;
    }

    public void setSqlMode(long sqlMode) {
        this.sqlMode = sqlMode;
    }

    public String getCharsetClient() {
        return charsetClient;
    }

    public String getCharsetConnection() {
        return charsetConnection;
    }

    public String getCharsetResults() {
        return charsetResults;
    }

    public String getCollationDatabase() {
        return collationDatabase;
    }

    public String getCollationServer() {
        return collationServer;
    }

    public long getSqlSelectLimit() {
        if (sqlSelectLimit < 0) {
            return DEFAULT_SELECT_LIMIT;
        }
        return sqlSelectLimit;
    }

    public void setSqlSelectLimit(long limit) {
        if (limit < 0) {
            return;
        }
        this.sqlSelectLimit = limit;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public void setMaxExecMemByte(long maxExecMemByte) {
        if (maxExecMemByte < MIN_EXEC_MEM_LIMIT) {
            this.maxExecMemByte = MIN_EXEC_MEM_LIMIT;
        } else {
            this.maxExecMemByte = maxExecMemByte;
        }
    }

    public void setLoadMemLimit(long loadMemLimit) {
        this.loadMemLimit = loadMemLimit;
    }

    public void setQueryTimeoutS(int queryTimeoutS) {
        this.queryTimeoutS = queryTimeoutS;
    }

    public String getResourceGroup() {
        return resourceGroup;
    }

    public void setResourceGroup(String resourceGroup) {
        this.resourceGroup = resourceGroup;
    }

    public boolean isDisableColocateJoin() {
        return disableColocateJoin;
    }

    public void setPreferJoinMethod(String preferJoinMethod) {
        this.preferJoinMethod = preferJoinMethod;
    }

    public int getParallelExecInstanceNum() {
        return parallelExecInstanceNum;
    }

    public void setParallelExecInstanceNum(int parallelExecInstanceNum) {
        this.parallelExecInstanceNum = parallelExecInstanceNum;
    }

    public int getExchangeInstanceParallel() {
        return exchangeInstanceParallel;
    }

    public boolean getEnableInsertStrict() {
        return enableInsertStrict;
    }

    public void setEnableInsertStrict(boolean enableInsertStrict) {
        this.enableInsertStrict = enableInsertStrict;
    }

    public boolean getForwardToMaster() {
        return forwardToMaster;
    }

    public boolean isUseV2Rollup() {
        return useV2Rollup;
    }

    // for unit test
    public void setUseV2Rollup(boolean useV2Rollup) {
        this.useV2Rollup = useV2Rollup;
    }

    public boolean getTestMaterializedView() {
        return this.testMaterializedView;
    }

    public void setTestMaterializedView(boolean testMaterializedView) {
        this.testMaterializedView = testMaterializedView;
    }

    public boolean isRewriteCountDistinct() {
        return rewriteCountDistinct;
    }

    public void setRewriteCountDistinct(boolean rewriteCountDistinct) {
        this.rewriteCountDistinct = rewriteCountDistinct;
    }

    public void setMaxScanKeyNum(int maxScanKeyNum) {
        this.maxScanKeyNum = maxScanKeyNum;
    }

    public void setMaxPushdownConditionsPerColumn(int maxPushdownConditionsPerColumn) {
        this.maxPushdownConditionsPerColumn = maxPushdownConditionsPerColumn;
    }

    public boolean useVectorizedEngineEnable() {
        return vectorizedEngineEnable;
    }

    public void setVectorizedEngineEnable(boolean vectorizedEngineEnable) {
        this.vectorizedEngineEnable = vectorizedEngineEnable;
    }

    public boolean isVectorizedInsertEnable() {
        return vectorizedInsertEnable;
    }

    public boolean isHashJoinPushDownRightTable() {
        return this.hashJoinPushDownRightTable;
    }

    public String getStreamingPreaggregationMode() {
        return streamingPreaggregationMode;
    }

    public boolean isDisableJoinReorder() {
        return disableJoinReorder;
    }

    public void disableJoinReorder() {
        this.disableJoinReorder = true;
    }

    public void enableJoinReorder() {
        this.disableJoinReorder = false;
    }

    public boolean isCboEnableDPJoinReorder() {
        return cboEnableDPJoinReorder;
    }

    public void disableDPJoinReorder() {
        this.cboEnableDPJoinReorder = false;
    }

    public void enableDPJoinReorder() {
        this.cboEnableDPJoinReorder = true;
    }

    public long getCboMaxReorderNodeUseDP() {
        return cboMaxReorderNodeUseDP;
    }

    public boolean isCboEnableGreedyJoinReorder() {
        return cboEnableGreedyJoinReorder;
    }

    public void disableGreedyJoinReorder() {
        this.cboEnableGreedyJoinReorder = false;
    }

    public void enableGreedyJoinReorder() {
        this.cboEnableGreedyJoinReorder = true;
    }

    public long getTransactionVisibleWaitTimeout() {
        return transactionVisibleWaitTimeout;
    }

    public boolean isForceScheduleLocal() {
        return forceScheduleLocal;
    }

    public boolean isEnableNewPlanner() {
        return enableCbo;
    }

    public void disableNewPlanner() {
        this.enableCbo = false;
    }

    public void enableNewPlanner() {
        this.enableCbo = true;
    }

    public boolean isEnableNewPlannerPushDownJoinToAgg() {
        return enableNewPlannerPushDownJoinToAgg;
    }

    public void setEnableNewPlannerPushDownJoinToAgg(boolean enableNewPlannerPushDownJoinToAgg) {
        this.enableNewPlannerPushDownJoinToAgg = enableNewPlannerPushDownJoinToAgg;
    }

    public int getCboMaxReorderNodeUseExhaustive() {
        return cboMaxReorderNodeUseExhaustive;
    }

    public int getNewPlannerAggStage() {
        return new_planner_agg_stage;
    }

    public void setNewPlanerAggStage(int stage) {
        this.new_planner_agg_stage = stage;
    }

    public void setMaxTransformReorderJoins(int maxReorderNodeUseExhaustive) {
        this.cboMaxReorderNodeUseExhaustive = maxReorderNodeUseExhaustive;
    }

    public long getBroadcastRowCountLimit() {
        return broadcastRowCountLimit;
    }

    public long getOptimizerExecuteTimeout() {
        return optimizerExecuteTimeout;
    }

    public void setOptimizerExecuteTimeout(long optimizerExecuteTimeout) {
        this.optimizerExecuteTimeout = optimizerExecuteTimeout;
    }

    public boolean getEnableGroupbyUseOutputAlias() {
        return enableGroupbyUseOutputAlias;
    }

    public boolean getEnableQueryDump() {
        return enable_query_dump;
    }

    public boolean getEnableGlobalRuntimeFilter() {
        return enableGlobalRuntimeFilter;
    }

    public void setEnableGlobalRuntimeFilter(boolean value) {
        enableGlobalRuntimeFilter = value;
    }

    public boolean isEnablePipelineEngine() {
        return enablePipelineEngine;
    }

    // Serialize to thrift object
    // used for rest api
    public TQueryOptions toThrift() {
        TQueryOptions tResult = new TQueryOptions();
        tResult.setMem_limit(maxExecMemByte);

        tResult.setMin_reservation(0);
        tResult.setMax_reservation(maxExecMemByte);
        tResult.setInitial_reservation_total_claims(maxExecMemByte);
        tResult.setBuffer_pool_limit(maxExecMemByte);
        // Avoid integer overflow
        tResult.setQuery_timeout(Math.min(Integer.MAX_VALUE / 1000, queryTimeoutS));
        tResult.setIs_report_success(isReportSucc);
        tResult.setCodegen_level(codegenLevel);

        tResult.setBatch_size(batchSize);
        tResult.setDisable_stream_preaggregations(disableStreamPreaggregations);
        tResult.setLoad_mem_limit(loadMemLimit);

        if (maxScanKeyNum > -1) {
            tResult.setMax_scan_key_num(maxScanKeyNum);
        }
        if (maxPushdownConditionsPerColumn > -1) {
            tResult.setMax_pushdown_conditions_per_column(maxPushdownConditionsPerColumn);
        }
        tResult.setEnable_spilling(enableSpilling);

        // Compression Type
        TCompressionType compressionType = CompressionUtils.findTCompressionByName(transmission_compression_type);
        if (compressionType != null) {
            tResult.setTransmission_compression_type(compressionType);
        }

        tResult.setRuntime_join_filter_pushdown_limit(runtimeJoinFilterPushDownLimit);
        final int global_runtime_filter_wait_timeout = 20;
        final int global_runtime_filter_rpc_timeout = 400;
        tResult.setRuntime_filter_wait_timeout_ms(global_runtime_filter_wait_timeout);
        tResult.setRuntime_filter_send_timeout_ms(global_runtime_filter_rpc_timeout);
        tResult.setQuery_threads(pipelineQueryThreads);
        tResult.setPipeline_scan_mode(pipelineScanMode);
        tResult.setPipeline_query_expire_seconds(pipelineQueryExpireSeconds);
        return tResult;
    }

    public String getJsonString() throws IOException {
        JSONObject root = new JSONObject();
        try {
            for (Field field : SessionVariable.class.getDeclaredFields()) {
                VarAttr attr = field.getAnnotation(VarAttr.class);
                if (attr == null) {
                    continue;
                }
                switch (field.getType().getSimpleName()) {
                    case "boolean":
                    case "int":
                    case "long":
                    case "float":
                    case "double":
                    case "String":
                        root.put(attr.name(), field.get(this));
                        break;
                    default:
                        // Unsupported type variable.
                        throw new IOException("invalid type: " + field.getType().getSimpleName());
                }
            }
        } catch (Exception e) {
            throw new IOException("failed to write session variable: " + e.getMessage());
        }
        return root.toString();
    }

    @Override
    public void write(DataOutput out) throws IOException {
        Text.writeString(out, getJsonString());
    }

    public void readFields(DataInput in) throws IOException {
        if (Catalog.getCurrentCatalogJournalVersion() < FeMetaVersion.VERSION_67) {
            codegenLevel = in.readInt();
            netBufferLength = in.readInt();
            sqlSafeUpdates = in.readInt();
            timeZone = Text.readString(in);
            netReadTimeout = in.readInt();
            netWriteTimeout = in.readInt();
            waitTimeout = in.readInt();
            interactiveTimeout = in.readInt();
            queryCacheType = in.readInt();
            autoIncrementIncrement = in.readInt();
            maxAllowedPacket = in.readInt();
            sqlSelectLimit = in.readLong();
            sqlAutoIsNull = in.readBoolean();
            collationDatabase = Text.readString(in);
            collationConnection = Text.readString(in);
            charsetServer = Text.readString(in);
            charsetResults = Text.readString(in);
            charsetConnection = Text.readString(in);
            charsetClient = Text.readString(in);
            txIsolation = Text.readString(in);
            autoCommit = in.readBoolean();
            resourceGroup = Text.readString(in);
            if (Catalog.getCurrentCatalogJournalVersion() >= FeMetaVersion.VERSION_65) {
                sqlMode = in.readLong();
            } else {
                // read old version SQL mode
                Text.readString(in);
                sqlMode = 0L;
            }
            isReportSucc = in.readBoolean();
            queryTimeoutS = in.readInt();
            maxExecMemByte = in.readLong();
            if (Catalog.getCurrentCatalogJournalVersion() >= FeMetaVersion.VERSION_37) {
                collationServer = Text.readString(in);
            }
            if (Catalog.getCurrentCatalogJournalVersion() >= FeMetaVersion.VERSION_38) {
                batchSize = in.readInt();
                disableStreamPreaggregations = in.readBoolean();
                parallelExecInstanceNum = in.readInt();
            }
            if (Catalog.getCurrentCatalogJournalVersion() >= FeMetaVersion.VERSION_62) {
                exchangeInstanceParallel = in.readInt();
            }
        } else {
            readFromJson(in);
        }
    }

    private void readFromJson(DataInput in) throws IOException {
        String json = Text.readString(in);
        replayFromJson(json);
    }

    public void replayFromJson(String json) throws IOException {
        JSONObject root = new JSONObject(json);
        try {
            for (Field field : SessionVariable.class.getDeclaredFields()) {
                VarAttr attr = field.getAnnotation(VarAttr.class);
                if (attr == null) {
                    continue;
                }

                if (!root.has(attr.name())) {
                    continue;
                }

                switch (field.getType().getSimpleName()) {
                    case "boolean":
                        field.set(this, root.getBoolean(attr.name()));
                        break;
                    case "int":
                        field.set(this, root.getInt(attr.name()));
                        break;
                    case "long":
                        field.set(this, root.getLong(attr.name()));
                        break;
                    case "float":
                        field.set(this, root.getFloat(attr.name()));
                        break;
                    case "double":
                        field.set(this, root.getDouble(attr.name()));
                        break;
                    case "String":
                        field.set(this, root.getString(attr.name()));
                        break;
                    default:
                        // Unsupported type variable.
                        throw new IOException("invalid type: " + field.getType().getSimpleName());
                }
            }
        } catch (Exception e) {
            throw new IOException("failed to read session variable: " + e.getMessage());
        }
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
}
