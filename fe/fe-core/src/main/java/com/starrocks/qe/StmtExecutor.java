// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/qe/StmtExecutor.java

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

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.starrocks.analysis.AddSqlBlackListStmt;
import com.starrocks.analysis.AnalyzeStmt;
import com.starrocks.analysis.Analyzer;
import com.starrocks.analysis.CreateAnalyzeJobStmt;
import com.starrocks.analysis.CreateTableAsSelectStmt;
import com.starrocks.analysis.DdlStmt;
import com.starrocks.analysis.DelSqlBlackListStmt;
import com.starrocks.analysis.EnterStmt;
import com.starrocks.analysis.ExportStmt;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.InsertStmt;
import com.starrocks.analysis.KillStmt;
import com.starrocks.analysis.QueryStmt;
import com.starrocks.analysis.RedirectStatus;
import com.starrocks.analysis.SelectStmt;
import com.starrocks.analysis.SetStmt;
import com.starrocks.analysis.SetVar;
import com.starrocks.analysis.ShowStmt;
import com.starrocks.analysis.SqlParser;
import com.starrocks.analysis.SqlScanner;
import com.starrocks.analysis.StatementBase;
import com.starrocks.analysis.StmtRewriter;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.analysis.UnsupportedStmt;
import com.starrocks.analysis.UseStmt;
import com.starrocks.catalog.BrokerTable;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.ExternalOlapTable;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Table.TableType;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.FeConstants;
import com.starrocks.common.MetaNotFoundException;
import com.starrocks.common.QueryDumpLog;
import com.starrocks.common.UserException;
import com.starrocks.common.Version;
import com.starrocks.common.util.DebugUtil;
import com.starrocks.common.util.ProfileManager;
import com.starrocks.common.util.RuntimeProfile;
import com.starrocks.common.util.SqlParserUtils;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.load.EtlJobType;
import com.starrocks.load.loadv2.LoadJob;
import com.starrocks.meta.SqlBlackList;
import com.starrocks.metric.MetricRepo;
import com.starrocks.metric.TableMetricsEntity;
import com.starrocks.metric.TableMetricsRegistry;
import com.starrocks.mysql.MysqlChannel;
import com.starrocks.mysql.MysqlEofPacket;
import com.starrocks.mysql.MysqlSerializer;
import com.starrocks.mysql.privilege.PrivPredicate;
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.planner.DataSink;
import com.starrocks.planner.ExportSink;
import com.starrocks.planner.OlapTableSink;
import com.starrocks.planner.PlanFragment;
import com.starrocks.planner.Planner;
import com.starrocks.planner.ScanNode;
import com.starrocks.proto.PQueryStatistics;
import com.starrocks.proto.QueryStatisticsItemPB;
import com.starrocks.qe.QueryState.MysqlStateType;
import com.starrocks.rewrite.ExprRewriter;
import com.starrocks.rewrite.mvrewrite.MVSelectFailedException;
import com.starrocks.rpc.RpcException;
import com.starrocks.service.FrontendOptions;
import com.starrocks.sql.StatementPlanner;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.common.StarRocksPlannerException;
import com.starrocks.sql.common.ErrorType;
import com.starrocks.sql.common.MetaUtils;
import com.starrocks.sql.plan.ExecPlan;
import com.starrocks.statistic.AnalyzeJob;
import com.starrocks.statistic.Constants;
import com.starrocks.statistic.StatisticExecutor;
import com.starrocks.task.LoadEtlTask;
import com.starrocks.thrift.TDescriptorTable;
import com.starrocks.thrift.TExplainLevel;
import com.starrocks.thrift.TQueryOptions;
import com.starrocks.thrift.TQueryType;
import com.starrocks.thrift.TUniqueId;
import com.starrocks.transaction.TabletCommitInfo;
import com.starrocks.transaction.TransactionCommitFailedException;
import com.starrocks.transaction.TransactionState;
import com.starrocks.transaction.TransactionStatus;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

// Do one COM_QEURY process.
// first: Parse receive byte array to statement struct.
// second: Do handle function for statement.
public class StmtExecutor {
    private static final Logger LOG = LogManager.getLogger(StmtExecutor.class);

    private static final AtomicLong STMT_ID_GENERATOR = new AtomicLong(0);

    private final ConnectContext context;
    private final MysqlSerializer serializer;
    private final OriginStatement originStmt;
    private StatementBase parsedStmt;
    private Analyzer analyzer;
    private RuntimeProfile profile;
    private volatile Coordinator coord = null;
    private MasterOpExecutor masterOpExecutor = null;
    private RedirectStatus redirectStatus = null;
    private Planner planner;
    private final boolean isProxy;
    private ShowResultSet proxyResultSet = null;
    private PQueryStatistics statisticsForAuditLog;

    // this constructor is mainly for proxy
    public StmtExecutor(ConnectContext context, OriginStatement originStmt, boolean isProxy) {
        this.context = context;
        this.originStmt = originStmt;
        this.serializer = context.getSerializer();
        this.isProxy = isProxy;
    }

    // this constructor is only for test now.
    public StmtExecutor(ConnectContext context, String stmt) {
        this(context, new OriginStatement(stmt, 0), false);
    }

    // constructor for receiving parsed stmt from connect processor
    public StmtExecutor(ConnectContext ctx, StatementBase parsedStmt) {
        this.context = ctx;
        this.parsedStmt = parsedStmt;
        this.originStmt = parsedStmt.getOrigStmt();
        this.serializer = context.getSerializer();
        this.isProxy = false;
    }

    // At the end of query execution, we begin to add up profile
    public void initProfile(long beginTimeInNanoSecond) {
        profile = new RuntimeProfile("Query");
        RuntimeProfile summaryProfile = new RuntimeProfile("Summary");
        summaryProfile.addInfoString(ProfileManager.QUERY_ID, DebugUtil.printId(context.getExecutionId()));
        summaryProfile.addInfoString(ProfileManager.START_TIME, TimeUtils.longToTimeString(context.getStartTime()));

        long currentTimestamp = System.currentTimeMillis();
        long totalTimeMs = currentTimestamp - context.getStartTime();
        summaryProfile.addInfoString(ProfileManager.END_TIME, TimeUtils.longToTimeString(currentTimestamp));
        summaryProfile.addInfoString(ProfileManager.TOTAL_TIME, DebugUtil.getPrettyStringMs(totalTimeMs));

        summaryProfile.addInfoString(ProfileManager.QUERY_TYPE, "Query");
        summaryProfile.addInfoString(ProfileManager.QUERY_STATE, context.getState().toString());
        summaryProfile.addInfoString("StarRocks Version", Version.STARROCKS_VERSION);
        summaryProfile.addInfoString(ProfileManager.USER, context.getQualifiedUser());
        summaryProfile.addInfoString(ProfileManager.DEFAULT_DB, context.getDatabase());
        summaryProfile.addInfoString(ProfileManager.SQL_STATEMENT, originStmt.originStmt);
        profile.addChild(summaryProfile);
        if (coord != null) {
            coord.getQueryProfile().getCounterTotalTime().setValue(TimeUtils.getEstimatedTime(beginTimeInNanoSecond));
            coord.endProfile();
            profile.addChild(coord.getQueryProfile());
            coord = null;
        }
    }

    public Planner planner() {
        return planner;
    }

    public boolean isForwardToMaster() {
        if (Catalog.getCurrentCatalog().isMaster()) {
            return false;
        }

        // this is a query stmt, but this non-master FE can not read, forward it to master
        if ((parsedStmt instanceof QueryStmt) && !Catalog.getCurrentCatalog().isMaster()
                && !Catalog.getCurrentCatalog().canRead()) {
            return true;
        }

        if (redirectStatus == null) {
            return false;
        } else {
            return redirectStatus.isForwardToMaster();
        }
    }

    public ByteBuffer getOutputPacket() {
        if (masterOpExecutor == null) {
            return null;
        } else {
            return masterOpExecutor.getOutputPacket();
        }
    }

    public ShowResultSet getProxyResultSet() {
        return proxyResultSet;
    }

    public ShowResultSet getShowResultSet() {
        if (masterOpExecutor == null) {
            return null;
        } else {
            return masterOpExecutor.getProxyResultSet();
        }
    }

    public boolean isQueryStmt() {
        return parsedStmt != null && parsedStmt instanceof QueryStmt;
    }

    public StatementBase getParsedStmt() {
        return parsedStmt;
    }

    // Execute one statement.
    // Exception:
    //  IOException: talk with client failed.
    public void execute() throws Exception {
        long beginTimeInNanoSecond = TimeUtils.getStartTime();
        context.setStmtId(STMT_ID_GENERATOR.incrementAndGet());

        // set execution id.
        // Try to use query id as execution id when execute first time.
        UUID uuid = context.getQueryId();
        context.setExecutionId(UUIDUtil.toTUniqueId(uuid));
        SessionVariable sessionVariableBackup = context.getSessionVariable();
        try {
            // parsedStmt may already by set when constructing this StmtExecutor();
            resloveParseStmtForForword();

            // support select hint e.g. select /*+ SET_VAR(query_timeout=1) */ sleep(3);
            if (parsedStmt != null && parsedStmt instanceof SelectStmt) {
                SelectStmt selectStmt = (SelectStmt) parsedStmt;
                Map<String, String> optHints = selectStmt.getSelectList().getOptHints();
                if (optHints != null) {
                    SessionVariable sessionVariable = (SessionVariable) sessionVariableBackup.clone();
                    for (String key : optHints.keySet()) {
                        VariableMgr.setVar(sessionVariable, new SetVar(key, new StringLiteral(optHints.get(key))));
                    }
                    context.setSessionVariable(sessionVariable);
                }
            }

            // execPlan is the output of new planner
            ExecPlan execPlan = null;
            boolean execPlanBuildByNewPlanner = false;

            // Entrance to the new planner
            if (isStatisticsOrAnalyzer(parsedStmt, context)
                    || (context.getSessionVariable().isEnableNewPlanner()
                    && context.getSessionVariable().useVectorizedEngineEnable()
                    && supportedByNewPlanner(parsedStmt, context))) {
                try {
                    redirectStatus = parsedStmt.getRedirectStatus();
                    if (!isForwardToMaster()) {
                        context.getDumpInfo().reset();
                        context.getDumpInfo().setOriginStmt(parsedStmt.getOrigStmt().originStmt);
                        execPlan = new StatementPlanner().plan(parsedStmt, context);
                        execPlanBuildByNewPlanner = true;
                    }
                } catch (SemanticException e) {
                    dumpException(e);
                    throw new AnalysisException(e.getMessage());
                } catch (StarRocksPlannerException e) {
                    dumpException(e);
                    if (e.getType().equals(ErrorType.USER_ERROR)) {
                        throw e;
                    } else if (e.getType().equals(ErrorType.UNSUPPORTED) && e.getMessage().contains("UDF function")) {
                        LOG.warn("New planner not implement : " + originStmt.originStmt, e);
                        analyze(context.getSessionVariable().toThrift());
                    } else {
                        LOG.warn("New planner error: " + originStmt.originStmt, e);
                        throw e;
                    }
                }
            } else {
                // analyze this query
                analyze(context.getSessionVariable().toThrift());
            }

            if (context.isQueryDump()) {
                return;
            }
            if (isForwardToMaster()) {
                forwardToMaster();
                return;
            } else {
                LOG.debug("no need to transfer to Master. stmt: {}", context.getStmtId());
            }

            // Only add the last running stmt for multi statement,
            // because the audit log will only show the last stmt and
            // the ConnectProcessor only add the last finished stmt
            if (context.getIsLastStmt()) {
                addRunningQueryDetail();
            }

            if (parsedStmt instanceof QueryStmt) {
                context.getState().setIsQuery(true);

                // sql's blacklist is enabled throuth enable_sql_blacklist.
                if (Config.enable_sql_blacklist) {
                    QueryStmt queryStmt = (QueryStmt) parsedStmt;
                    String originSql = queryStmt.getOrigStmt().originStmt.trim().toLowerCase().replaceAll(" +", " ");

                    // If this sql is in blacklist, show message.
                    SqlBlackList.verifying(originSql);
                }

                int retryTime = Config.max_query_retry_time;
                for (int i = 0; i < retryTime; i++) {
                    try {
                        //reset query id for each retry
                        if (i > 0) {
                            uuid = UUID.randomUUID();
                            context.setExecutionId(
                                    new TUniqueId(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()));
                        }

                        if (execPlanBuildByNewPlanner) {
                            StringBuilder explainStringBuilder = new StringBuilder("WORK ON CBO OPTIMIZER\n");
                            // StarRocksManager depends on explainString to get sql plan
                            if (parsedStmt.isExplain() || context.getSessionVariable().isReportSucc()) {
                                TExplainLevel level = parsedStmt.isCosts() ? TExplainLevel.COSTS :
                                        parsedStmt.isVerbose() ? TExplainLevel.VERBOSE : TExplainLevel.NORMAL;
                                explainStringBuilder.append(execPlan.getExplainString(level));
                            }
                            handleQueryStmt(execPlan.getFragments(), execPlan.getScanNodes(),
                                    execPlan.getDescTbl().toThrift(),
                                    execPlan.getColNames(), execPlan.getOutputExprs(), explainStringBuilder.toString());
                        } else {
                            TExplainLevel level = parsedStmt.isVerbose() ? TExplainLevel.VERBOSE : TExplainLevel.NORMAL;
                            String explainString = planner.getExplainString(planner.getFragments(), level);
                            handleQueryStmt(planner.getFragments(), planner.getScanNodes(),
                                    analyzer.getDescTbl().toThrift(),
                                    parsedStmt.getColLabels(), parsedStmt.getResultExprs(), explainString);
                        }

                        if (context.getSessionVariable().isReportSucc()) {
                            writeProfile(beginTimeInNanoSecond);
                        }
                        break;
                    } catch (RpcException e) {
                        if (i == retryTime - 1) {
                            throw e;
                        }
                        if (!context.getMysqlChannel().isSend()) {
                            LOG.warn("retry {} times. stmt: {}", (i + 1), parsedStmt.getOrigStmt().originStmt);
                        } else {
                            throw e;
                        }
                    } finally {
                        QeProcessorImpl.INSTANCE.unregisterQuery(context.getExecutionId());
                    }
                }
            } else if (parsedStmt instanceof SetStmt) {
                handleSetStmt();
            } else if (parsedStmt instanceof EnterStmt) {
                handleEnterStmt();
            } else if (parsedStmt instanceof UseStmt) {
                handleUseStmt();
            } else if (parsedStmt instanceof CreateTableAsSelectStmt) {
                handleInsertStmt(uuid);
            } else if (parsedStmt instanceof InsertStmt) { // Must ahead of DdlStmt because InserStmt is its subclass
                try {
                    if (execPlanBuildByNewPlanner) {
                        handleInsertStmtWithNewPlanner(execPlan, (InsertStmt) parsedStmt);
                    } else {
                        handleInsertStmt(uuid);
                    }
                    if (context.getSessionVariable().isReportSucc()) {
                        writeProfile(beginTimeInNanoSecond);
                    }
                } catch (Throwable t) {
                    LOG.warn("handle insert stmt fail", t);
                    // the transaction of this insert may already begun, we will abort it at outer finally block.
                    throw t;
                } finally {
                    QeProcessorImpl.INSTANCE.unregisterQuery(context.getExecutionId());
                }
            } else if (parsedStmt instanceof DdlStmt) {
                handleDdlStmt();
            } else if (parsedStmt instanceof ShowStmt) {
                handleShow();
            } else if (parsedStmt instanceof KillStmt) {
                handleKill();
            } else if (parsedStmt instanceof ExportStmt) {
                handleExportStmt(context.getQueryId());
            } else if (parsedStmt instanceof UnsupportedStmt) {
                handleUnsupportedStmt();
            } else if (parsedStmt instanceof AnalyzeStmt) {
                handleAnalyzeStmt();
            } else if (parsedStmt instanceof AddSqlBlackListStmt) {
                handleAddSqlBlackListStmt();
            } else if (parsedStmt instanceof DelSqlBlackListStmt) {
                handleDelSqlBlackListStmt();
            } else {
                context.getState().setError("Do not support this query.");
            }
        } catch (IOException e) {
            LOG.warn("execute IOException ", e);
            // the exception happens when interact with client
            // this exception shows the connection is gone
            context.getState().setError(e.getMessage());
            throw e;
        } catch (UserException e) {
            // analysis exception only print message, not print the stack
            LOG.info("execute Exception. {}", e.getMessage());
            context.getState().setError(e.getMessage());
            context.getState().setErrType(QueryState.ErrType.ANALYSIS_ERR);
        } catch (Throwable e) {
            String sql = originStmt != null ? originStmt.originStmt : "";
            LOG.warn("execute Exception, sql " + sql, e);
            context.getState().setError(e.getMessage());
            if (parsedStmt instanceof KillStmt) {
                // ignore kill stmt execute err(not monitor it)
                context.getState().setErrType(QueryState.ErrType.ANALYSIS_ERR);
            }
        } finally {
            if (parsedStmt instanceof InsertStmt) {
                InsertStmt insertStmt = (InsertStmt) parsedStmt;
                // The transaction of a insert operation begin at analyze phase.
                // So we should abort the transaction at this finally block if it encounter exception.
                if (insertStmt.isTransactionBegin() && context.getState().getStateType() == MysqlStateType.ERR) {
                    try {
                        String errMsg = Strings.emptyToNull(context.getState().getErrorMessage());
                        if (insertStmt.getTargetTable() instanceof ExternalOlapTable) {
                            ExternalOlapTable externalTable = (ExternalOlapTable)(insertStmt.getTargetTable());
                            Catalog.getCurrentGlobalTransactionMgr().abortRemoteTransaction(
                            externalTable.getDbId(), insertStmt.getTransactionId(),
                            externalTable.getExternalInfo().getHost(),
                            externalTable.getExternalInfo().getPort(),
                            errMsg == null ? "unknown reason" : errMsg);
                        } else {
                            Catalog.getCurrentGlobalTransactionMgr().abortTransaction(
                                    insertStmt.getDbObj().getId(), insertStmt.getTransactionId(),
                                    (errMsg == null ? "unknown reason" : errMsg));
                        }
                    } catch (Exception abortTxnException) {
                        LOG.warn("errors when abort txn", abortTxnException);
                    }
                }
            }
            context.setSessionVariable(sessionVariableBackup);
        }
    }

    private void resloveParseStmtForForword() throws AnalysisException {
        if (parsedStmt == null) {
            // Parse statement with parser generated by CUP&FLEX
            SqlScanner input = new SqlScanner(new StringReader(originStmt.originStmt),
                    context.getSessionVariable().getSqlMode());
            SqlParser parser = new SqlParser(input);
            try {
                parsedStmt = SqlParserUtils.getStmt(parser, originStmt.idx);
                parsedStmt.setOrigStmt(originStmt);
            } catch (Error e) {
                LOG.info("error happened when parsing stmt {}, id: {}", originStmt, context.getStmtId(), e);
                throw new AnalysisException("sql parsing error, please check your sql");
            } catch (AnalysisException e) {
                String syntaxError = parser.getErrorMsg(originStmt.originStmt);
                LOG.info("analysis exception happened when parsing stmt {}, id: {}, error: {}",
                        originStmt, context.getStmtId(), syntaxError, e);
                if (syntaxError == null) {
                    throw e;
                } else {
                    throw new AnalysisException(syntaxError, e);
                }
            } catch (Exception e) {
                // TODO(lingbin): we catch 'Exception' to prevent unexpected error,
                // should be removed this try-catch clause future.
                LOG.info("unexpected exception happened when parsing stmt {}, id: {}, error: {}",
                        originStmt, context.getStmtId(), parser.getErrorMsg(originStmt.originStmt), e);
                throw new AnalysisException("Unexpected exception: " + e.getMessage());
            }
        }
    }

    private void dumpException(Exception e) {
        context.getDumpInfo().addException(ExceptionUtils.getStackTrace(e));
        if (context.getSessionVariable().getEnableQueryDump() && !context.isQueryDump()) {
            QueryDumpLog.getQueryDump().log(GsonUtils.GSON.toJson(context.getDumpInfo()));
        }
    }

    private void forwardToMaster() throws Exception {
        boolean isQuery = parsedStmt instanceof QueryStmt;
        masterOpExecutor = new MasterOpExecutor(originStmt, context, redirectStatus, isQuery);
        LOG.debug("need to transfer to Master. stmt: {}", context.getStmtId());
        masterOpExecutor.execute();
    }

    private void writeProfile(long beginTimeInNanoSecond) {
        initProfile(beginTimeInNanoSecond);
        profile.computeTimeInChildProfile();
        StringBuilder builder = new StringBuilder();
        profile.prettyPrint(builder, "");
        String profileContent = ProfileManager.getInstance().pushProfile(profile);
        if (context.getQueryDetail() != null) {
            context.getQueryDetail().setProfile(profileContent);
        }
    }

    // Lock all database before analyze
    private void lock(Map<String, Database> dbs) {
        if (dbs == null) {
            return;
        }
        for (Database db : dbs.values()) {
            db.readLock();
        }
    }

    // unLock all database after analyze
    private void unLock(Map<String, Database> dbs) {
        if (dbs == null) {
            return;
        }
        for (Database db : dbs.values()) {
            db.readUnlock();
        }
    }

    // Analyze one statement to structure in memory.
    public void analyze(TQueryOptions tQueryOptions) throws UserException {
        LOG.info("begin to analyze stmt: {}, forwarded stmt id: {}", context.getStmtId(), context.getForwardedStmtId());

        // parsedStmt may already by set when constructing this StmtExecutor();
        resloveParseStmtForForword();
        redirectStatus = parsedStmt.getRedirectStatus();

        // yiguolei: insertstmt's grammer analysis will write editlog, so that we check if the stmt should be forward to master here
        // if the stmt should be forward to master, then just return here and the master will do analysis again
        if (isForwardToMaster()) {
            return;
        }

        analyzer = new Analyzer(context.getCatalog(), context);
        // Convert show statement to select statement here
        if (parsedStmt instanceof ShowStmt) {
            SelectStmt selectStmt = ((ShowStmt) parsedStmt).toSelectStmt(analyzer);
            if (selectStmt != null) {
                parsedStmt = selectStmt;
            }
        }

        if (parsedStmt instanceof QueryStmt
                || parsedStmt instanceof InsertStmt
                || parsedStmt instanceof CreateTableAsSelectStmt) {
            Map<String, Database> dbs = Maps.newTreeMap();
            QueryStmt queryStmt;
            if (parsedStmt instanceof QueryStmt) {
                queryStmt = (QueryStmt) parsedStmt;
                queryStmt.getDbs(context, dbs);
            } else {
                InsertStmt insertStmt;
                if (parsedStmt instanceof InsertStmt) {
                    insertStmt = (InsertStmt) parsedStmt;
                } else {
                    insertStmt = ((CreateTableAsSelectStmt) parsedStmt).getInsertStmt();
                }
                insertStmt.getDbs(context, dbs);
            }

            lock(dbs);
            try {
                analyzeAndGenerateQueryPlan(tQueryOptions);
            } catch (MVSelectFailedException e) {
                /*
                 * If there is MVSelectFailedException after the first planner, there will be error mv rewritten in query.
                 * So, the query should be reanalyzed without mv rewritten and planner again.
                 * Attention: Only error rewritten tuple is forbidden to mv rewrite in the second time.
                 */
                resetAnalyzerAndStmt();
                analyzeAndGenerateQueryPlan(tQueryOptions);
            } catch (UserException e) {
                throw e;
            } catch (Exception e) {
                LOG.warn("Analyze failed because ", e);
                throw new AnalysisException("Unexpected exception: " + e.getMessage());
            } finally {
                unLock(dbs);
            }
        } else {
            try {
                parsedStmt.analyze(analyzer);
            } catch (AnalysisException e) {
                throw e;
            } catch (Exception e) {
                LOG.warn("Analyze failed because ", e);
                throw new AnalysisException("Unexpected exception: " + e.getMessage());
            }
        }
    }

    private void analyzeAndGenerateQueryPlan(TQueryOptions tQueryOptions) throws UserException {
        parsedStmt.analyze(analyzer);
        if (parsedStmt instanceof QueryStmt || parsedStmt instanceof InsertStmt) {
            boolean isExplain = parsedStmt.isExplain();
            boolean isVerbose = parsedStmt.isVerbose();
            boolean isCosts = parsedStmt.isCosts();
            // Apply expr and subquery rewrites.
            boolean reAnalyze = false;

            ExprRewriter rewriter = analyzer.getExprRewriter();
            rewriter.reset();
            parsedStmt.rewriteExprs(rewriter);
            reAnalyze = rewriter.changed();
            if (analyzer.containSubquery()) {
                parsedStmt = StmtRewriter.rewrite(analyzer, parsedStmt);
                reAnalyze = true;
            }
            if (reAnalyze) {
                // The rewrites should have no user-visible effect. Remember the original result
                // types and column labels to restore them after the rewritten stmt has been
                // reset() and re-analyzed.
                List<Type> origResultTypes = Lists.newArrayList();
                for (Expr e : parsedStmt.getResultExprs()) {
                    origResultTypes.add(e.getType());
                }
                List<String> origColLabels =
                        Lists.newArrayList(parsedStmt.getColLabels());

                // Re-analyze the stmt with a new analyzer.
                analyzer = new Analyzer(context.getCatalog(), context);

                // query re-analyze
                parsedStmt.reset();
                parsedStmt.analyze(analyzer);

                // Restore the original result types and column labels.
                parsedStmt.castResultExprs(origResultTypes);
                parsedStmt.setColLabels(origColLabels);
                if (LOG.isTraceEnabled()) {
                    LOG.trace("rewrittenStmt: " + parsedStmt.toSql());
                }
                if (isExplain) {
                    parsedStmt.setIsExplain(isExplain, isVerbose, isCosts);
                }
            }
        }

        // create plan
        planner = new Planner();
        if (parsedStmt instanceof QueryStmt || parsedStmt instanceof InsertStmt) {
            planner.plan(parsedStmt, analyzer, tQueryOptions);
        } else {
            planner.plan(((CreateTableAsSelectStmt) parsedStmt).getInsertStmt(),
                    analyzer, new TQueryOptions());
        }
        // TODO(zc):
        // Preconditions.checkState(!analyzer.hasUnassignedConjuncts());
    }

    private void resetAnalyzerAndStmt() {
        analyzer = new Analyzer(context.getCatalog(), context);

        parsedStmt.reset();
    }

    // Because this is called by other thread
    public void cancel() {
        Coordinator coordRef = coord;
        if (coordRef != null) {
            coordRef.cancel();
        }
    }

    // Handle kill statement.
    private void handleKill() throws DdlException {
        KillStmt killStmt = (KillStmt) parsedStmt;
        long id = killStmt.getConnectionId();
        ConnectContext killCtx = context.getConnectScheduler().getContext(id);
        if (killCtx == null) {
            ErrorReport.reportDdlException(ErrorCode.ERR_NO_SUCH_THREAD, id);
        }
        if (context == killCtx) {
            // Suicide
            context.setKilled();
        } else {
            // Check auth
            // Only user itself and user with admin priv can kill connection
            if (!killCtx.getQualifiedUser().equals(ConnectContext.get().getQualifiedUser())
                    && !Catalog.getCurrentCatalog().getAuth().checkGlobalPriv(ConnectContext.get(),
                    PrivPredicate.ADMIN)) {
                ErrorReport.reportDdlException(ErrorCode.ERR_KILL_DENIED_ERROR, id);
            }

            killCtx.kill(killStmt.isConnectionKill());
        }
        context.getState().setOk();
    }

    // Process set statement.
    private void handleSetStmt() {
        try {
            SetStmt setStmt = (SetStmt) parsedStmt;
            SetExecutor executor = new SetExecutor(context, setStmt);
            executor.execute();
        } catch (DdlException e) {
            // Return error message to client.
            context.getState().setError(e.getMessage());
            return;
        }
        context.getState().setOk();
    }

    // Process a select statement.
    private void handleQueryStmt(List<PlanFragment> fragments, List<ScanNode> scanNodes, TDescriptorTable descTable,
                                 List<String> colNames, List<Expr> outputExprs, String explainString) throws Exception {
        // Every time set no send flag and clean all data in buffer
        context.getMysqlChannel().reset();
        QueryStmt queryStmt = (QueryStmt) parsedStmt;

        if (queryStmt.isExplain()) {
            handleExplainStmt(explainString);
            return;
        }
        if (context.getQueryDetail() != null) {
            context.getQueryDetail().setExplain(explainString);
        }

        coord = new Coordinator(context, fragments, scanNodes, descTable);

        QeProcessorImpl.INSTANCE.registerQuery(context.getExecutionId(),
                new QeProcessorImpl.QueryInfo(context, originStmt.originStmt, coord));

        coord.exec();

        // send result
        // 1. If this is a query with OUTFILE clause, eg: select * from tbl1 into outfile xxx,
        //    We will not send real query result to client. Instead, we only send OK to client with
        //    number of rows selected. For example:
        //          mysql> select * from tbl1 into outfile xxx;
        //          Query OK, 10 rows affected (0.01 sec)
        //
        // 2. If this is a query, send the result expr fields first, and send result data back to client.
        RowBatch batch;
        MysqlChannel channel = context.getMysqlChannel();
        boolean isOutfileQuery = queryStmt.hasOutFileClause();
        boolean isSendFields = false;
        while (true) {
            batch = coord.getNext();
            // for outfile query, there will be only one empty batch send back with eos flag
            if (batch.getBatch() != null && !isOutfileQuery) {
                // For some language driver, getting error packet after fields packet will be recognized as a success result
                // so We need to send fields after first batch arrived
                if (!isSendFields) {
                    sendFields(colNames, outputExprs);
                    isSendFields = true;
                }
                for (ByteBuffer row : batch.getBatch().getRows()) {
                    channel.sendOnePacket(row);
                }
                context.updateReturnRows(batch.getBatch().getRows().size());
            }
            if (batch.isEos()) {
                break;
            }
        }
        if (!isSendFields && !isOutfileQuery) {
            sendFields(colNames, outputExprs);
        }

        statisticsForAuditLog = batch.getQueryStatistics();
        if (!isOutfileQuery) {
            context.getState().setEof();
        } else {
            context.getState().setOk(statisticsForAuditLog.returned_rows, 0, "");
        }
        if (null == statisticsForAuditLog || null == statisticsForAuditLog.stats_items ||
                statisticsForAuditLog.stats_items.isEmpty()) {
            return;
        }
        // collect table-level metrics
        Set<Long> tableIds = Sets.newHashSet();
        for (QueryStatisticsItemPB item : statisticsForAuditLog.stats_items) {
            TableMetricsEntity entity = TableMetricsRegistry.getInstance().getMetricsEntity(item.table_id);
            entity.COUNTER_SCAN_ROWS_TOTAL.increase(item.scan_rows);
            entity.COUNTER_SCAN_BYTES_TOTAL.increase(item.scan_bytes);
            tableIds.add(item.table_id);
        }
        for (Long tableId : tableIds) {
            TableMetricsEntity entity = TableMetricsRegistry.getInstance().getMetricsEntity(tableId);
            entity.COUNTER_SCAN_FINISHED_TOTAL.increase(1l);
        }
    }

    // Process a select statement.
    private void handleInsertStmt(UUID queryId) throws Exception {
        // Every time set no send flag and clean all data in buffer
        context.getMysqlChannel().reset();
        // create plan
        InsertStmt insertStmt = null;
        if (parsedStmt instanceof CreateTableAsSelectStmt) {
            // Create table here
            ((CreateTableAsSelectStmt) parsedStmt).createTable(analyzer);
            insertStmt = ((CreateTableAsSelectStmt) parsedStmt).getInsertStmt();
        } else {
            insertStmt = (InsertStmt) parsedStmt;
        }

        // set broker table file name prefix
        if (insertStmt.getTargetTable() instanceof BrokerTable) {
            DataSink dataSink = insertStmt.getDataSink();
            Preconditions.checkArgument(dataSink instanceof ExportSink);
            // File name: <table-name>_<query-id>_0_<instance-id>_<file-number>.csv.<timestamp>
            ((ExportSink) dataSink)
                    .setFileNamePrefix(insertStmt.getTargetTable().getName() + "_" + queryId.toString() + "_0_");
        }

        if (insertStmt.getQueryStmt().hasOutFileClause()) {
            throw new DdlException("Not support OUTFILE clause in INSERT statement");
        }

        String explainString = planner.getExplainString(planner.getFragments(), TExplainLevel.NORMAL);
        if (insertStmt.getQueryStmt().isExplain()) {
            handleExplainStmt(explainString);
            return;
        }
        if (context.getQueryDetail() != null) {
            context.getQueryDetail().setExplain(explainString);
        }

        long createTime = System.currentTimeMillis();
        Throwable throwable = null;

        String label = insertStmt.getLabel();

        long loadedRows = 0;
        int filteredRows = 0;
        TransactionStatus txnStatus = TransactionStatus.ABORTED;
        try {
            coord = new Coordinator(context, analyzer, planner);
            coord.setQueryType(TQueryType.LOAD);

            QeProcessorImpl.INSTANCE.registerQuery(context.getExecutionId(), coord);

            coord.exec();

            coord.join(context.getSessionVariable().getQueryTimeoutS());
            if (!coord.isDone()) {
                coord.cancel();
                ErrorReport.reportDdlException(ErrorCode.ERR_EXECUTE_TIMEOUT);
            }

            if (!coord.getExecStatus().ok()) {
                String errMsg = coord.getExecStatus().getErrorMsg();
                LOG.warn("insert failed: {}", errMsg);
                ErrorReport.reportDdlException(errMsg, ErrorCode.ERR_FAILED_WHEN_INSERT);
            }

            LOG.debug("delta files is {}", coord.getDeltaUrls());

            if (coord.getLoadCounters().get(LoadEtlTask.DPP_NORMAL_ALL) != null) {
                loadedRows = Long.parseLong(coord.getLoadCounters().get(LoadEtlTask.DPP_NORMAL_ALL));
            }
            if (coord.getLoadCounters().get(LoadEtlTask.DPP_ABNORMAL_ALL) != null) {
                filteredRows = Integer.parseInt(coord.getLoadCounters().get(LoadEtlTask.DPP_ABNORMAL_ALL));
            }

            // if in strict mode, insert will fail if there are filtered rows
            if (context.getSessionVariable().getEnableInsertStrict()) {
                if (filteredRows > 0) {
                    context.getState().setError("Insert has filtered data in strict mode, tracking_url="
                            + coord.getTrackingUrl());
                    return;
                }
            }

            TableType tableType = insertStmt.getTargetTable().getType();
            if ((tableType != TableType.OLAP) && (tableType != TableType.OLAP_EXTERNAL)) {
                // no need to add load job.
                // MySQL table is already being inserted.
                context.getState().setOk(loadedRows, filteredRows, null);
                return;
            }

            if (loadedRows == 0 && filteredRows == 0) {
                // if no data, just abort txn and return ok
                if (insertStmt.getTargetTable() instanceof ExternalOlapTable) {
                    ExternalOlapTable externalTable = (ExternalOlapTable)(insertStmt.getTargetTable());
                    Catalog.getCurrentGlobalTransactionMgr().abortRemoteTransaction(
                    externalTable.getDbId(), insertStmt.getTransactionId(),
                    externalTable.getExternalInfo().getHost(),
                    externalTable.getExternalInfo().getPort(),
                    TransactionCommitFailedException.NO_DATA_TO_LOAD_MSG);
                } else {
                    Catalog.getCurrentGlobalTransactionMgr().abortTransaction(insertStmt.getDbObj().getId(),
                            insertStmt.getTransactionId(), TransactionCommitFailedException.NO_DATA_TO_LOAD_MSG);
                }
                context.getState().setOk();
                return;
            }

            if (insertStmt.getTargetTable() instanceof ExternalOlapTable) {
                ExternalOlapTable externalTable = (ExternalOlapTable)(insertStmt.getTargetTable());
                if (Catalog.getCurrentGlobalTransactionMgr().commitRemoteTransaction(
                    externalTable.getDbId(), insertStmt.getTransactionId(),
                    externalTable.getExternalInfo().getHost(),
                    externalTable.getExternalInfo().getPort(),
                    coord.getCommitInfos())) {
                    txnStatus = TransactionStatus.VISIBLE;
                    MetricRepo.COUNTER_LOAD_FINISHED.increase(1L);
                } else {
                    txnStatus = TransactionStatus.COMMITTED;
                }
                // TODO: wait remote txn finished
            } else {
                if (Catalog.getCurrentGlobalTransactionMgr().commitAndPublishTransaction(
                        insertStmt.getDbObj(), insertStmt.getTransactionId(),
                        TabletCommitInfo.fromThrift(coord.getCommitInfos()),
                        context.getSessionVariable().getTransactionVisibleWaitTimeout() * 1000)) {
                    txnStatus = TransactionStatus.VISIBLE;
                    MetricRepo.COUNTER_LOAD_FINISHED.increase(1L);
                    // collect table-level metrics
                    if (null != insertStmt.getTargetTable()) {
                        TableMetricsEntity entity =
                                TableMetricsRegistry.getInstance().getMetricsEntity(insertStmt.getTargetTable().getId());
                        entity.COUNTER_INSERT_LOAD_FINISHED_TOTAL.increase(1L);
                        entity.COUNTER_INSERT_LOAD_ROWS_TOTAL.increase(loadedRows);
                        entity.COUNTER_INSERT_LOAD_BYTES_TOTAL
                                .increase(Long.valueOf(coord.getLoadCounters().get(LoadJob.LOADED_BYTES)));
                    }
                } else {
                    txnStatus = TransactionStatus.COMMITTED;
                }
            }
        } catch (Throwable t) {
            // if any throwable being thrown during insert operation, first we should abort this txn
            LOG.warn("handle insert stmt fail: {}", label, t);
            try {
                if (insertStmt.getTargetTable() instanceof ExternalOlapTable) {
                    ExternalOlapTable externalTable = (ExternalOlapTable)(insertStmt.getTargetTable());
                    Catalog.getCurrentGlobalTransactionMgr().abortRemoteTransaction(
                    externalTable.getDbId(), insertStmt.getTransactionId(),
                    externalTable.getExternalInfo().getHost(),
                    externalTable.getExternalInfo().getPort(),
                    t.getMessage() == null ? "unknown reason" : t.getMessage());
                } else {
                    Catalog.getCurrentGlobalTransactionMgr().abortTransaction(
                            insertStmt.getDbObj().getId(), insertStmt.getTransactionId(),
                            t.getMessage() == null ? "unknown reason" : t.getMessage());
                }
            } catch (Exception abortTxnException) {
                // just print a log if abort txn failed. This failure do not need to pass to user.
                // user only concern abort how txn failed.
                LOG.warn("errors when abort txn", abortTxnException);
            }

            if (!Config.using_old_load_usage_pattern) {
                // if not using old load usage pattern, error will be returned directly to user
                StringBuilder sb = new StringBuilder(t.getMessage());
                if (!Strings.isNullOrEmpty(coord.getTrackingUrl())) {
                    sb.append(". url: ").append(coord.getTrackingUrl());
                }
                context.getState().setError(sb.toString());
                return;
            }

            /*
             * If config 'using_old_load_usage_pattern' is true.
             * StarRocks will return a label to user, and user can use this label to check load job's status,
             * which exactly like the old insert stmt usage pattern.
             */
            throwable = t;
        }

        // Go here, which means:
        // 1. transaction is finished successfully (COMMITTED or VISIBLE), or
        // 2. transaction failed but Config.using_old_load_usage_pattern is true.
        // we will record the load job info for these 2 cases

        String errMsg = "";
        try {
            context.getCatalog().getLoadManager().recordFinishedLoadJob(
                    label,
                    insertStmt.getDb(),
                    insertStmt.getTargetTable().getId(),
                    EtlJobType.INSERT,
                    createTime,
                    throwable == null ? "" : throwable.getMessage(),
                    coord.getTrackingUrl());
        } catch (MetaNotFoundException e) {
            LOG.warn("Record info of insert load with error {}", e.getMessage(), e);
            errMsg = "Record info of insert load with error " + e.getMessage();
        }

        // {'label':'my_label1', 'status':'visible', 'txnId':'123'}
        // {'label':'my_label1', 'status':'visible', 'txnId':'123' 'err':'error messages'}
        StringBuilder sb = new StringBuilder();
        sb.append("{'label':'").append(label).append("', 'status':'").append(txnStatus.name());
        sb.append("', 'txnId':'").append(insertStmt.getTransactionId()).append("'");
        if (!Strings.isNullOrEmpty(errMsg)) {
            sb.append(", 'err':'").append(errMsg).append("'");
        }
        sb.append("}");

        context.getState().setOk(loadedRows, filteredRows, sb.toString());
    }

    private void handleAnalyzeStmt() throws Exception {
        AnalyzeStmt analyzeStmt = (AnalyzeStmt) parsedStmt;
        StatisticExecutor statisticExecutor = new StatisticExecutor();
        Database db = MetaUtils.getStarRocks(context, analyzeStmt.getTableName());
        Table table = MetaUtils.getStarRocksTable(context, analyzeStmt.getTableName());

        AnalyzeJob job = new AnalyzeJob();
        job.setDbId(db.getId());
        job.setTableId(table.getId());
        job.setColumns(analyzeStmt.getColumnNames());
        job.setType(analyzeStmt.isSample() ? Constants.AnalyzeType.SAMPLE : Constants.AnalyzeType.FULL);
        job.setScheduleType(Constants.ScheduleType.ONCE);
        job.setWorkTime(LocalDateTime.now());
        job.setStatus(Constants.ScheduleStatus.FINISH);
        job.setProperties(analyzeStmt.getProperties());

        try {
            statisticExecutor.collectStatisticSync(db.getId(), table.getId(), analyzeStmt.getColumnNames(),
                    analyzeStmt.isSample(), job.getSampleCollectRows());
            Catalog.getCurrentStatisticStorage().expireColumnStatistics(table, job.getColumns());
        } catch (Exception e) {
            job.setReason(e.getMessage());
            throw e;
        }

        Catalog.getCurrentCatalog().getAnalyzeManager().addAnalyzeJob(job);
    }

    private void handleAddSqlBlackListStmt() {
        AddSqlBlackListStmt addSqlBlackListStmt = (AddSqlBlackListStmt) parsedStmt;
        SqlBlackList.getInstance().put(addSqlBlackListStmt.getSqlPattern());
    }

    private void handleDelSqlBlackListStmt() {
        DelSqlBlackListStmt delSqlBlackListStmt = (DelSqlBlackListStmt) parsedStmt;
        List<Long> indexs = delSqlBlackListStmt.getIndexs();
        if (indexs != null) {
            for (long id : indexs) {
                SqlBlackList.getInstance().delete(id);
            }
        }
    }

    private void handleUnsupportedStmt() {
        context.getMysqlChannel().reset();
        // do nothing
        context.getState().setOk();
    }

    // Process use statement.
    private void handleUseStmt() throws AnalysisException {
        UseStmt useStmt = (UseStmt) parsedStmt;
        try {
            if (Strings.isNullOrEmpty(useStmt.getClusterName())) {
                ErrorReport.reportAnalysisException(ErrorCode.ERR_CLUSTER_NO_SELECT_CLUSTER);
            }
            context.getCatalog().changeDb(context, useStmt.getDatabase());
        } catch (DdlException e) {
            context.getState().setError(e.getMessage());
            return;
        }
        context.getState().setOk();
    }

    private void sendMetaData(ShowResultSetMetaData metaData) throws IOException {
        // sends how many columns
        serializer.reset();
        serializer.writeVInt(metaData.getColumnCount());
        context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
        // send field one by one
        for (Column col : metaData.getColumns()) {
            serializer.reset();
            // TODO(zhaochun): only support varchar type
            serializer.writeField(col.getName(), col.getType());
            context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
        }
        // send EOF
        serializer.reset();
        MysqlEofPacket eofPacket = new MysqlEofPacket(context.getState());
        eofPacket.writeTo(serializer);
        context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
    }

    private void sendFields(List<String> colNames, List<Expr> exprs) throws IOException {
        // sends how many columns
        serializer.reset();
        serializer.writeVInt(colNames.size());
        context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
        // send field one by one
        for (int i = 0; i < colNames.size(); ++i) {
            serializer.reset();
            serializer.writeField(colNames.get(i), exprs.get(i).getOriginType());
            context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
        }
        // send EOF
        serializer.reset();
        MysqlEofPacket eofPacket = new MysqlEofPacket(context.getState());
        eofPacket.writeTo(serializer);
        context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
    }

    public void sendShowResult(ShowResultSet resultSet) throws IOException {
        context.updateReturnRows(resultSet.getResultRows().size());
        // Send meta data.
        sendMetaData(resultSet.getMetaData());

        // Send result set.
        for (List<String> row : resultSet.getResultRows()) {
            serializer.reset();
            for (String item : row) {
                if (item == null || item.equals(FeConstants.null_string)) {
                    serializer.writeNull();
                } else {
                    serializer.writeLenEncodedString(item);
                }
            }
            context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
        }

        context.getState().setEof();
    }

    // Process show statement
    private void handleShow() throws IOException, AnalysisException, DdlException {
        ShowExecutor executor = new ShowExecutor(context, (ShowStmt) parsedStmt);
        ShowResultSet resultSet = executor.execute();
        if (resultSet == null) {
            // state changed in execute
            return;
        }
        if (isProxy) {
            proxyResultSet = resultSet;
            context.getState().setEof();
            return;
        }

        sendShowResult(resultSet);
    }

    private void handleExplainStmt(String result) throws IOException {
        ShowResultSetMetaData metaData =
                ShowResultSetMetaData.builder()
                        .addColumn(new Column("Explain String", ScalarType.createVarchar(20)))
                        .build();
        sendMetaData(metaData);

        // Send result set.
        for (String item : result.split("\n")) {
            serializer.reset();
            serializer.writeLenEncodedString(item);
            context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
        }
        context.getState().setEof();
    }

    private void handleDdlStmt() {
        try {
            DdlExecutor.execute(context.getCatalog(), (DdlStmt) parsedStmt);
            context.getState().setOk();
        } catch (QueryStateException e) {
            if (e.getQueryState().getStateType() != MysqlStateType.OK) {
                LOG.warn("DDL statement(" + originStmt.originStmt + ") process failed.", e);
            }
            context.setState(e.getQueryState());
        } catch (UserException e) {
            LOG.warn("DDL statement(" + originStmt.originStmt + ") process failed.", e);
            // Return message to info client what happened.
            context.getState().setError(e.getMessage());
        } catch (Exception e) {
            // Maybe our bug
            LOG.warn("DDL statement(" + originStmt.originStmt + ") process failed.", e);
            context.getState().setError("Unexpected exception: " + e.getMessage());
        }
    }

    // process enter cluster
    private void handleEnterStmt() {
        final EnterStmt enterStmt = (EnterStmt) parsedStmt;
        try {
            context.getCatalog().changeCluster(context, enterStmt.getClusterName());
            context.setDatabase("");
        } catch (DdlException e) {
            context.getState().setError(e.getMessage());
            return;
        }
        context.getState().setOk();
    }

    private void handleExportStmt(UUID queryId) throws Exception {
        ExportStmt exportStmt = (ExportStmt) parsedStmt;
        context.getCatalog().getExportMgr().addExportJob(queryId, exportStmt);
    }

    private void addRunningQueryDetail() {
        String sql;
        if (parsedStmt.needAuditEncryption()) {
            sql = parsedStmt.toSql();
        } else {
            sql = originStmt.originStmt;
        }
        boolean isQuery = false;
        if (parsedStmt instanceof QueryStmt) {
            isQuery = true;
        }
        QueryDetail queryDetail = new QueryDetail(
                DebugUtil.printId(context.getQueryId()),
                isQuery,
                context.connectionId,
                context.getMysqlChannel() != null ?
                        context.getMysqlChannel().getRemoteIp() : "System",
                context.getStartTime(), -1, -1,
                QueryDetail.QueryMemState.RUNNING,
                context.getDatabase(),
                sql,
                context.getQualifiedUser());
        context.setQueryDetail(queryDetail);
        //copy queryDetail, cause some properties can be changed in future
        QueryDetailQueue.addAndRemoveTimeoutQueryDetail(queryDetail.copy());
    }

    public PQueryStatistics getQueryStatisticsForAuditLog() {
        if (statisticsForAuditLog == null) {
            statisticsForAuditLog = new PQueryStatistics();
        }
        if (statisticsForAuditLog.scan_bytes == null) {
            statisticsForAuditLog.scan_bytes = 0L;
        }
        if (statisticsForAuditLog.scan_rows == null) {
            statisticsForAuditLog.scan_rows = 0L;
        }
        return statisticsForAuditLog;
    }

    /**
     * Below function is added by new analyzer
     */
    private boolean isStatisticsOrAnalyzer(StatementBase statement, ConnectContext context) {
        return (statement instanceof InsertStmt && context.getDatabase().equalsIgnoreCase(Constants.StatisticsDBName))
                || statement instanceof AnalyzeStmt
                || statement instanceof CreateAnalyzeJobStmt;
    }

    private boolean supportedByNewPlanner(StatementBase statement, ConnectContext context) {
        return statement instanceof QueryStmt || statement instanceof InsertStmt;
    }

    public void handleInsertStmtWithNewPlanner(ExecPlan execPlan, InsertStmt stmt) throws Exception {
        if (stmt.getQueryStmt().isExplain()) {
            handleExplainStmt(execPlan.getExplainString(TExplainLevel.NORMAL));
            return;
        }
        if (context.getQueryDetail() != null) {
            context.getQueryDetail().setExplain(execPlan.getExplainString(TExplainLevel.NORMAL));
        }

        MetaUtils.normalizationTableName(context, stmt.getTableName());
        Database database = MetaUtils.getStarRocks(context, stmt.getTableName());
        Table targetTable = MetaUtils.getStarRocksTable(context, stmt.getTableName());

        String label = Strings.isNullOrEmpty(stmt.getLabel()) ?
                "insert_" + DebugUtil.printId(context.getExecutionId()) : stmt.getLabel();
        TransactionState.LoadJobSourceType sourceType = TransactionState.LoadJobSourceType.INSERT_STREAMING;
        MetricRepo.COUNTER_LOAD_ADD.increase(1L);

        long transactionId = -1;
        if (targetTable instanceof ExternalOlapTable) {
            ExternalOlapTable externalTable = (ExternalOlapTable)targetTable;
            transactionId = Catalog.getCurrentGlobalTransactionMgr().beginRemoteTransaction(database.getFullName(),
                    Lists.newArrayList(targetTable.getName()), label,
                    externalTable.getExternalInfo().getHost(),
                    externalTable.getExternalInfo().getPort(),
                    new TransactionState.TxnCoordinator(TransactionState.TxnSourceType.FE, FrontendOptions.getLocalHostAddress()),
                    sourceType,
                    ConnectContext.get().getSessionVariable().getQueryTimeoutS());
        } else {
            transactionId = Catalog.getCurrentGlobalTransactionMgr().beginTransaction(
                    database.getId(),
                    Lists.newArrayList(targetTable.getId()),
                    label,
                    new TransactionState.TxnCoordinator(TransactionState.TxnSourceType.FE,
                            FrontendOptions.getLocalHostAddress()),
                    sourceType,
                    ConnectContext.get().getSessionVariable().getQueryTimeoutS());

            // add table indexes to transaction state
            TransactionState txnState =
                    Catalog.getCurrentGlobalTransactionMgr().getTransactionState(database.getId(), transactionId);
            if (txnState == null) {
                throw new DdlException("txn does not exist: " + transactionId);
            }
            txnState.addTableIndexes((OlapTable) targetTable);
        }

        // Every time set no send flag and clean all data in buffer
        if (context.getMysqlChannel() != null) {
            context.getMysqlChannel().reset();
        }
        long createTime = System.currentTimeMillis();

        long loadedRows = 0;
        int filteredRows = 0;
        TransactionStatus txnStatus = TransactionStatus.ABORTED;
        try {
            OlapTableSink dataSink = (OlapTableSink) execPlan.getFragments().get(0).getSink();
            dataSink.init(context.getExecutionId(), transactionId, database.getId(),
                    ConnectContext.get().getSessionVariable().getQueryTimeoutS());
            dataSink.complete();

            coord = new Coordinator(context, execPlan.getFragments(), execPlan.getScanNodes(),
                    execPlan.getDescTbl().toThrift());
            coord.setQueryType(TQueryType.LOAD);
            QeProcessorImpl.INSTANCE.registerQuery(context.getExecutionId(), coord);
            coord.exec();

            coord.join(context.getSessionVariable().getQueryTimeoutS());
            if (!coord.isDone()) {
                coord.cancel();
                ErrorReport.reportDdlException(ErrorCode.ERR_EXECUTE_TIMEOUT);
            }

            if (!coord.getExecStatus().ok()) {
                String errMsg = coord.getExecStatus().getErrorMsg();
                LOG.warn("insert failed: {}", errMsg);
                ErrorReport.reportDdlException(errMsg, ErrorCode.ERR_FAILED_WHEN_INSERT);
            }

            LOG.debug("delta files is {}", coord.getDeltaUrls());

            if (coord.getLoadCounters().get(LoadEtlTask.DPP_NORMAL_ALL) != null) {
                loadedRows = Long.parseLong(coord.getLoadCounters().get(LoadEtlTask.DPP_NORMAL_ALL));
            }
            if (coord.getLoadCounters().get(LoadEtlTask.DPP_ABNORMAL_ALL) != null) {
                filteredRows = Integer.parseInt(coord.getLoadCounters().get(LoadEtlTask.DPP_ABNORMAL_ALL));
            }

            // if in strict mode, insert will fail if there are filtered rows
            if (context.getSessionVariable().getEnableInsertStrict()) {
                if (filteredRows > 0) {
                    context.getState().setError("Insert has filtered data in strict mode, tracking_url="
                            + coord.getTrackingUrl());
                    return;
                }
            }

            if (loadedRows == 0 && filteredRows == 0) {
                if (stmt.getTargetTable() instanceof ExternalOlapTable) {
                    ExternalOlapTable externalTable = (ExternalOlapTable)(stmt.getTargetTable());
                    Catalog.getCurrentGlobalTransactionMgr().abortRemoteTransaction(
                        externalTable.getDbId(), transactionId,
                        externalTable.getExternalInfo().getHost(),
                        externalTable.getExternalInfo().getPort(),
                        TransactionCommitFailedException.NO_DATA_TO_LOAD_MSG);
                } else {
                    Catalog.getCurrentGlobalTransactionMgr().abortTransaction(
                            database.getId(),
                            transactionId,
                            TransactionCommitFailedException.NO_DATA_TO_LOAD_MSG
                    );
                }
                context.getState().setOk();
                return;
            }

            if (targetTable instanceof ExternalOlapTable) {
                ExternalOlapTable externalTable = (ExternalOlapTable)targetTable;
                if (Catalog.getCurrentGlobalTransactionMgr().commitRemoteTransaction(
                    externalTable.getDbId(), transactionId,
                    externalTable.getExternalInfo().getHost(),
                    externalTable.getExternalInfo().getPort(),
                    coord.getCommitInfos())) {
                    txnStatus = TransactionStatus.VISIBLE;
                    MetricRepo.COUNTER_LOAD_FINISHED.increase(1L);
                }
                // TODO: wait remote txn finished
            } else {
                if (Catalog.getCurrentGlobalTransactionMgr().commitAndPublishTransaction(
                        database,
                        transactionId,
                        TabletCommitInfo.fromThrift(coord.getCommitInfos()),
                        context.getSessionVariable().getTransactionVisibleWaitTimeout() * 1000)) {
                    txnStatus = TransactionStatus.VISIBLE;
                    MetricRepo.COUNTER_LOAD_FINISHED.increase(1L);
                    // collect table-level metrics
                    if (null != stmt.getTargetTable()) {
                        TableMetricsEntity entity =
                                TableMetricsRegistry.getInstance().getMetricsEntity(stmt.getTargetTable().getId());
                        entity.COUNTER_INSERT_LOAD_FINISHED_TOTAL.increase(1L);
                        entity.COUNTER_INSERT_LOAD_ROWS_TOTAL.increase(loadedRows);
                        entity.COUNTER_INSERT_LOAD_BYTES_TOTAL
                                .increase(Long.valueOf(coord.getLoadCounters().get(LoadJob.LOADED_BYTES)));
                    }
                } else {
                    txnStatus = TransactionStatus.COMMITTED;
                }
            }
        } catch (Throwable t) {
            // if any throwable being thrown during insert operation, first we should abort this txn
            LOG.warn("handle insert stmt fail: {}", label, t);
            try {
                if (stmt.getTargetTable() instanceof ExternalOlapTable) {
                    ExternalOlapTable externalTable = (ExternalOlapTable)(stmt.getTargetTable());
                    Catalog.getCurrentGlobalTransactionMgr().abortRemoteTransaction(
                        externalTable.getDbId(), stmt.getTransactionId(),
                        externalTable.getExternalInfo().getHost(),
                        externalTable.getExternalInfo().getPort(),
                        t.getMessage() == null ? "Unknown reason" : t.getMessage());
                } else {
                    Catalog.getCurrentGlobalTransactionMgr().abortTransaction(
                            database.getId(), transactionId,
                            t.getMessage() == null ? "Unknown reason" : t.getMessage());
                }
            } catch (Exception abortTxnException) {
                // just print a log if abort txn failed. This failure do not need to pass to user.
                // user only concern abort how txn failed.
                LOG.warn("errors when abort txn", abortTxnException);
            }

            // if not using old load usage pattern, error will be returned directly to user
            StringBuilder sb = new StringBuilder(t.getMessage());
            if (!Strings.isNullOrEmpty(coord.getTrackingUrl())) {
                sb.append(". url: ").append(coord.getTrackingUrl());
            }
            context.getState().setError(sb.toString());
            return;
        }

        String errMsg = "";
        try {
            context.getCatalog().getLoadManager().recordFinishedLoadJob(
                    label,
                    database.getFullName(),
                    targetTable.getId(),
                    EtlJobType.INSERT,
                    createTime,
                    "",
                    coord.getTrackingUrl());
        } catch (MetaNotFoundException e) {
            LOG.warn("Record info of insert load with error {}", e.getMessage(), e);
            errMsg = "Record info of insert load with error " + e.getMessage();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{'label':'").append(label).append("', 'status':'").append(txnStatus.name());
        sb.append("', 'txnId':'").append(transactionId).append("'");
        if (!Strings.isNullOrEmpty(errMsg)) {
            sb.append(", 'err':'").append(errMsg).append("'");
        }
        sb.append("}");

        context.getState().setOk(loadedRows, filteredRows, sb.toString());
    }

    public String getOriginStmtInString() {
        if (originStmt == null) {
            return "";
        }
        return originStmt.originStmt;
    }
}
