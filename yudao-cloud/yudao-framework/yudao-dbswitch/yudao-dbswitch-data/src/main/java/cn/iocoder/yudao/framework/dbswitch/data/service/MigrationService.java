package cn.iocoder.yudao.framework.dbswitch.data.service;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import cn.iocoder.yudao.framework.dbswitch.common.entity.RecordTransformer;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.common.type.DBTableType;
import cn.iocoder.yudao.framework.dbswitch.common.type.SourceType;
import cn.iocoder.yudao.framework.dbswitch.common.util.DbswitchStrUtils;
import cn.iocoder.yudao.framework.dbswitch.common.util.PatterNameUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.PerfStat;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.handler.MigrationHandler;
import cn.iocoder.yudao.framework.dbswitch.data.handler.TableResultHandler;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverPoolFactory;
import org.springframework.util.StopWatch;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.SQLException;

import javax.sql.DataSource;


/**
 * 数据迁移主逻辑类
 * <p>
 * 职责：整个迁移任务的调度入口。负责创建源端/目标端连接池、按配置筛选出需要迁移的表，
 * 为每张表创建 {@link MigrationHandler} 并提交到固定大小的线程池并发执行，
 * 汇总所有表的迁移结果（成功/失败数、行数、字节数）为 {@link DbSwitchResult}。
 * <p>
 * 协作关系：表清单来自 MetaDataByDataSourceServiceImpl 的元数据查询；
 * 单表迁移逻辑全部在 MigrationHandler 中；每张表完成后回调 {@link TableResultHandler}
 * 让上层业务保存表级结果；支持 interrupt() 中断所有正在执行的表任务
 *
 * @author jrl
 */
@Slf4j
public class MigrationService {

    /**
     * 性能统计记录表
     */
    private final List<PerfStat> perfStats = new ArrayList<>();

    /**
     * 任务列表（interrupt() 可能与主线程注册 Handler 并发执行，使用并发安全集合）
     */
    private final List<MigrationHandler> migrationHandlers = new CopyOnWriteArrayList<>();

    /**
     * 当前活跃的表迁移线程池（供 interrupt() 立即停止在执行/排队中的任务）
     */
    private final List<ExecutorService> activeExecutors = new CopyOnWriteArrayList<>();

    private final List<PoolResources> ownedPools = new CopyOnWriteArrayList<>();

    /** 由每个表执行器自身的终止钩子完成，包括已被移除的执行器。 */
    private final List<CompletableFuture<Void>> executorExits = new CopyOnWriteArrayList<>();

    private final CompletableFuture<Void> physicalExit = new CompletableFuture<>();

    private volatile boolean runReturned;

    private final List<Statement> activeScriptStatements = new CopyOnWriteArrayList<>();

    private final List<ScriptExecution> scriptExecutions = new CopyOnWriteArrayList<>();

    /** run() 失败时仍保留脚本证据，其中绝不包含 SQL 或厂商错误消息。 */
    public List<ScriptExecution> scriptExecutions() {
        return List.copyOf(scriptExecutions);
    }

    public enum ScriptState {SUCCEEDED, REJECTED, PARTIAL, UNKNOWN, CANCELLED}

    public record ScriptExecution(String phase, String digest, int statements, int acknowledgedStatements,
                                  int inFlightStatement, ScriptState state, String sqlState, int vendorCode) {

    }

    public static final class ScriptExecutionException extends IllegalStateException {

        private final ScriptExecution execution;

        /**
         * 初始化 {@code ScriptExecutionException}及其父类状态。
         */
        private ScriptExecutionException(ScriptExecution execution) {
            super("Migration script failed: " + execution);
            this.execution = execution;
        }

        /**
         * 返回执行。
         */
        public ScriptExecution execution() {
            return execution;
        }
    }

    /**
     * 线程是否被中断的标识
     */
    private volatile boolean interrupted = false;

    /** 单次使用的迁移实例；取消发生在 run 之前也必须生效。 */
    private volatile boolean started;

    private Thread runThread;

    private volatile MigrationTermination termination = MigrationTermination.UNCONFIRMED;

    private volatile boolean cleanupConfirmed = true;

    /** 本地资源退出不能覆盖已观察到的远端写入未决结果；单次实例只允许置为 true。 */
    private volatile boolean externalWriteUnconfirmed;

    /**
     * 即使 run 抛异常也可查询；UNCONFIRMED 不会仅因 Future 已取消而升级。
     */
    public MigrationTermination termination() {
        return termination;
    }

    /**
     * 实际执行和资源退出的回执，独立于写入确定性；{@link #termination()} 仍保留
     * 缓存的保守值；只有 run() 返回、全部表执行器终止、
     * 全部流水线写入器退出，以及所有已打开连接池的受控驱动
     * {@link DriverPoolFactory#physicalTermination} 完成后才正常完成（包含延迟创建连接
     * 和延迟物理关闭）；启动前已被中断的实例没有待退出资源；完成取决于这些事件，
     * 而非经过的时间、Hikari 关闭状态或 Future 取消；若已打开的连接池没有
     * 物理终止证据，则以异常完成；调用方不能主动完成该回执，依赖动作可能运行在驱动回调
     * 线程中，因此不得阻塞或执行 I/O。
     */
    public java.util.concurrent.CompletionStage<Void> physicalExit() {
        observePhysicalExit();
        return physicalExit.minimalCompletionStage();
    }

    /** 无锁且不阻塞：由驱动回执、执行器终止和写入器退出触发。 */
    private void observePhysicalExit() {
        if (physicalExit.isDone()) {
            return;
        }
        // 先读取 interrupted；一旦设置该标志，run() 就不能再启动。
        if (interrupted && !started) {
            physicalExit.complete(null);
            return;
        }
        if (!runReturned) {
            return;
        }
        if (ownedPools.stream().anyMatch(pool -> !pool.hasExitEvidence())) {
            physicalExit.completeExceptionally(
                    new IllegalStateException("An opened pool has no physical-termination evidence"));
            return;
        }
        if (executorExits.stream().allMatch(CompletableFuture::isDone)
                && migrationHandlers.stream().noneMatch(MigrationHandler::hasActiveWriters)
                && ownedPools.stream().allMatch(PoolResources::physicallyExited)) {
            physicalExit.complete(null);
        }
    }

    /**
     * 配置参数
     */
    private final MigrationRequest properties;

    /**
     * 表结果处理器：每张表迁移完成（成功或失败）后回调，供上层保存表级同步结果
     */
    private final TableResultHandler tableResultHandler;

    /**
     * 行级值转换钩子（可选，脱敏/值映射），null 表示不转换；实现须线程安全
     */
    private final RecordTransformer recordTransformer;

    /** 借用的部署运行时管理方，绝不能序列化到迁移属性中。 */
    private final ConfiguredJdbcDrivers configuredDrivers;

    private final DbSwitchRuntime runtime;

    private final MigrationTargetSqlAuthorizer targetSqlAuthorizer;


    /**
     * 构造函数
     *
     * @param properties 配置信息
     */
    public MigrationService(MigrationRequest properties, TableResultHandler tableResultHandler) {
        this(properties, tableResultHandler, null);
    }

    /**
     * 构造函数（带行级值转换钩子）
     *
     * @param properties        配置信息
     * @param recordTransformer 行级值转换钩子（脱敏/值映射），null 表示不转换
     */
    public MigrationService(MigrationRequest properties, TableResultHandler tableResultHandler,
            RecordTransformer recordTransformer) {
        this(properties, tableResultHandler, recordTransformer, DbSwitchRuntime.standalone());
    }

    /** 生产调用方显式传入由其上下文管理的部署注册表。 */
    public MigrationService(MigrationRequest properties, TableResultHandler tableResultHandler,
            RecordTransformer recordTransformer, ConfiguredJdbcDrivers configuredDrivers) {
        this(properties, tableResultHandler, recordTransformer,
                new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), configuredDrivers));
    }

    /**
     * 校验构造参数并初始化 {@code MigrationService}。
     */
    public MigrationService(MigrationRequest properties, TableResultHandler tableResultHandler,
            RecordTransformer recordTransformer, DbSwitchRuntime runtime) {
        this(properties, tableResultHandler, recordTransformer, runtime, null);
    }

    /** 显式安装调用方的目标计划授权；旧构造器不改变已有组件调用方的行为。 */
    public MigrationService(MigrationRequest properties, TableResultHandler tableResultHandler,
            RecordTransformer recordTransformer, DbSwitchRuntime runtime,
            MigrationTargetSqlAuthorizer targetSqlAuthorizer) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.properties = Objects.requireNonNull(properties, "properties is null").snapshot();
        this.targetSqlAuthorizer = targetSqlAuthorizer;
        if (targetSqlAuthorizer != null) {
            validateAuthorizedConfiguration(this.properties);
            if (recordTransformer != null) {
                throw new IllegalArgumentException("Authorized table copy does not support row transformers");
            }
        }
        this.properties.getTarget().setTransformDefinition(
                cn.iocoder.yudao.framework.dbswitch.common.entity.RecordTransformDefinition.resolve(
                        this.properties.getTarget().getTransformDefinition(), recordTransformer));
        this.tableResultHandler = tableResultHandler;
        this.recordTransformer = recordTransformer;
        this.configuredDrivers = runtime.drivers();
        for (var source : this.properties.getSource()) {
            if (tidbManagedCursor(source,
                    this.properties.getTarget()) && (source.getPoolSize() == null || source.getPoolSize() < 2)) {
                throw new IllegalArgumentException("TiDB cursor source pool requires two connections per active table");
            }
        }
    }

    /** 入队与执行复用的纯配置检查；原生表结构仍在首次目标写入前读取验证。 */
    public static void validateAuthorizedConfiguration(MigrationRequest request) {
        Objects.requireNonNull(request, "request");
        var target = Objects.requireNonNull(request.getTarget(), "target");
        if (request.getSource() == null || request.getSource().isEmpty()
                || target.getTargetProductType() != ProductTypeEnum.POSTGRESQL
                || !"CREATE_NEW".equals(target.getWriteMode())
                || !Boolean.TRUE.equals(target.getWriterEngineInsert())
                || Boolean.TRUE.equals(target.getTargetDrop()) || Boolean.TRUE.equals(target.getTargetTruncateData())
                || Boolean.TRUE.equals(target.getChangeDataSync()) || Boolean.TRUE.equals(target.getOnlyCreate())
                || Boolean.TRUE.equals(target.getIndexCreate()) || Boolean.TRUE.equals(target.getSyncDefaultVal())
                || Boolean.TRUE.equals(target.getCreateTableAutoIncrement())
                || Boolean.TRUE.equals(target.getPreserveIdentityValues())
                || Boolean.TRUE.equals(target.getExclusiveTargetOwnership())
                || Boolean.TRUE.equals(target.getExclusiveTargetDdlOwnership())
                || target.getCustomDdlMap() != null && !target.getCustomDdlMap().isEmpty()
                || target.getDistributedKeys() != null && !target.getDistributedKeys().isEmpty()
                || StringUtils.hasText(target.getBeforeSqlScripts()) || StringUtils.hasText(target.getAfterSqlScripts())
                || !request.getTypeMappingAcknowledgements().isEmpty()
                || target.getCursorRunBindings() != null || target.getGroupRewriteBindings() != null
                || target.getGroupRewriteBindingProvider() != null || target.getTableRewriteBindingProvider() != null) {
            throw new IllegalArgumentException("Authorized migration requires ordinary PostgreSQL CREATE_NEW full INSERT copy");
        }
        for (var source : request.getSource()) {
            if (source.getSourceProductType() != ProductTypeEnum.POSTGRESQL
                    || !SourceType.TABLE.getCode().equals(source.getSourceType())
                    || StringUtils.hasText(source.getSourceSql())
                    || StringUtils.hasText(source.getBeforeSqlScripts()) || StringUtils.hasText(source.getAfterSqlScripts())
                    || StringUtils.hasText(source.getTimestampColumn()) || StringUtils.hasText(source.getIncreaseColumnName())
                    || source.getConfigMap() != null && source.getConfigMap().values().stream().anyMatch(mapper ->
                    StringUtils.hasText(mapper.getTargetTableComment()) || StringUtils.hasText(mapper.getTimestampColumn())
                            || StringUtils.hasText(mapper.getIncreaseColumnName()) || mapper.getDorisOptions() != null)) {
                throw new IllegalArgumentException("Authorized migration requires ordinary PostgreSQL TABLE sources without hooks");
            }
        }
    }

    /**
     * 创建当前 TiDB 受管源代次绑定的游标读取器。
     */
    private static boolean tidbManagedCursor(SourceDataSourceProperties source, TargetDataSourceProperties target) {
        return source.getSourceProductType() == ProductTypeEnum.TIDB && Boolean.TRUE.equals(target.getChangeDataSync())
                && cn.iocoder.yudao.framework.dbswitch.common.constant.ChangeDataSyncType.INCREASE_COLUMN.name()
                .equals(target.getChangeDataSyncType());
    }

    /**
     * 中断执行中的任务：置中断标记，通知所有单表迁移处理器中断，
     * 并对活跃线程池执行 shutdownNow（中断在执行的线程、丢弃排队任务）。
     * run() 侧会等待线程池终止后再关闭数据源，避免任务访问已关闭的连接池
     */
    synchronized public void interrupt() {
        this.interrupted = true;
        migrationHandlers.forEach(MigrationHandler::interrupt);
        activeExecutors.forEach(ExecutorService::shutdownNow);
        for (Statement statement : activeScriptStatements) {
            try {
                statement.cancel();
            } catch (SQLException failure) {
                log.warn("Migration script cancellation was not acknowledged");
            }
        }
        if (runThread != null) {
            runThread.interrupt();
        } else if (!started) {
            termination = MigrationTermination.CONFIRMED;
        }
        observePhysicalExit();
    }

    /**
     * 执行主逻辑
     * <p>
     * 主要流程：
     * 1. 创建目标端连接池并做连通性探测（失败重试一次）；
     * 2. 遍历每个源端配置：创建源端连接池（同样探测连通性），
     *    按源端与目标端连接池大小的较小值创建固定线程池；
     * 3. 表模式：按 schema 查询表清单，再按 include（含单个通配符模式）/exclude
     *    配置和表类型（表/视图）过滤，为每张命中的表提交一个异步迁移任务；
     *    SQL 模式：直接以配置的 SQL 构造一个迁移任务；
     * 4. 等待该源端所有表任务完成（allOf().get()），记录该源端的性能统计；
     * 5. 所有源端处理完毕返回汇总结果；finally 中输出总耗时与各源端统计报表
     *
     * @return 全部表的汇总迁移结果
     * @throws Exception 建连失败或任务执行异常时抛出
     */
    public DbSwitchResult run() throws Exception {
        synchronized (this) {
            if (started) {
                throw new IllegalStateException("MigrationService instances are single-use");
            }
            if (interrupted) {
                throw new CancellationException("Migration cancelled before start");
            }
            started = true;
            runThread = Thread.currentThread();
            termination = MigrationTermination.RUNNING;
        }
        DbSwitchResult result = new DbSwitchResult();
        try {
            return execute(result);
        } catch (Exception | Error failure) {
            recordUnconfirmedWrite(failure);
            throw failure;
        } finally {
            synchronized (this) {
                termination = cleanupConfirmed && !externalWriteUnconfirmed
                        && ownedPools.stream().allMatch(PoolResources::hasExited)
                        && activeExecutors.stream().allMatch(ExecutorService::isTerminated)
                        && migrationHandlers.stream().noneMatch(MigrationHandler::hasActiveWriters)
                        ? MigrationTermination.CONFIRMED : MigrationTermination.UNCONFIRMED;
                result.setTermination(termination);
                runThread = null;
            }
            runReturned = true;
            observePhysicalExit();
        }
    }

    /**
     * 拒绝当前实现未支持的 {@code execute} 操作。
     */
    private DbSwitchResult execute(DbSwitchResult dbSwitchResult) throws Exception {
        StopWatch watch = new StopWatch();
        watch.start();

        log.info("dbswitch data service is started....");

        TargetDataSourceProperties target = properties.getTarget();
        checkCancelled();
        try (OwnedDataSource targetResource = openTargetDataSource(target)) {
            HikariDataSource targetDataSource = targetResource.dataSource;
            // 目标端连通性探测：失败等待 1 秒重试，共尝试 2 次
            int targetAttempts = 2;
            for (int ta = 1; ta <= targetAttempts; ta++) {
                try (Connection c = targetDataSource.getConnection()) {
                    break;
                } catch (Exception e) {
                    if (ta < targetAttempts) {
                        Thread.sleep(1000);
                    } else {
                        throw e;
                    }
                }
            }
            var taskTargetPlan = properties.getSource().size() > 1 ? planTaskTargets(targetDataSource) : null;
            // 前置脚本失败时，不得继续创建处理器或写入表。
            executeSqlScripts(targetDataSource, target.getTargetProductType(), target.getBeforeSqlScripts(),
                    "target-before");
            try {
                int sourcePropertiesIndex = 0;
                int totalTableCount = 0;
                List<SourceDataSourceProperties> sourcesProperties = properties.getSource();
                for (SourceDataSourceProperties sourceProperties : sourcesProperties) {
                    // 保留失败时的部分结果；远端写入结果未知时，不得继续处理新的源端。
                    if (externalWriteUnconfirmed) {
                        break;
                    }
                    if (interrupted) {
                        throw new RuntimeException("task is interrupted");
                    }
                    try (OwnedDataSource sourceResource = openSourceDataSource(sourceProperties)) {
                        HikariDataSource sourceDataSource = sourceResource.dataSource;
                        int sourceAttempts = 2;
                        for (int sa = 1; sa <= sourceAttempts; sa++) {
                            try (Connection c = sourceDataSource.getConnection()) {
                                break;
                            } catch (Exception e) {
                                if (sa < sourceAttempts) {
                                    Thread.sleep(1000);
                                } else {
                                    throw e;
                                }
                            }
                        }
                        // 源端前置 SQL（移植自 dbswitch 2.0.2）
                        executeSqlScripts(sourceDataSource, sourceProperties.getSourceProductType(),
                                sourceProperties.getBeforeSqlScripts(), "source-before");
                        // 受控 TiDB 游标保留一个快照连接，还需要一次新的源端代次探测。
                        int sourceConcurrency = tidbManagedCursor(sourceProperties,
                                target) ? sourceProperties.getPoolSize() / 2 : sourceProperties.getPoolSize();
                        ExecutorService executor = newTableExecutor(
                                Math.max(1, Math.min(sourceConcurrency, target.getPoolSize())));
                        synchronized (this) {
                            activeExecutors.add(executor);
                            if (interrupted) {
                                executor.shutdownNow();
                            }
                        }
                        SequenceRun sequenceRun = null;
                        try {
                            List<CompletableFuture<Void>> futures = new ArrayList<>();
                            AtomicInteger numberOfFailures = new AtomicInteger(0);
                            AtomicLong currentSourceRowCount = new AtomicLong(0L);

                            if (SourceType.TABLE.getCode().equals(sourceProperties.getSourceType())) {
                                List<TableDescription> selectedTables = planSourceTables(sourceProperties,
                                        sourceDataSource);
                                verifyTaskTargets(taskTargetPlan, sourcePropertiesIndex, sourceProperties,
                                        selectedTables);
                                if (!selectedTables.isEmpty()) {
                                    validateTargetMappings(selectedTables, sourceProperties, target, targetDataSource);
                                    checkCancelled();
                                    if (sourceProperties.getSourceProductType() != ProductTypeEnum.POSTGRESQL) {
                                        if (sourceProperties.getTableReadMode() != cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.ReadMode.INCLUDE_DESCENDANTS) {
                                            throw new UnsupportedOperationException(
                                                    "ONLY relation reads are not verified for this product");
                                        }
                                        for (var table : selectedTables) {
                                            futures.add(makeFutureTask(table, sourcePropertiesIndex, sourceDataSource,
                                                    targetDataSource, numberOfFailures, currentSourceRowCount,
                                                    dbSwitchResult, executor));
                                        }
                                    } else {
                                        var selected = new java.util.LinkedHashMap<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation, TableDescription>();
                                        for (var table : selectedTables) {
                                            selected.put(
                                                    new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(
                                                            table.getSchemaName(), table.getTableName()), table);
                                        }
                                        var mode = sourceProperties.getTableReadMode();
                                        if (mode == cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.ReadMode.ONLY
                                                && Boolean.TRUE.equals(target.getChangeDataSync())) {
                                            throw new UnsupportedOperationException(
                                                    "ONLY relation reads are not implemented for incremental synchronization");
                                        }
                                        cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan readPlan;
                                        try (var connection = sourceDataSource.getConnection()) {
                                            readPlan = runtime.products()
                                                    .metadata(sourceProperties.getSourceProductType())
                                                    .planTableReads(connection, selected.keySet(), mode);
                                        }
                                        sequenceRun = prepareSequenceRun(readPlan, selected, sourcePropertiesIndex,
                                                sourceDataSource, targetDataSource,
                                                numberOfFailures, currentSourceRowCount, dbSwitchResult);
                                        if (sequenceRun == null) {
                                            for (var read : readPlan.reads()) {
                                                futures.add(makeFutureTask(selected.get(read.relation()),
                                                        sourcePropertiesIndex,
                                                        sourceDataSource, targetDataSource, numberOfFailures,
                                                        currentSourceRowCount, dbSwitchResult, executor, read));
                                            }
                                        } else {
                                            futures.addAll(sequenceRun.schedule(executor));
                                        }
                                    }
                                }
                            } else if (SourceType.SQL.getCode().equals(sourceProperties.getSourceType())) {
                                TableDescription td = new TableDescription();
                                td.setTableName(sourceProperties.getSourceSql());
                                td.setTableType(DBTableType.SQL.name());
                                td.setSchemaName(sourceProperties.getSourceSchema());
                                td.setSql(sourceProperties.getSourceSql());
                                verifyTaskTargets(taskTargetPlan, sourcePropertiesIndex, sourceProperties, List.of(td));
                                log.info("Source SQL digest={}", SqlScriptPlan.digest(sourceProperties.getSourceSql()));
                                futures.add(
                                        makeFutureTask(td, sourcePropertiesIndex, sourceDataSource, targetDataSource,
                                                numberOfFailures, currentSourceRowCount, dbSwitchResult, executor));
                            }

                            if (!interrupted) {
                                if (futures.isEmpty()
                                        && cn.iocoder.yudao.framework.dbswitch.data.util.MigrationWriteModes.failOnMissingSource(
                                        target.getWriteMode())) {
                                    throw new IllegalStateException(
                                            "CREATE_NEW/STRICT_COPY 未匹配到唯一源对象，拒绝把空结果标为成功");
                                }
                                try {
                                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[]{})).get();
                                    if (sequenceRun != null) {
                                        sequenceRun.finish();
                                    }
                                } catch (InterruptedException e) {
                                    // get() 清除了中断位；恢复后 finally 不会再把阻塞回调等满 90 秒。
                                    Thread.currentThread().interrupt();
                                    throw e;
                                }
                                log.info(
                                        "#### Complete data migration for the [ {} ] data source:\ntotal table count={}\nfailure count={}\ntotal count size={}",
                                        sourcePropertiesIndex, futures.size(), numberOfFailures.get(),
                                        currentSourceRowCount.get());
                                perfStats.add(new PerfStat(sourcePropertiesIndex, futures.size(),
                                        numberOfFailures.get(), currentSourceRowCount.get()));
                                ++sourcePropertiesIndex;
                                totalTableCount += futures.size();
                            }
                        } finally {
                            // 等待线程池终止后再让数据源随 try-with-resources 关闭：
                            // 历史实现仅 shutdown() 不等待，中断/异常路径下运行中的任务会访问已关闭的连接池
                            if (shutdownAndAwaitTermination(executor)) {
                                activeExecutors.remove(executor);
                            } else {
                                cleanupConfirmed = false;
                            }
                            if (sequenceRun != null) {
                                sequenceRun.closeAfterWorkers();
                            }
                            if (!externalWriteUnconfirmed && !interrupted && !Thread.currentThread().isInterrupted()
                                    && executor.isTerminated()
                                    && migrationHandlers.stream().noneMatch(MigrationHandler::hasActiveWriters)) {
                                executeAfterSqlScripts(sourceDataSource, sourceProperties.getSourceProductType(),
                                        sourceProperties.getAfterSqlScripts(), "source-after", dbSwitchResult);
                            } else if (hasScript(sourceProperties.getAfterSqlScripts())) {
                                cleanupConfirmed = false;
                                dbSwitchResult.getIfAllSuccess().set(false);
                            }
                        }
                    }
                }
                log.info("Migration table phase finished, success={}, tables={}, rows={}",
                        dbSwitchResult.getIfAllSuccess().get(), totalTableCount, dbSwitchResult.getTotalRowCount());
                return dbSwitchResult;
            } finally {
                if (!externalWriteUnconfirmed && !interrupted && !Thread.currentThread().isInterrupted()
                        && activeExecutors.stream().allMatch(ExecutorService::isTerminated)
                        && migrationHandlers.stream().noneMatch(MigrationHandler::hasActiveWriters)) {
                    executeAfterSqlScripts(targetDataSource, target.getTargetProductType(),
                            target.getAfterSqlScripts(), "target-after", dbSwitchResult);
                } else if (hasScript(target.getAfterSqlScripts())) {
                    cleanupConfirmed = false;
                    dbSwitchResult.getIfAllSuccess().set(false);
                }
            }
        } finally {
            watch.stop();
            log.info("total ellipse = {} s", watch.getTotalTimeSeconds());

            StringBuilder sb = new StringBuilder();
            sb.append("===================================\n");
            sb.append(String.format("total ellipse time:\t %f s\n", watch.getTotalTimeSeconds()));
            sb.append("-------------------------------------\n");
            perfStats.forEach(st -> {
                sb.append(st);
                if (perfStats.size() > 1) {
                    sb.append("-------------------------------------\n");
                }
            });
            sb.append("===================================\n");
            log.info("\n\n" + sb.toString());
        }
    }

    /**
     * 根据源端配置打开迁移数据源。
     */
    private OwnedDataSource openSourceDataSource(SourceDataSourceProperties source) {
        var binding = configuredDrivers == null ? null : configuredDrivers.find(source.getSourceProductType(),
                source.getDriverProfileId()).orElse(null);
        var drivers = configuredDrivers == null ? new cn.iocoder.yudao.framework.dbswitch.driver.DriverRuntimeRegistry() : configuredDrivers.runtimes();
        Long budget = sequencePhaseBudget();
        return openDataSource((factory, observer) -> budget == null
                ? DataSourceUtils.createSourceDataSource(source, factory, observer, binding, drivers, runtime.options())
                : DataSourceUtils.createSourceDataSource(source, factory, observer, binding, drivers, runtime.options(),
                budget));
    }

    /**
     * 根据目标端配置打开迁移数据源。
     */
    private OwnedDataSource openTargetDataSource(TargetDataSourceProperties target) {
        var binding = configuredDrivers == null ? null : configuredDrivers.find(target.getTargetProductType(),
                target.getDriverProfileId()).orElse(null);
        var drivers = configuredDrivers == null ? new cn.iocoder.yudao.framework.dbswitch.driver.DriverRuntimeRegistry() : configuredDrivers.runtimes();
        Long budget = sequencePhaseBudget();
        return openDataSource((factory, observer) -> budget == null
                ? DataSourceUtils.createTargetDataSource(target, factory, observer, binding, drivers, runtime.options())
                : DataSourceUtils.createTargetDataSource(target, factory, observer, binding, drivers, runtime.options(),
                budget));
    }

    /**
     * 为当前序列迁移阶段分配资源预算。
     */
    private Long sequencePhaseBudget() {
        return properties.getTarget().getTargetProductType() == ProductTypeEnum.POSTGRESQL
                && Boolean.TRUE.equals(properties.getTarget().getPreserveIdentityValues())
                ? Math.min(120, runtime.options().queryTimeoutSeconds()) * 1000L : null;
    }

    /** 任务预留和实际执行使用相同的选择规则和原生表族展开规则。 */
    private List<TableDescription> planSourceTables(SourceDataSourceProperties source, DataSource dataSource) {
        var metadata = runtime.metadata(dataSource, source.getSourceProductType());
        var exact = cn.iocoder.yudao.framework.dbswitch.data.config.SourceTableSelection.exactNames(source);
        boolean include = Integer.valueOf(1).equals(source.getIncludeOrExclude());
        var includes = exact == null ? DbswitchStrUtils.stringToList(
                source.getSourceIncludes()) : include ? exact : List.<String>of();
        var excludes = exact == null ? DbswitchStrUtils.stringToList(
                source.getSourceExcludes()) : include ? List.<String>of() : exact;
        var selected = new ArrayList<TableDescription>();
        // 只有 PostgreSQL 且未点名时才收窄。其他产品，以及显式表名，仍走完整枚举。
        Function<List<TableDescription>, List<TableDescription>> postgresqlDefaultSync =
                includes.isEmpty() && source.getSourceProductType() == ProductTypeEnum.POSTGRESQL
                        ? runtime.products().metadata(ProductTypeEnum.POSTGRESQL)::defaultSyncTargets
                        : Function.identity();
        for (String schema : DbswitchStrUtils.stringToList(source.getSourceSchema())) {
            checkCancelled();
            selected.addAll(selectSourceTables(source.getSourceProductType(), source.getTableType(), includes, excludes,
                    metadata.queryTableList(schema), postgresqlDefaultSync, exact != null));
        }
        if (selected.isEmpty()) {
            return List.of();
        }
        var schemas = selected.stream().map(TableDescription::getSchemaName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return validatePlannedSources(
                runtime.reader(dataSource, source.getSourceProductType()).planMigrationSources(List.copyOf(selected)),
                schemas, includes.isEmpty() ? new java.util.HashSet<>(excludes) : java.util.Set.of());
    }

    /** 未点名的 PostgreSQL 用 defaultSyncTargets。显式表名或模式不调用它。 */
    static List<TableDescription> selectSourceTables(ProductTypeEnum product, DBTableType tableType,
            List<String> includes, List<String> excludes, List<TableDescription> available,
            Function<List<TableDescription>, List<TableDescription>> postgresqlDefaultSync) {
        return selectSourceTables(product, tableType, includes, excludes, available, postgresqlDefaultSync, false);
    }

    /**
     * 根据选择规则筛选本次迁移的源表。
     */
    static List<TableDescription> selectSourceTables(ProductTypeEnum product, DBTableType tableType,
            List<String> includes, List<String> excludes, List<TableDescription> available,
            Function<List<TableDescription>, List<TableDescription>> postgresqlDefaultSync, boolean exact) {
        var tables = includes.isEmpty() && product == ProductTypeEnum.POSTGRESQL
                ? postgresqlDefaultSync.apply(available) : available;
        var selected = new ArrayList<TableDescription>();
        for (var table : tables) {
            if (includes.isEmpty()) {
                if (tableType != null && !tableType.name().equals(table.getTableType())) {
                    continue;
                }
                if (!excludes.contains(table.getTableName())) {
                    selected.add(table);
                }
            } else if (!exact && includes.size() == 1 && (includes.getFirst().contains("*") || includes.getFirst()
                    .contains("?"))) {
                if (matchesTablePattern(includes.getFirst(), table.getTableName())) {
                    selected.add(table);
                }
            } else if (includes.contains(table.getTableName())) {
                selected.add(table);
            }
        }
        return selected;
    }

    private record ReservedMapping(cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation source,
                                   cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation target) {

    }

    /**
     * 构建源表到目标表的名称及列映射。
     */
    private java.util.Set<ReservedMapping> targetMappings(SourceDataSourceProperties source,
            List<TableDescription> tables) {
        var target = properties.getTarget();
        var values = new java.util.HashSet<ReservedMapping>();
        for (var table : tables) {
            var relation = new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(
                    table.getSchemaName(),
                    SourceType.SQL.getCode().equals(source.getSourceType()) ? SqlScriptPlan.digest(
                            source.getSourceSql()) : table.getTableName());
            String name = cn.iocoder.yudao.framework.dbswitch.data.util.MigrationTableNames.resolve(source, target,
                    table.getTableName());
            values.add(new ReservedMapping(relation,
                    new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(target.getTargetSchema(),
                            name)));
        }
        return java.util.Set.copyOf(values);
    }

    /** 任何目标脚本、DDL 或 DML 执行前，读取全部选中源端并释放每个临时连接池。 */
    private Map<Integer, java.util.Set<ReservedMapping>> planTaskTargets(DataSource target) {
        var result = new java.util.LinkedHashMap<Integer, java.util.Set<ReservedMapping>>();
        var exact = new java.util.HashSet<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation>();
        var folded = new java.util.HashSet<String>();
        boolean caseEvidence = false;
        int index = 0;
        for (var source : properties.getSource()) {
            checkCancelled();
            List<TableDescription> tables;
            if (SourceType.TABLE.getCode().equals(source.getSourceType())) {
                try (var resource = openSourceDataSource(source)) {
                    tables = planSourceTables(source, resource.dataSource);
                }
            } else {
                var table = new TableDescription();
                table.setSchemaName(source.getSourceSchema());
                table.setTableName(source.getSourceSql());
                tables = List.of(table);
            }
            var mappings = targetMappings(source, tables);
            if (mappings.size() != tables.size()) {
                throw new IllegalArgumentException("Task contains duplicate source table coverage");
            }
            for (var mapping : mappings) {
                if (!exact.add(mapping.target())) {
                    throw new IllegalArgumentException(
                            "Multiple source resources map to the same physical target table");
                }
                String key = mapping.target().schema().toLowerCase(java.util.Locale.ROOT) + "\u0000" + mapping.target()
                        .table().toLowerCase(java.util.Locale.ROOT);
                if (!folded.add(key)) {
                    caseEvidence = true;
                }
            }
            result.put(index++, mappings);
        }
        if (caseEvidence) {
            try (var connection = target.getConnection()) {
                if (!connection.getMetaData().supportsMixedCaseQuotedIdentifiers()) {
                    throw new IllegalArgumentException("Target identifier rules collapse task-wide table mappings");
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Cannot verify task target identifier rules");
            }
        }
        return Map.copyOf(result);
    }

    /**
     * 检查当前迁移任务的目标对象集合不存在冲突。
     */
    private void verifyTaskTargets(Map<Integer, java.util.Set<ReservedMapping>> expected, int sourceIndex,
            SourceDataSourceProperties source, List<TableDescription> tables) {
        if (expected != null && !Objects.equals(expected.get(sourceIndex), targetMappings(source, tables))) {
            throw new IllegalStateException("Source relation plan changed after task-wide target reservation");
        }
    }

    /**
     * 准备本次序列搬运的依赖图和执行状态。
     */
    private SequenceRun prepareSequenceRun(cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan reads,
            Map<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation, TableDescription> selected,
            int sourceIndex, HikariDataSource source, HikariDataSource target, AtomicInteger failures,
            AtomicLong rows, DbSwitchResult result) {
        var options = properties.getTarget();
        if (options.getTargetProductType() != ProductTypeEnum.POSTGRESQL || !Boolean.TRUE.equals(
                options.getPreserveIdentityValues())
                || Boolean.TRUE.equals(options.getChangeDataSync())) {
            return null;
        }
        var relations = reads.reads().stream()
                .map(cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Read::relation).toList();
        var graph = runtime.reader(source, ProductTypeEnum.POSTGRESQL).planMigrationDependencies(relations);
        boolean grouped = graph.sequences().stream().anyMatch(sequence -> sequence.shared()
                || sequence.owner()
                .state() == cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.ABSENT);
        if (!grouped) {
            return null;
        }
        graph.requireClosedSelection();
        if (!Boolean.TRUE.equals(options.getSyncExist())
                || options.getCustomDdlMap() != null && !options.getCustomDdlMap().isEmpty()) {
            throw new IllegalArgumentException(
                    "Shared sequence transfer requires complete selection and controlled structure planning");
        }
        var handlers = new java.util.LinkedHashMap<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation, MigrationHandler>();
        var plans = new ArrayList<cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TablePlan>();
        for (var read : reads.reads()) {
            var handler = getMigrateHandler(selected.get(read.relation()), sourceIndex, source, target, read);
            plans.add(handler.sequenceTablePlan());
            handlers.put(read.relation(), handler);
        }
        var mode = options.getWriteMode();
        var scope = runtime.reader(target, ProductTypeEnum.POSTGRESQL).prepareSequenceTransfer(graph, plans,
                cn.iocoder.yudao.framework.dbswitch.data.util.MigrationWriteModes.allowDrop(
                        Boolean.TRUE.equals(options.getTargetDrop()), mode),
                !Boolean.TRUE.equals(
                        options.getOnlyCreate()) && cn.iocoder.yudao.framework.dbswitch.data.util.MigrationWriteModes.allowTruncate(
                        options.getTargetTruncateData(), mode),
                cn.iocoder.yudao.framework.dbswitch.data.util.MigrationWriteModes.failIfTargetExists(mode),
                Boolean.TRUE.equals(
                        options.getOnlyCreate()) ? cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.Mode.STRUCTURE_ONLY
                        : cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.Mode.DATA_AND_STRUCTURE);
        try {
            var statements = new java.util.LinkedHashMap<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation, List<String>>();
            for (var entry : handlers.entrySet()) {
                entry.getValue().bindSequenceTransfer(scope.binding(entry.getKey()));
                entry.getValue().preflightSequenceTarget();
                statements.put(entry.getKey(), entry.getValue().sequenceCreateStatements());
            }
            checkCancelled();
            scope.prepare(statements);
            checkCancelled();
            return new SequenceRun(scope, handlers, selected, sourceIndex, failures, rows, result);
        } catch (RuntimeException | Error failure) {
            scope.close();
            throw failure;
        }
    }

    /** 保留表级回执；只有资源级重启的一次提交完成后，才发布成功状态。 */
    private final class SequenceRun {

        final cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope scope;

        final Map<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation, MigrationHandler> handlers;

        final Map<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation, TableDescription> descriptions;

        final int sourceIndex;

        final AtomicInteger failures;

        final AtomicLong rows;

        final DbSwitchResult result;

        final List<DbSwitchTableResult> deferred = new CopyOnWriteArrayList<>();

        boolean published;

        /**
         * 初始化 {@code SequenceRun}。
         */
        SequenceRun(cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope scope,
                Map<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation, MigrationHandler> handlers,
                Map<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation, TableDescription> descriptions,
                int sourceIndex, AtomicInteger failures, AtomicLong rows, DbSwitchResult result) {
            this.scope = scope;
            this.handlers = handlers;
            this.descriptions = descriptions;
            this.sourceIndex = sourceIndex;
            this.failures = failures;
            this.rows = rows;
            this.result = result;
        }

        /**
         * 返回调度配置。
         */
        List<CompletableFuture<Void>> schedule(ExecutorService executor) {
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            for (var entry : handlers.entrySet()) {
                tasks.add(CompletableFuture.supplyAsync(() -> {
                            checkCancelled();
                            return entry.getValue().get();
                        }, executor)
                        .exceptionally(getExceptHandler(descriptions.get(entry.getKey()), sourceIndex, failures,
                                entry.getValue()))
                        .thenAccept(deferred::add));
            }
            return tasks;
        }

        /**
         * 完成当前操作并汇总最终状态。
         */
        void finish() {
            if (deferred.size() != handlers.size() || deferred.stream()
                    .anyMatch(value -> !value.getIfSuccess().get())) {
                publish(false);
                return;
            }
            boolean succeeded;
            try {
                checkCancelled();
                scope.complete();
                succeeded = true;
            } catch (RuntimeException failure) {
                recordUnconfirmedWrite(failure);
                succeeded = false;
            }
            publish(succeeded);
        }

        /**
         * 将当前已汇总的数据或状态发布给结果接收方。
         */
        void publish(boolean success) {
            if (published) {
                return;
            }
            published = true;
            boolean deliveryFailed = false;
            for (var value : deferred) {
                if (!success && value.getIfSuccess().getAndSet(false)) {
                    failures.incrementAndGet();
                    value.setSuccessMsg(null);
                    value.setErrorMsg(
                            "Shared sequence group was not finalized; confirmed row batches remain committed");
                }
                try {
                    getAcceptHandler(value, rows, result);
                } catch (RuntimeException failure) {
                    deliveryFailed = true;
                    result.getIfAllSuccess().set(false);
                }
            }
            if (!success) {
                result.getIfAllSuccess().set(false);
            }
            if (deliveryFailed) {
                throw new IllegalStateException(
                        "Table result delivery failed after all confirmed table receipts were retained");
            }
        }

        /**
         * 在所有工作线程真正退出后关闭共享资源。
         */
        void closeAfterWorkers() {
            try {
                publish(false);
            } finally {
                scope.close();
            }
        }
    }

    /** 任何表被调度进行目标修改前，验证完整产品计划。 */
    static List<TableDescription> validatePlannedSources(List<TableDescription> planned,
            java.util.Set<String> selectedSchemas, java.util.Set<String> excludedNames) {
        if (planned == null) {
            throw new IllegalStateException("Source relation plan is missing");
        }
        var unique = new java.util.LinkedHashMap<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation, TableDescription>();
        for (var table : planned) {
            if (table == null || table.getTableName() == null || table.getTableName().isBlank()
                    || table.getSchemaName() == null || !selectedSchemas.contains(table.getSchemaName())
                    || table.getSql() != null && !table.getSql().isBlank()) {
                throw new IllegalStateException("Source relation plan escaped the selected physical schema scope");
            }
            if (excludedNames.contains(table.getTableName())) {
                continue;
            }
            var identity = new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(
                    table.getSchemaName(), table.getTableName());
            if (unique.putIfAbsent(identity, table) != null) {
                throw new IllegalStateException("Source relation plan contains duplicate physical coverage");
            }
        }
        return List.copyOf(unique.values());
    }

    /** 任何并行表替换开始前，按执行器自身的命名规则解析名称。 */
    private static void validateTargetMappings(List<TableDescription> selected, SourceDataSourceProperties source,
            TargetDataSourceProperties target, DataSource dataSource) throws SQLException {
        var exact = new java.util.HashSet<String>();
        var folded = new java.util.HashSet<String>();
        boolean requiresCaseEvidence = false;
        for (var table : selected) {
            String name = cn.iocoder.yudao.framework.dbswitch.data.util.MigrationTableNames.resolve(source, target,
                    table.getTableName());
            if (!exact.add(name)) {
                throw new IllegalArgumentException("Multiple source relations map to the same target table");
            }
            if (!folded.add(name.toLowerCase(java.util.Locale.ROOT))) {
                requiresCaseEvidence = true;
            }
        }
        if (requiresCaseEvidence) {
            try (var connection = dataSource.getConnection()) {
                if (!connection.getMetaData().supportsMixedCaseQuotedIdentifiers()) {
                    throw new IllegalArgumentException(
                            "Target identifier rules collapse distinct source table mappings");
                }
            }
        }
    }

    /** 调度一个已验证的物理单元，保留失败和结果回调契约。 */
    private CompletableFuture<Void> makeFutureTask(
            TableDescription td,
            Integer indexInternal,
            HikariDataSource sds,
            HikariDataSource tds,
            AtomicInteger numberOfFailures,
            AtomicLong currentSourceRowCount,
            DbSwitchResult dbSwitchResult,
            ExecutorService executor) {
        return makeFutureTask(td, indexInternal, sds, tds, numberOfFailures, currentSourceRowCount, dbSwitchResult,
                executor, null);
    }

    /**
     * 为当前迁移处理器创建异步任务。
     */
    private CompletableFuture<Void> makeFutureTask(TableDescription td, Integer indexInternal, HikariDataSource sds,
            HikariDataSource tds, AtomicInteger numberOfFailures, AtomicLong currentSourceRowCount,
            DbSwitchResult dbSwitchResult,
            ExecutorService executor, cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Read read) {
        MigrationHandler instance = getMigrateHandler(td, indexInternal, sds, tds, read);
        return CompletableFuture.supplyAsync(() -> {
                    checkCancelled();
                    return instance.get();
                }, executor)
                .exceptionally(getExceptHandler(td, indexInternal, numberOfFailures, instance))
                .thenAccept((result) -> getAcceptHandler(result, currentSourceRowCount, dbSwitchResult));
    }

    /**
     * 处理汇总结果：把单表结果的行数/字节数累加到全局统计，
     * 按成功与否更新成功/失败表计数，并回调表结果处理器通知上层
     *
     * @param tableResult           单表迁移结果
     * @param currentSourceRowCount 当前源端已同步行数累计器
     * @param switchResult          全局汇总结果（原地累加）
     */
    private void getAcceptHandler(DbSwitchTableResult tableResult, AtomicLong currentSourceRowCount,
            DbSwitchResult switchResult) {

        currentSourceRowCount.getAndAdd(tableResult.getSyncCount().get());

        List<DbSwitchTableResult> tableResultList = switchResult.getTableResultList();
        AtomicBoolean ifAllSuccess = switchResult.getIfAllSuccess();
        AtomicLong totalRowCount = switchResult.getTotalRowCount();
        AtomicLong totalBytes = switchResult.getTotalBytes();
        AtomicLong totalTableCount = switchResult.getTotalTableCount();
        AtomicLong totalTableSuccessCount = switchResult.getTotalTableSuccessCount();
        AtomicLong totalTableFailCount = switchResult.getTotalTableFailCount();

        totalRowCount.getAndAdd(tableResult.getSyncCount().get());
        totalBytes.getAndAdd(tableResult.getSyncBytes().get());
        totalTableCount.getAndIncrement();
        if (!tableResult.getIfSuccess().get()) {
            ifAllSuccess.set(false);
            totalTableFailCount.getAndIncrement();
        } else {
            totalTableSuccessCount.getAndIncrement();
        }
        tableResultList.add(tableResult);
        //调用表结果处理器
        tableResultHandler.handler(tableResult);
    }


    /**
     * 单表迁移处理方法：创建该表的 MigrationHandler 并登记到列表（便于统一中断），
     * 返回给 CompletableFuture 异步执行
     *
     * @param td            表描述上下文
     * @param indexInternal 源端索引号
     * @param sds           源端的DataSource数据源
     * @param tds           目的端的DataSource数据源
     * @return Supplier<Long>
     */
    private MigrationHandler getMigrateHandler(
            TableDescription td,
            Integer indexInternal,
            HikariDataSource sds,
            HikariDataSource tds) {
        return getMigrateHandler(td, indexInternal, sds, tds, null);
    }

    /**
     * 返回当前表对应的迁移处理器。
     */
    private MigrationHandler getMigrateHandler(TableDescription td, Integer indexInternal, HikariDataSource sds,
            HikariDataSource tds, cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Read read) {
        MigrationHandler instance = MigrationHandler.createInstance(
                td, properties, indexInternal, sds, tds, recordTransformer, runtime, read, targetSqlAuthorizer);
        instance.observeWriterExit(this::observePhysicalExit);
        synchronized (this) {
            migrationHandlers.add(instance);
            if (interrupted) {
                instance.interrupt();
            }
        }
        return instance;
    }

    /**
     * 表名模式匹配：优先按正则解释（兼容历史配置），正则非法时按 glob 通配符
     * （* 匹配任意串、? 匹配单字符）转换后匹配。
     * 历史实现直接把用户输入当 Java 正则，单独配置 "*" 这类合法 glob 会因非法正则直接报错
     */
    private static boolean matchesTablePattern(String pattern, String tableName) {
        try {
            return Pattern.matches(pattern, tableName);
        } catch (PatternSyntaxException e) {
            return Pattern.matches(DbswitchStrUtils.globToRegex(pattern), tableName);
        }
    }

    /**
     * 关闭线程池并等待任务终止：正常路径任务已全部完成，立即返回；
     * 中断/异常路径等待运行中的任务在检查点退出（最多 60 秒），超时则强制中断再等 30 秒。
     * 确保数据源在所有任务结束后才被关闭
     */
    private boolean shutdownAndAwaitTermination(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("Migration executor did not terminate in time, "
                            + "running tasks may fail on closed datasource");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        return executor.isTerminated();
    }

    /**
     * 检查已取消。
     */
    private void checkCancelled() {
        if (interrupted || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Migration is cancelled");
        }
    }

    /** 与 Executors.newFixedThreadPool 相同的固定线程池；终止钩子表示表工作线程实际退出。 */
    private ExecutorService newTableExecutor(int threads) {
        CompletableFuture<Void> exited = new CompletableFuture<>();
        executorExits.add(exited);
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            /**
             * 检查当前处理流程是否已经终止。
             */
            @Override
            protected void terminated() {
                exited.complete(null);
                observePhysicalExit();
            }
        };
    }

    /**
     * 打开数据源。
     */
    private OwnedDataSource openDataSource(BiFunction<ThreadFactory, Consumer<Connection>, HikariDataSource> factory) {
        PoolResources resources = new PoolResources();
        // 创建连接池也可能启动线程后抛异常，必须先登记再调用工厂。
        ownedPools.add(resources);
        HikariDataSource dataSource = factory.apply(resources, resources.connections::add);
        // 在任何关闭操作前获取；调用方管理的连接池或未知连接池不提供退出证据。
        CompletableFuture<Void> receipt;
        try {
            receipt = DriverPoolFactory.physicalTermination(dataSource).toCompletableFuture();
        } catch (SQLException | RuntimeException unavailable) {
            receipt = CompletableFuture.failedFuture(unavailable);
        }
        resources.receipt = receipt;
        receipt.whenComplete((ignored, failure) -> observePhysicalExit());
        return new OwnedDataSource(dataSource, resources);
    }

    /** Hikari.close 超时会正常返回，必须另外确认它拥有的后台线程已退出。 */
    private static final class PoolResources implements ThreadFactory {

        private final List<Thread> threads = new CopyOnWriteArrayList<>();

        private final List<Connection> connections = new CopyOnWriteArrayList<>();

        private final AtomicInteger sequence = new AtomicInteger();

        /** 受控驱动的物理回执；仅当工厂在返回连接池之前失败时为 null。 */
        private volatile CompletableFuture<Void> receipt;

        /** 构造失败且未启动线程、未创建连接时，没有待退出资源。 */
        private boolean hasExitEvidence() {
            var evidence = receipt;
            return evidence == null ? threads.isEmpty() && connections.isEmpty() : !evidence.isCompletedExceptionally();
        }

        /**
         * 检查底层工作线程或进程是否已经实际退出。
         */
        private boolean physicallyExited() {
            var evidence = receipt;
            return evidence == null || evidence.isDone();
        }

        /**
         * 为当前任务创建具名线程。
         */
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "dbswitch-pool-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            threads.add(thread);
            return thread;
        }

        /**
         * 判断是否具有退出。
         */
        private boolean hasExited() {
            // NEW 也不是退出证据：它可能即将被连接池启动。
            if (threads.stream().anyMatch(thread -> thread.getState() != Thread.State.TERMINATED)) {
                return false;
            }
            for (Connection connection : connections) {
                try {
                    if (!connection.isClosed()) {
                        return false;
                    }
                } catch (Exception e) {
                    return false;
                }
            }
            return true;
        }

        /**
         * 等待底层线程或进程实际退出。
         */
        private boolean awaitExit() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            for (Thread thread : threads) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
            }
            return hasExited();
        }
    }

    /** close 返回与实际后台清理退出必须同时有证据。 */
    private final class OwnedDataSource implements AutoCloseable {

        private final HikariDataSource dataSource;

        private final PoolResources resources;

        /**
         * 初始化 {@code OwnedDataSource}。
         */
        private OwnedDataSource(HikariDataSource dataSource, PoolResources resources) {
            this.dataSource = dataSource;
            this.resources = resources;
        }

        /**
         * 关闭 {@code MigrationService} 当前持有的资源。
         */
        @Override
        public void close() {
            // Hikari 的关闭过程也需要等待线程；带中断位调用可能提前放弃清理。
            boolean restoreInterrupt = Thread.interrupted();
            try {
                dataSource.close();
                if (Thread.currentThread().isInterrupted()) {
                    cleanupConfirmed = false;
                }
                cleanupConfirmed &= resources.awaitExit();
            } catch (InterruptedException e) {
                cleanupConfirmed = false;
                Thread.currentThread().interrupt();
            } catch (RuntimeException | Error e) {
                cleanupConfirmed = false;
                throw e;
            } finally {
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * 检查当前配置是否包含非空脚本。
     */
    private static boolean hasScript(String sql) {
        return sql != null && !sql.isBlank();
    }

    /**
     * 表提交仍是有效证据；清理钩子失败不能抹除这些证据，。
     */
    private void executeAfterSqlScripts(DataSource dataSource, ProductTypeEnum product, String sql,
            String phase, DbSwitchResult result) {
        try {
            executeSqlScripts(dataSource, product, sql, phase);
        } catch (ScriptExecutionException failure) {
            // 表提交仍是有效证据；清理钩子失败不能抹除这些证据，
            // 也不能报告整体成功并诱导盲目重放已经提交的写入。
            result.getIfAllSuccess().set(false);
            cleanupConfirmed = false;
        }
    }

    /** 在一个自主管理且自动提交的连接上执行经过完整验证的计划。 */
    private void executeSqlScripts(DataSource dataSource, ProductTypeEnum product, String sql, String phase) {
        checkCancelled();
        if (!hasScript(sql)) {
            return;
        }
        String digest = SqlScriptPlan.digest(sql);
        int total = 0;
        int acknowledged = 0;
        int inFlight = 0;
        boolean executionStarted = false;
        boolean statementCancellationConfirmed = false;
        try {
            SqlScriptPlan plan = SqlScriptPlan.parseMigrationHook(product, sql);
            total = plan.steps().size();
            try (Connection connection = dataSource.getConnection()) {
                if (!connection.getAutoCommit()) {
                    throw new IllegalStateException("Migration script requires an auto-commit connection");
                }
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(60);
                    activeScriptStatements.add(statement);
                    try {
                        for (SqlScriptPlan.Step step : plan.steps()) {
                            checkCancelled();
                            inFlight = step.ordinal();
                            executionStarted = true;
                            log.info("Execute migration script phase={}, digest={}, statement={}/{}",
                                    phase, digest, inFlight, total);
                            try {
                                statement.execute(step.sql());
                            } catch (SQLException executeFailure) {
                                // PostgreSQL 的 ErrorResponse 57014 确认此自动提交语句
                                // 对于普通 SQL 已取消或回滚；ANALYZE 可能保留
                                // 非事务性的优化器提示，因此维护操作仍为 UNKNOWN。
                                // 传输失败，或成功执行后在耗尽或关闭结果时
                                // 发生失败，都不能提供上述证据。
                                statementCancellationConfirmed = product == ProductTypeEnum.POSTGRESQL
                                        && step.kind() != cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier.Kind.OTHER
                                        && "57014".equals(executeFailure.getSQLState());
                                throw executeFailure;
                            }
                            // 将自动提交语句视为已确认前，关闭或耗尽其结果。
                            while (true) {
                                try (var resultSet = statement.getResultSet()) { /* 关闭所有结果 */ }
                                if (!statement.getMoreResults(Statement.CLOSE_ALL_RESULTS)
                                        && statement.getUpdateCount() == -1) {
                                    break;
                                }
                            }
                            acknowledged++;
                            inFlight = 0;
                        }
                        checkCancelled();
                    } finally {
                        activeScriptStatements.remove(statement);
                    }
                }
            }
            scriptExecutions.add(new ScriptExecution(phase, digest, total, acknowledged, 0,
                    ScriptState.SUCCEEDED, null, 0));
        } catch (Exception failure) {
            ScriptState state = statementCancellationConfirmed
                    ? acknowledged > 0 ? ScriptState.PARTIAL : ScriptState.CANCELLED
                    : inFlight > 0 ? ScriptState.UNKNOWN
                    : acknowledged > 0 ? ScriptState.PARTIAL
                    : failure instanceof CancellationException ? ScriptState.CANCELLED : ScriptState.REJECTED;
            if (inFlight > 0 && !statementCancellationConfirmed) {
                externalWriteUnconfirmed = true;
            }
            if (failure.getSuppressed().length > 0) {
                cleanupConfirmed = false;
            }
            if (executionStarted && inFlight == 0 && !(failure instanceof CancellationException)) {
                // 执行已确认后，关闭语句或连接失败。
                cleanupConfirmed = false;
            }
            String sqlState = failure instanceof SQLException jdbc
                    && jdbc.getSQLState() != null && jdbc.getSQLState().matches("[A-Z0-9]{5}")
                    ? jdbc.getSQLState() : null;
            int vendorCode = failure instanceof SQLException jdbc ? jdbc.getErrorCode() : 0;
            ScriptExecution evidence = new ScriptExecution(phase, digest, total, acknowledged,
                    inFlight, state, sqlState, vendorCode);
            scriptExecutions.add(evidence);
            log.error("Migration script failed: {}", evidence);
            if (failure instanceof CancellationException || interrupted || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Migration script cancelled: " + evidence);
            }
            // 不保留驱动或解析器的原始原因，它们通常内嵌 SQL 和字面值。
            throw new ScriptExecutionException(evidence);
        }
    }

    /**
     * 异常处理函数方法：单表迁移抛异常时记录日志、累加失败计数，
     * 并构造一个标记为失败（含错误信息与目标表名映射）的表结果返回，保证统计流程不中断
     *
     * @param td 表描述上下文
     * @return Function<Throwable, Long>
     */
    private Function<Throwable, DbSwitchTableResult> getExceptHandler(
            TableDescription td,
            Integer indexInternal, AtomicInteger numberOfFailures, MigrationHandler instance) {
        return (e) -> {
            recordUnconfirmedWrite(e);
            SourceDataSourceProperties sourceProperties = properties.getSource().get(indexInternal);
            String sourceObject = SourceType.SQL.getCode().equals(sourceProperties.getSourceType())
                    ? "SQL[" + SqlScriptPlan.digest(sourceProperties.getSourceSql()) + "]" : td.getTableName();
            String diagnostic = "Migration failed; type=" + e.getClass().getSimpleName()
                    + "; externalWriteUnconfirmed=" + externalWriteUnconfirmed;
            // 驱动异常链可能内嵌 SQL、数据值和连接凭据。
            log.error("Error migration for source index={}, object={}.{}, {}", indexInternal,
                    td.getSchemaName(), sourceObject, diagnostic);

            numberOfFailures.getAndIncrement();

            TargetDataSourceProperties target = properties.getTarget();
            String targetSchemaName = target.getTargetSchema();
            String targetTableName = sourceProperties.getSourceType() == 2 ? target.getTargetTable() : PatterNameUtils.getFinalName(
                    td.getTableName(), sourceProperties.getRegexTableMapper());
            DbSwitchTableResult partial = instance.partialResult();
            DbSwitchTableResult dbSwitchTableResult = partial == null ? new DbSwitchTableResult() : partial;
            dbSwitchTableResult.setSourceSchemaName(td.getSchemaName());
            dbSwitchTableResult.setSourceTableName(sourceObject);
            dbSwitchTableResult.setTargetSchemaName(targetSchemaName);
            dbSwitchTableResult.setTargetTableName(targetTableName);
            dbSwitchTableResult.getIfSuccess().set(false);
            dbSwitchTableResult.setSyncTime(new Date());
            dbSwitchTableResult.setSuccessMsg(null);
            dbSwitchTableResult.setErrorMsg(diagnostic);
            return dbSwitchTableResult;
        };
    }

    /** try-with-resources 可能把写入异常放入 suppressed，不能只遍历 getCause。 */
    private void recordUnconfirmedWrite(Throwable failure) {
        var pending = new ArrayDeque<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(failure);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            if (current instanceof UnconfirmedWriteException
                    || current instanceof cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException partial
                    && (partial.unknownRows() > 0 || !partial.terminationConfirmed())) {
                externalWriteUnconfirmed = true;
                return;
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
        }
    }

}
