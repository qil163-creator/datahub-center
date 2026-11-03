package cn.iocoder.yudao.module.dataforge.service.access;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.seatunnel.NativeSeatunnelConfig;
import cn.iocoder.yudao.framework.dbswitch.common.constant.ChangeDataSyncType;
import cn.iocoder.yudao.framework.dbswitch.common.constant.MapperType;
import cn.iocoder.yudao.framework.dbswitch.common.entity.MapperConfig;
import cn.iocoder.yudao.framework.dbswitch.common.type.SourceType;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigMapper;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.util.BytesUnitUtils;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataTransUtils;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.ApiAccessMappingVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.DataApiSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.ResponseColumnItem;
import cn.iocoder.yudao.module.dataforge.service.dataapi.DataApiClient;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDetailDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessTaskDetailMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessTaskMapper;
import cn.iocoder.yudao.module.dataforge.enums.access.AccessEngineTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.access.AccessReleaseStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.access.AccessRunStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.access.AccessTaskTypeEnum;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 数据同步任务执行器
 *
 * 按同步引擎分派：
 * - 离线同步（DBSwitch）：参考 srt-cloud 的 DbswitchTaskAdapter，调用 {@link MigrationService} 执行库表迁移
 * - 实时同步（Seatunnel）：参考 srt-cloud 的 SeatunnelTaskAdapter，提交作业到 Seatunnel 集群并轮询状态
 *
 * @author Li
 */
@Slf4j
@Component
public class DataAccessExecutor {

    /**
     * Seatunnel 作业状态轮询间隔
     */
    private static final long SEATUNNEL_POLL_INTERVAL_MS = 3000;

    @Resource
    private DbSwitchRuntime dbSwitchRuntime;

    @Resource
    private DataAccessMapper dataAccessMapper;

    @Resource
    private DataAccessTaskMapper taskMapper;

    @Resource
    private DataAccessTaskDetailMapper taskDetailMapper;

    @Resource
    private AccessCursorCheckpointStore cursorCheckpointStore;

    @Resource
    private AccessGroupRewriteCoordinator groupRewriteCoordinator;

    @Resource
    private AccessDatabaseResolver databaseResolver;

    @Resource
    private DataApiClient dataApiClient;

    @Resource
    private DatabaseQuerySupport databaseQuerySupport;

    @Resource
    private AccessMigrationAuthorization migrationAuthorization;

    private ExecutorService executor;

    @Resource
    private AccessRunCoordinator coordinator;

    @Resource
    private AccessRunAuthorization authorization;

    @Resource
    private cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties properties;

    private final String nodeInstanceId = java.util.UUID.randomUUID().toString();

    private java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(4);

    private final ConcurrentMap<String, RunningTask> runningTasks = new ConcurrentHashMap<>();

    private java.util.concurrent.ScheduledExecutorService monitor;

    private static class RunningTask {

        private cn.iocoder.yudao.module.dataforge.service.execution.RunLease lease;

        private volatile Thread thread;

        private volatile MigrationService migrationService;

        private volatile AccessGroupRewriteCoordinator.Handle groupRewriteHandle;

        private Long groupRecoveryRecordId;

        private String groupRecoveryToken;

        private PreparedGroupRecovery groupRecoveryPrepared;

        private AccessGroupRewriteCoordinator.RecoveryHandle groupRecoveryHandle;

        private volatile java.util.concurrent.CompletableFuture<Void> groupRecoveryExit;

        private volatile boolean groupRecoveryExecutionReturned, groupRecoveryPending;

        private final java.util.concurrent.atomic.AtomicBoolean groupRecoveryFinalizing = new java.util.concurrent.atomic.AtomicBoolean();

        private final java.util.concurrent.atomic.AtomicBoolean groupRecoveryLocalReleased = new java.util.concurrent.atomic.AtomicBoolean();

        private volatile java.util.concurrent.CompletableFuture<Void> drainExit;

        private volatile boolean engineStarted, executionReturned, drainPending;

        private final java.util.concurrent.atomic.AtomicBoolean drainFinalizing = new java.util.concurrent.atomic.AtomicBoolean();

        private final java.util.concurrent.atomic.AtomicBoolean localReleased = new java.util.concurrent.atomic.AtomicBoolean();

        private volatile boolean cancelSignalled;

        private volatile boolean remoteTerminated;

        private volatile boolean apiTerminated;

        private volatile boolean apiCallUncertain;

        private volatile boolean active = true;

        private long lastRenewNanos = System.nanoTime();
    }

    /**
     * 初始化数据同步任务。
     */
    @PostConstruct
    public void init() {
        AtomicInteger counter = new AtomicInteger();
        int workers = properties.getWorkerConcurrency();
        capacity = new java.util.concurrent.Semaphore(workers);
        java.util.concurrent.BlockingQueue<Runnable> queue = properties.getWorkerQueue() == 0
                ? new java.util.concurrent.SynchronousQueue<>() : new java.util.concurrent.ArrayBlockingQueue<>(
                properties.getWorkerQueue());
        executor = new java.util.concurrent.ThreadPoolExecutor(workers, workers, 0,
                java.util.concurrent.TimeUnit.SECONDS,
                queue, runnable -> {
            Thread thread = new Thread(runnable, "data-access-sync-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        // 仅用于每个节点的内部维护；持久化 XXL-Job 扫描负责派发及恢复。
        monitor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "data-access-control");
            thread.setDaemon(true);
            return thread;
        });
        monitor.scheduleWithFixedDelay(this::monitorLocalRuns, 0, properties.getStopPoll().toSeconds(),
                java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 在组件销毁时释放持有的运行资源。
     */
    @PreDestroy
    public void destroy() {
        if (monitor != null) {
            monitor.shutdownNow();
        }
        runningTasks.values().forEach(this::signal);
        if (executor != null) {
            executor.shutdownNow();
        }
        // 不能根据中断或关闭推断终态；节点失联由租约恢复机制处理。
    }

    /**
     * 判断当前执行是否仍处于运行状态。
     *
     * @param taskId 任务编号
     * @return 判断结果
     */
    public boolean isRunning(Long taskId) {
        return taskMapper.selectCount(Wrappers.lambdaQuery(DataAccessTaskDO.class)
                .eq(DataAccessTaskDO::getDataAccessId, taskId)
                .and(q -> q.eq(DataAccessTaskDO::getOccupiesSlot, true).or()
                        .eq(DataAccessTaskDO::getRunStatus, 1))) > 0;
    }

    /** 仅持久化记录可进入引擎；在数据库抢占之前预留容量。 */
    public boolean submit(Long recordId) {
        if (!capacity.tryAcquire()) {
            return false;
        }
        RunningTask handle = new RunningTask();
        String key = localKey(recordId);
        boolean registered = false;
        try {
            var lease = coordinator.claim(recordId, nodeInstanceId);
            if (lease.isEmpty()) {
                capacity.release();
                return false;
            }
            handle.lease = lease.get();
            if (runningTasks.putIfAbsent(key, handle) != null) {
                coordinator.releaseRejected(handle.lease);
                capacity.release();
                return false;
            }
            registered = true;
            executor.submit(() -> {
                try {
                    execute(handle);
                } finally {
                    handle.executionReturned = true;
                    if (!handle.drainPending) {
                        releaseLocal(handle);
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            if (registered) {
                runningTasks.remove(key, handle);
            }
            capacity.release();
            if (handle.lease != null) {
                coordinator.releaseRejected(handle.lease);
            }
            return false;
        } catch (RuntimeException failure) {
            if (registered) {
                runningTasks.remove(key, handle);
            }
            capacity.release();
            throw failure;
        }
    }

    /** 仅在内存中准入；连接材料绝不持久化，也不通过 toString() 暴露。 */
    public static final class PreparedGroupRecovery {

        private final Long recordId;

        private final String token, snapshotJson, sourceConnectionIdentity, targetConnectionIdentity;

        private final AccessGroupRewriteCoordinator.RecoveryAdmission admission;

        private final cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers.Binding sourceDriver, targetDriver;

        private final TargetDataSourceProperties target;

        private final java.util.concurrent.atomic.AtomicBoolean dispatched = new java.util.concurrent.atomic.AtomicBoolean();

        /**
         * 创建已准备分组恢复实例。
         *
         * @param recordId 记录编号
         * @param token 当前执行或租约令牌
         * @param snapshotJson 快照JSON
         * @param sourceIdentity 来源身份
         * @param targetIdentity 目标身份
         * @param admission 准入
         * @param sourceDriver 来源驱动
         * @param targetDriver 目标驱动
         * @param target 目标数据
         */
        private PreparedGroupRecovery(Long recordId, String token, String snapshotJson,
                String sourceIdentity, String targetIdentity, AccessGroupRewriteCoordinator.RecoveryAdmission admission,
                cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers.Binding sourceDriver,
                cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers.Binding targetDriver,
                TargetDataSourceProperties target) {
            this.recordId = recordId;
            this.token = token;
            this.snapshotJson = snapshotJson;
            this.sourceConnectionIdentity = sourceIdentity;
            this.targetConnectionIdentity = targetIdentity;
            this.admission = admission;
            this.sourceDriver = sourceDriver;
            this.targetDriver = targetDriver;
            this.target = target.copy();
        }

        /**
         * 返回已准备分组恢复的准入。
         *
         * @return 恢复准入
         */
        public AccessGroupRewriteCoordinator.RecoveryAdmission admission() {
            return admission;
        }

        /**
         * 返回当前对象的字符串表示，供日志或展示使用。
         *
         * @return 当前对象的字符串表示，供日志或展示使用文本
         */
        @Override
        public String toString() {
            return "PreparedGroupRecovery[redacted]";
        }
    }

    /** 解析受管配置并校验部署产物，不打开源端连接。 */
    public PreparedGroupRecovery prepareGroupRecovery(DataAccessDO frozen, DataAccessTaskDO observed, String token) {

        if (frozen == null || observed == null || !java.util.Objects.equals(frozen.getId(),
                observed.getDataAccessId())) {
            throw new IllegalArgumentException("Group recovery scope is invalid");
        }
        var snapshot = AccessRunSnapshot.restore(observed.getConfigJson());
        MigrationRequest request = recoveryRequest(snapshot);
        var source = request.getSource().getFirst();
        var target = request.getTarget();
        var sourceConnection = fillSourceConnection(source, snapshot);
        var targetConnection = fillTargetConnection(target, snapshot);
        var admission = groupRewriteCoordinator.prepareRecovery(snapshot, observed, token, source, target,
                sourceConnection, targetConnection);
        var sourceDriver = dbSwitchRuntime.drivers().find(source.getSourceProductType()).orElseThrow();
        var targetDriver = dbSwitchRuntime.drivers().find(target.getTargetProductType()).orElseThrow();
        sourceDriver.verifyUnchanged();
        targetDriver.verifyUnchanged();
        return new PreparedGroupRecovery(observed.getId(), token, observed.getConfigJson(),
                recoveryConnectionIdentity(sourceConnection), recoveryConnectionIdentity(targetConnection),
                admission, sourceDriver, targetDriver, target);
    }

    /**
     * 获取数据同步任务的恢复请求。
     *
     * @param snapshot 快照
     * @return 迁移请求
     */
    private static MigrationRequest recoveryRequest(DataAccessDO snapshot) {
        if (!AccessEngineTypeEnum.DBSWITCH.getType()
                .equals(snapshot.getEngineType()) || snapshot.getDataAccessJson() == null) {
            throw new IllegalArgumentException("Group recovery requires a frozen database task");
        }
        var request = AccessTaskConfigMapper.toRuntime(snapshot.getDataAccessJson(), snapshot.getTargetDatabaseId());
        if (request.getSource() == null || request.getSource().size() != 1 || request.getTarget() == null) {
            throw new IllegalArgumentException("Group recovery requires one managed source");
        }
        var source = request.getSource().getFirst();
        // 授权可检查此固定集合，但恢复流程绝不得发现或读取源端。
        if (!SourceType.TABLE.getCode().equals(source.getSourceType()) || !Integer.valueOf(1)
                .equals(source.getIncludeOrExclude())
                || AccessExactTableSelection.selectedNames(source).isEmpty()) {
            throw new IllegalArgumentException("Group recovery requires frozen source table names");
        }
        return request;
    }

    /**
     * 获取数据同步任务的恢复连接身份。
     *
     * @param connection 数据库连接
     * @return 数据同步任务的恢复连接身份文本
     */
    private static String recoveryConnectionIdentity(AccessDatabaseResolver.ConnectionInfo connection) {
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(JSONUtil.toJsonStr(java.util.Arrays.asList(
                connection.getDatabaseId(), connection.getProductType(), connection.getConfigurationRevision(),
                connection.getJdbcUrl(), connection.getSchema(), connection.getUsername())));
    }

    /** 准入事务提交后，至多进行一次本地派发；持久化校验负责跨节点互斥。 */
    public boolean submitGroupRecovery(Long recordId, String token, Long actor, PreparedGroupRecovery prepared,
            AccessGroupRewriteCoordinator.RecoveryHandle recovery) {

        if (prepared == null || recovery == null || actor == null
                || !java.util.Objects.equals(recordId, prepared.recordId) || !java.util.Objects.equals(token,
                prepared.token)) {
            throw new IllegalArgumentException("Group recovery dispatch scope is invalid");
        }
        if (!prepared.dispatched.compareAndSet(false, true)) {
            return false;
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new IllegalStateException("Group recovery requires transaction completion observation");
            }
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        /**
                         * 在本地事务成功提交后触发对应的后续处理。
                         */
                        @Override
                        public void afterCommit() {
                            dispatchGroupRecovery(actor, prepared, recovery);
                        }
                    });
            return true;
        }
        return dispatchGroupRecovery(actor, prepared, recovery);
    }

    /**
     * 派发分组恢复。
     *
     * @param actor 操作人编号
     * @param prepared 已准备的执行配置或记录
     * @param recovery 恢复
     * @return 处理是否成功
     */
    private boolean dispatchGroupRecovery(Long actor, PreparedGroupRecovery prepared,
            AccessGroupRewriteCoordinator.RecoveryHandle recovery) {
        String key = localKey(prepared.recordId);
        // admitRecovery 在发放新句柄之前，已认证旧令牌对应的执行是否完全退出。
        // 旧工作线程在持久化完成后，可能仍在移除本地条目。
        // 此 Prepared 尚未传入 executor.submit，仅确认其自身未进行 I/O 的退出事实。
        if (runningTasks.containsKey(key)) {
            finishGroupRecovery(prepared, recovery, true);
            return false;
        }
        if (!capacity.tryAcquire()) {
            finishGroupRecovery(prepared, recovery, true);
            return false;
        }
        var handle = new RunningTask();
        handle.groupRecoveryRecordId = prepared.recordId;
        handle.groupRecoveryToken = prepared.token;
        handle.groupRecoveryPrepared = prepared;
        handle.groupRecoveryHandle = recovery;
        if (runningTasks.putIfAbsent(key, handle) != null) {
            capacity.release();
            finishGroupRecovery(prepared, recovery, true);
            return false;
        }
        try {
            executor.submit(() -> {
                try {
                    executeGroupRecovery(handle, actor, prepared, recovery);
                } finally {
                    handle.groupRecoveryExecutionReturned = true;
                    if (!handle.groupRecoveryPending) {
                        releaseGroupRecoveryLocal(handle);
                    }
                }
            });
            return true;
        } catch (RuntimeException rejected) {
            runningTasks.remove(key, handle);
            capacity.release();
            finishGroupRecovery(prepared, recovery, true); // 本进程从未提交目标端工作。
            return false;
        }
    }

    /**
     * 执行分组恢复。
     *
     * @param handle 句柄
     * @param actor 操作人编号
     * @param prepared 已准备的执行配置或记录
     * @param recovery 恢复
     */
    private void executeGroupRecovery(RunningTask handle, Long actor, PreparedGroupRecovery prepared,
            AccessGroupRewriteCoordinator.RecoveryHandle recovery) {
        var originalSecurity = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var security = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        security.setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                        new cn.iocoder.yudao.framework.security.core.LoginUser().setId(actor)
                                .setUserType(cn.iocoder.yudao.framework.common.enums.UserTypeEnum.ADMIN.getValue()),
                        null, List.of()));
        org.springframework.security.core.context.SecurityContextHolder.setContext(security);
        handle.thread = Thread.currentThread();
        com.zaxxer.hikari.HikariDataSource pool = null;
        java.util.concurrent.CompletionStage<Void> physicalExit = null;
        boolean targetStarted = false, terminated = false;
        try {
            var snapshot = AccessRunSnapshot.restore(prepared.snapshotJson);
            var request = recoveryRequest(snapshot);
            authorization.action(snapshot, actor, "dataforge:access:resolve");
            authorization.execution(snapshot, actor);
            var currentSource = fillSourceConnection(request.getSource().getFirst(), snapshot);
            var currentTarget = fillTargetConnection(request.getTarget(), snapshot);
            if (!prepared.sourceConnectionIdentity.equals(recoveryConnectionIdentity(currentSource))
                    || !prepared.targetConnectionIdentity.equals(recoveryConnectionIdentity(currentTarget))) {
                throw new IllegalStateException("Managed recovery connection configuration changed");
            }
            prepared.sourceDriver.verifyUnchanged();
            prepared.targetDriver.verifyUnchanged();
            groupRecoveryBoundary(handle, recovery);
            var tables = recovery.pendingTables(); // 打开任何目标端连接池之前，先认证完整集合。
            for (var table : tables) {
                if (!java.util.Objects.equals(table.schema(), prepared.target.getTargetSchema())) {
                    throw new IllegalStateException("Recovery target schema differs from its managed binding");
                }
                cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.qualifiedTableName(
                        prepared.target.getTargetProductType(), table.schema(), table.table());
            }
            if (!tables.isEmpty()) {
                groupRecoveryBoundary(handle, recovery);
                targetStarted = true; // 构建连接池可能启动后台物理连接。
                long connectionBudget = Math.max(1000L,
                        Math.min(120_000L, dbSwitchRuntime.options().queryTimeoutSeconds() * 1000L));
                pool = DataSourceUtils.createTargetDataSource(prepared.target.copy(), null, null, prepared.targetDriver,
                        dbSwitchRuntime.drivers().runtimes(), dbSwitchRuntime.options(), connectionBudget);
                physicalExit = cn.iocoder.yudao.framework.dbswitch.driver.DriverPoolFactory.physicalTermination(pool);
                for (var table : tables) {
                    groupRecoveryBoundary(handle, recovery);
                    var sync = dbSwitchRuntime.synchronizer(pool, prepared.target.getTargetProductType());
                    sync.configureBatchLimits(table.binding().limits());
                    sync.bindGroupRewrite(table.binding());
                    sync.prepare(table.schema(), table.table(), table.fields(), table.primaryKeys());
                }
            }
        } catch (Throwable failure) {
            log.warn("ACCESS 组恢复未收敛 record={} error={}", prepared.recordId, failure.getClass().getSimpleName());
        } finally {
            if (pool != null) {
                try {
                    pool.close();
                } catch (RuntimeException failure) {
                    log.warn("ACCESS 组恢复连接池关闭待核查 record={}", prepared.recordId);
                }
            }
            try {
                if (!targetStarted) {
                    terminated = true;
                } else if (physicalExit != null) {
                    physicalExit.toCompletableFuture()
                            .get(properties.getStopPoll().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                    terminated = true;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException |
                     java.util.concurrent.TimeoutException unconfirmed) { /* 保留固定记录与占用。 */ }
            synchronized (handle) {
                handle.active = false;
                handle.thread = null;
            }
            boolean interrupted = Thread.interrupted();
            try {
                handle.groupRecoveryExit = terminated ? java.util.concurrent.CompletableFuture.completedFuture(null)
                        : physicalExit == null ? null : physicalExit.toCompletableFuture();
                handle.groupRecoveryPending = !finishGroupRecovery(prepared, recovery, terminated);
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.setContext(originalSecurity);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * 确认表组恢复仍持有执行权，响应取消信号、线程中断及恢复租约失效。
     *
     * @param handle 句柄
     * @param recovery 恢复
     */
    private void groupRecoveryBoundary(RunningTask handle, AccessGroupRewriteCoordinator.RecoveryHandle recovery) {
        if (handle.cancelSignalled || Thread.currentThread().isInterrupted()
                || coordinator.shouldStopGroupRecovery(handle.groupRecoveryRecordId, handle.groupRecoveryToken)) {
            throw new IllegalStateException("Group recovery no longer owns execution");
        }
        recovery.assertOwnership();
    }

    /**
     * 结束分组恢复。
     *
     * @param prepared 已准备的执行配置或记录
     * @param recovery 恢复
     * @param terminated 已终止
     * @return 处理是否成功
     */
    private boolean finishGroupRecovery(PreparedGroupRecovery prepared,
            AccessGroupRewriteCoordinator.RecoveryHandle recovery, boolean terminated) {
        boolean applied = false;
        try {
            applied = recovery.finished(terminated);
        } catch (RuntimeException unavailable) {
            log.warn("ACCESS 组恢复占位待核查 record={}", prepared.recordId);
        }
        // 保留令牌，直到控制数据库确认每条固定记录对应的执行已完全退出。
        boolean durableDrain = terminated && (applied || recovery.hasConfirmedExecutorDrain());
        try {
            coordinator.finishGroupRecovery(prepared.recordId, prepared.token, durableDrain, applied);
            return durableDrain;
        } catch (RuntimeException unavailable) {
            log.warn("ACCESS 组恢复完成状态待核查 record={}", prepared.recordId);
            return false;
        }
    }

    /**
     * 释放分组恢复本地。
     *
     * @param handle 句柄
     */
    private void releaseGroupRecoveryLocal(RunningTask handle) {
        if (handle.groupRecoveryLocalReleased.compareAndSet(false, true)) {
            runningTasks.remove(localKey(handle.groupRecoveryRecordId), handle);
            capacity.release();
        }
    }

    /** 驱动终止回调可能持有驱动锁，绝不在其中执行平台 I/O。 */
    private void observeGroupRecoveryExit(RunningTask handle) {
        var exit = handle.groupRecoveryExit;
        if (!handle.groupRecoveryExecutionReturned || exit == null || !exit.isDone() || exit.isCompletedExceptionally()
                || !handle.groupRecoveryFinalizing.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    if (finishGroupRecovery(handle.groupRecoveryPrepared, handle.groupRecoveryHandle, true)) {
                        handle.groupRecoveryPending = false;
                        releaseGroupRecoveryLocal(handle);
                    }
                } finally {
                    handle.groupRecoveryFinalizing.set(false);
                }
            });
        } catch (RuntimeException saturated) {
            handle.groupRecoveryFinalizing.set(false);
        }
    }

    /** 引擎自身不可伪造的退出回执；若引擎从未调用 run()，就没有执行需要退出。 */
    private static java.util.concurrent.CompletableFuture<Void> engineExit(RunningTask handle) {
        if (!handle.engineStarted) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        var receipt = handle.migrationService.physicalExit();
        return receipt == null ? null : receipt.toCompletableFuture();
    }

    /**
     * 判断已确认条件是否成立。
     *
     * @param exit 退出
     * @return 处理是否成功
     */
    private static boolean confirmed(java.util.concurrent.CompletableFuture<Void> exit) {
        return exit != null && exit.isDone() && !exit.isCompletedExceptionally();
    }

    /** 仅保留的运行时句柄可签名；每条固定记录的退出事实持久确认后，本地槽位才结束占用。 */
    private boolean finishDrain(RunningTask handle, boolean exited) {
        boolean released = false;
        try {
            released = handle.groupRewriteHandle.finished(exited);
        } catch (RuntimeException recoveryUnavailable) {
            log.warn("ACCESS 恢复占位待核查 record={}", handle.lease.recordId());
        }
        handle.drainPending = !exited || !released && !handle.groupRewriteHandle.hasConfirmedExecutorDrain();
        return released;
    }

    /**
     * 释放本地。
     *
     * @param handle 句柄
     */
    private void releaseLocal(RunningTask handle) {
        if (handle.localReleased.compareAndSet(false, true)) {
            runningTasks.remove(localKey(handle.lease.recordId()), handle);
            capacity.release();
        }
    }

    /** 回执回调不进行平台 I/O；现有有容量上限的执行器仅重试退出确认签名。 */
    private void observeDrain(RunningTask handle) {
        if (!confirmed(handle.drainExit) || !handle.drainFinalizing.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    finishDrain(handle, true);
                    if (!handle.drainPending) {
                        releaseLocal(handle);
                    }
                } finally {
                    handle.drainFinalizing.set(false);
                }
            });
        } catch (RuntimeException saturated) {
            handle.drainFinalizing.set(false);
        }
    }

    /**
     * 停止数据同步任务执行。
     *
     * @param taskId 任务编号
     * @return 处理是否成功
     */
    public boolean stop(Long taskId) {
        DataAccessTaskDO target = taskMapper.selectOne(Wrappers.lambdaQuery(DataAccessTaskDO.class)
                .eq(DataAccessTaskDO::getDataAccessId, taskId)
                .in(DataAccessTaskDO::getRunStatus, 1, 2, 5).orderByDesc(DataAccessTaskDO::getOccupiesSlot)
                .orderByAsc(DataAccessTaskDO::getId).last("LIMIT 1"));
        if (target == null) {
            return false;
        }
        coordinator.requestStop(target.getId(), java.util.UUID.randomUUID().toString(), "兼容停止入口");
        return true;
    }

    /**
     * 监测本地运行。
     */
    private void monitorLocalRuns() {
        for (RunningTask handle : runningTasks.values()) {
            if (handle.groupRecoveryPending && handle.groupRecoveryExecutionReturned) {
                observeGroupRecoveryExit(handle);
                continue;
            }
            if (handle.drainPending && handle.executionReturned) {
                observeDrain(handle);
                continue;
            }
            if (!handle.active) {
                continue;
            }
            try {
                {
                    boolean recovery = handle.groupRecoveryRecordId != null;
                    boolean stop = recovery ? coordinator.shouldStopGroupRecovery(handle.groupRecoveryRecordId,
                            handle.groupRecoveryToken)
                            : coordinator.shouldStop(handle.lease);
                    long now = System.nanoTime();
                    if (!stop && now - handle.lastRenewNanos >= properties.getHeartbeat().toNanos()) {
                        stop = recovery ? !coordinator.renewGroupRecovery(handle.groupRecoveryRecordId,
                                handle.groupRecoveryToken)
                                : !coordinator.renew(handle.lease);
                        handle.lastRenewNanos = now;
                    }
                    if (stop) {
                        signal(handle);
                    }
                }
                ;
            } catch (RuntimeException unavailable) {
                signal(handle); // 平台失联时按拒绝原则处理；失去归属权后绝不继续启动分页操作。
                log.warn("ACCESS 控制监测失败 record={}",
                        handle.groupRecoveryRecordId == null ? handle.lease.recordId() : handle.groupRecoveryRecordId);
            }
        }
    }

    /**
     * 发送信号以数据同步任务。
     *
     * @param handle 句柄
     */
    private void signal(RunningTask handle) {
        synchronized (handle) {
            if (!handle.active) {
                return;
            }
            handle.cancelSignalled = true;
            if (handle.migrationService != null) {
                handle.migrationService.interrupt();
            }
            if (handle.thread != null) {
                handle.thread.interrupt();
            }
        }
    }

    /**
     * 获取数据同步任务的本地键。
     *
     * @param record 运行记录
     * @return 数据同步任务的本地键文本
     */
    private static String localKey(Long record) {
        return record.toString();
    }

    /**
     * 执行数据同步任务处理。
     *
     * @param handle 句柄
     */
    private void execute(RunningTask handle) {
        DataAccessTaskDO task = coordinator.requireRun(handle.lease.recordId());
        var originalSecurity = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var security = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        security.setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                        new cn.iocoder.yudao.framework.security.core.LoginUser().setId(task.getExecutionUserId())
                                .setUserType(cn.iocoder.yudao.framework.common.enums.UserTypeEnum.ADMIN.getValue()),
                        null, List.of()));
        org.springframework.security.core.context.SecurityContextHolder.setContext(security);
        handle.thread = Thread.currentThread();
        StringBuffer logText = new StringBuffer();
        AtomicLong rows = new AtomicLong(), bytes = new AtomicLong(), successes = new AtomicLong(), failures = new AtomicLong();
        boolean effects = false;
        boolean seatunnel = false;
        boolean api = false;
        try {
            DataAccessDO snapshot = AccessRunSnapshot.restore(task.getConfigJson());
            snapshot.setCreator(String.valueOf(task.getExecutionUserId()));
            seatunnel = AccessEngineTypeEnum.SEATUNNEL.getType().equals(snapshot.getEngineType());
            api = AccessEngineTypeEnum.API_ACCESS.getType().equals(snapshot.getEngineType());
            authorization.execution(snapshot, task.getExecutionUserId());
            // 原文校验失败属于准备失败，不能误记为已发送作业。
            String seatunnelConfig = seatunnel && StrUtil.isBlank(task.getSeatunnelJobId())
                    ? remoteConfig(snapshot) : null;
            if (handle.cancelSignalled || coordinator.shouldStop(handle.lease)) {
                coordinator.releaseBeforeExecution(handle.lease, "执行前取消");
                return;
            }
            var stage = seatunnel ? cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Stage.SUBMIT_SENT
                    : cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Stage.EXECUTION_STARTED;
            if (!coordinator.startEffects(handle.lease, stage)) {
                coordinator.releaseBeforeExecution(handle.lease, "未获有效执行许可");
                return;
            }
            effects = true;
            task.setExecutionStage(stage.name());
            if (seatunnel) {
                executeSeatunnel(snapshot, task, logText, seatunnelConfig);
            } else if (api) {
                executeApiAccess(snapshot, task, logText, rows, successes, failures);
            } else {
                executeDbswitch(snapshot, task, handle, logText, rows, bytes, successes, failures);
            }
        } catch (Throwable failure) {
            task.setRunStatus(effects ? 5 : 4);
            task.setErrorInfo("执行未收敛：" + failure.getClass().getSimpleName());
            appendLog(logText, task.getErrorInfo());
            log.warn("ACCESS 执行未收敛 task={} record={} stage={} error={}", task.getDataAccessId(), task.getId(),
                    task.getExecutionStage(), failure.getClass().getSimpleName());
        } finally {
            // 引擎调用及清理已返回。即使过期监控已看到旧本地注册条目，也不得在 finish 清除令牌后
            // 中断终态的持久化。
            synchronized (handle) {
                handle.active = false;
                handle.thread = null;
            }
            // 仅在短暂的权威状态持久化期间清除中断标志，随后恢复。
            boolean interrupted = Thread.interrupted();
            try {
                boolean terminated = !effects || (seatunnel ? handle.remoteTerminated : api ? handle.apiTerminated
                        : handle.migrationService != null && handle.migrationService.termination()
                        == cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination.CONFIRMED);
                boolean recoveryReleased = true;
                if (handle.groupRewriteHandle != null) {
                    // 固定记录保存执行器的实际退出事实；运行本身保留引擎对写入结果的保守判断。
                    handle.drainExit = terminated ? java.util.concurrent.CompletableFuture.completedFuture(
                            null) : engineExit(handle);
                    recoveryReleased = finishDrain(handle, confirmed(handle.drainExit));
                }
                if (effects && terminated && !Integer.valueOf(3).equals(task.getRunStatus())) {
                    task.setRunStatus(handle.cancelSignalled || Integer.valueOf(6).equals(task.getRunStatus()) ? 6 : 4);
                }
                if (!terminated || !recoveryReleased) {
                    task.setRunStatus(5);
                }
                var effect = !effects ? cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.NONE
                        : !terminated || !recoveryReleased ? cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.UNKNOWN
                        : Integer.valueOf(3).equals(task.getRunStatus())
                        ? cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.COMPLETE
                        : cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.PARTIAL;
                if (task.getRunStatus() == null || task.getRunStatus() == 1 || task.getRunStatus() == 2) {
                    task.setRunStatus(4);
                }
                task.setRealTimeLog(logText.toString());
                coordinator.finish(handle.lease, task, terminated, effect);
            } catch (RuntimeException persistenceFailure) {
                log.warn("ACCESS 终态持久化待恢复 record={}", task.getId());
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.setContext(originalSecurity);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * 获取数据同步任务的租约。
     *
     * @param task 任务记录
     * @return 运行租约
     */
    private cn.iocoder.yudao.module.dataforge.service.execution.RunLease lease(DataAccessTaskDO task) {
        return new cn.iocoder.yudao.module.dataforge.service.execution.RunLease(task.getId(), task.getExecutionToken(),
                task.getLeaseUntil());
    }

    /**
     * 检查当前同步运行的取消状态和租约，在失去执行权时中止处理。
     *
     * @param task 任务记录
     */
    private void boundary(DataAccessTaskDO task) {
        if (Thread.currentThread().isInterrupted() || coordinator.shouldStop(lease(task))) {
            throw new IllegalStateException("执行已取消或租约无效");
        }
    }

    /**
     * 持久化进度。
     *
     * @param task 任务记录
     */
    private void persistProgress(DataAccessTaskDO task) {
        boolean written = coordinator.writeOwned(lease(task), () -> writeProgress(task));
        if (!written) {
            throw new IllegalStateException("运行进度租约已失效");
        }
    }

    /**
     * 写入进度。
     *
     * @param task 任务记录
     */
    private void writeProgress(DataAccessTaskDO task) {
        taskMapper.update(null, Wrappers.lambdaUpdate(DataAccessTaskDO.class)
                .eq(DataAccessTaskDO::getId, task.getId())
                .set(DataAccessTaskDO::getDataCount, task.getDataCount())
                .set(DataAccessTaskDO::getTableSuccessCount, task.getTableSuccessCount())
                .set(DataAccessTaskDO::getTableFailCount, task.getTableFailCount())
                .set(DataAccessTaskDO::getByteCount, task.getByteCount())
                .set(DataAccessTaskDO::getRealTimeLog, task.getRealTimeLog())
                .set(DataAccessTaskDO::getSeatunnelJobId, task.getSeatunnelJobId())
                .setSql("update_time=clock_timestamp()"));
    }

    /**
     * 校验当前执行主体和引擎配置；使用平台数据源的引擎另行核验表权限。
     */
    public void validateExecutionPermission(DataAccessDO dataAccess) {
        validateAccessPermission(dataAccess);
    }

    /**
     * 校验访问权限。
     *
     * @param dataAccess 数据同步任务
     */
    private void validateAccessPermission(DataAccessDO dataAccess) {
        Long userId = cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService.parseExecutionUserId(dataAccess.getCreator());
        if (userId == null || userId <= 0) {
            throw new IllegalStateException("同步执行主体缺失，禁止访问数据源");
        }
        if (AccessEngineTypeEnum.API_ACCESS.getType().equals(dataAccess.getEngineType())) {
            JSONObject config = JSONUtil.parseObj(dataAccess.getApiAccessJson());
            for (ApiAccessMappingVO mapping : JSONUtil.toList(config.getJSONArray("mappings"),
                    ApiAccessMappingVO.class)) {
                if (config.getBool("changeDataSync", false) && !mapping.hasCompletePrimaryKeyMapping()) {
                    throw new IllegalStateException("增量写入必须完整导入接口声明的主键字段");
                }
                validateTargetOperations(dataAccess.getTargetDatabaseId(), mapping.getTargetTable(), userId,
                        config.getBool("targetDrop", false), true, config.getBool("changeDataSync", false));
            }
            return;
        }
        if (AccessEngineTypeEnum.SEATUNNEL.getType().equals(dataAccess.getEngineType())) {
            NativeSeatunnelConfig.validate(dataAccess.getSeatunnelConfig(), dataAccess.getConfigFormat());
            return;
        }
        MigrationRequest properties = dataAccess.getDataAccessJson() == null ? null
                : AccessTaskConfigMapper.toRuntime(dataAccess.getDataAccessJson(), dataAccess.getTargetDatabaseId());
        if (properties == null || CollUtil.isEmpty(properties.getSource()) || properties.getTarget() == null) {
            throw new IllegalStateException("同步配置不完整");
        }
        SourceDataSourceProperties source = properties.getSource().get(0);
        var sourceRoute = fillSourceConnection(source, dataAccess);
        fillTargetConnection(properties.getTarget(), dataAccess);
        MigrationService.validateAuthorizedConfiguration(properties);
        migrationAuthorization.requireTargetPermission(userId);
        TargetDataSourceProperties target = properties.getTarget();
        List<String> tables;
        if (SourceType.SQL.getCode().equals(source.getSourceType())) {
            databaseQuerySupport.normalizeSelect(dataAccess.getSourceDatabaseId(), source.getSourceSql());
            tables = List.of(source.getSourceSql());
        } else {
            tables = resolveAccessTables(dataAccess);
            if (tables.isEmpty()) {
                throw new IllegalStateException("没有可同步的源表");
            }
            databaseQuerySupport.requireExactTables(dataAccess.getSourceDatabaseId(), source.getSourceProductType(),
                    source.getSourceSchema(), tables);
            // 固化精确表集合，防止排除模式在执行时意外纳入新增表。
            source.setIncludeOrExclude(1);
            source.setSourceTableNames(tables);
            source.setSourceIncludes("");
            source.setSourceExcludes("");
            // 写回业务快照的固定集合；执行时不得重新展开排除规则。
            var authorized = dataAccess.getDataAccessJson().getSource().getFirst();
            authorized.setIncludeOrExclude(1);
            authorized.setSourceTableNames(tables);
            authorized.setSourceIncludes("");
            authorized.setSourceExcludes("");
        }
        for (String table : tables) {
            String targetTable = cn.iocoder.yudao.framework.dbswitch.data.util.MigrationTableNames.resolve(source,
                    target, table);
            cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.qualifiedTableName(
                    target.getTargetProductType(), target.getTargetSchema(), targetTable);
            migrationAuthorization.requireSourceBinding(sourceRoute, userId, table);
        }
        String sourceBefore = authorizeHook(dataAccess.getSourceDatabaseId(), source.getSourceProductType(),
                source.getBeforeSqlScripts(), userId);
        String sourceAfter = authorizeHook(dataAccess.getSourceDatabaseId(), source.getSourceProductType(),
                source.getAfterSqlScripts(), userId);
        String targetBefore = authorizeHook(dataAccess.getTargetDatabaseId(), target.getTargetProductType(),
                target.getBeforeSqlScripts(), userId);
        String targetAfter = authorizeHook(dataAccess.getTargetDatabaseId(), target.getTargetProductType(),
                target.getAfterSqlScripts(), userId);
        // 仅在四个脚本都完成语法校验后更新运行定义，后续使用规范化 SQL。
        var savedSource = dataAccess.getDataAccessJson().getSource().getFirst();
        var savedTarget = dataAccess.getDataAccessJson().getTarget();
        savedSource.setBeforeSqlScripts(sourceBefore);
        savedSource.setAfterSqlScripts(sourceAfter);
        savedTarget.setBeforeSqlScripts(targetBefore);
        savedTarget.setAfterSqlScripts(targetAfter);
    }

    /**
     * 授权钩子。
     *
     * @param databaseId 数据源编号
     * @param product 产品
     * @param script 脚本
     * @param userId 用户编号
     * @return 授权钩子文本
     */
    private String authorizeHook(Long databaseId,
            cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum product,
            String script, Long userId) {
        if (StrUtil.isBlank(script)) {
            return script;
        }
        var plan = cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.parseMigrationHook(product, script);
        var authorized = new ArrayList<String>();
        for (var step : plan.steps()) {
            // 生命周期脚本仅接受只读查询；写操作由受限目标计划统一负责。
            if (step.kind() != cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier.Kind.QUERY) {
                throw new IllegalStateException("当前同步路径不支持生命周期写入脚本");
            }
            String sql = databaseQuerySupport.normalizeSelect(databaseId, step.sql());
            if (StrUtil.isBlank(sql)) {
                throw new IllegalStateException("脚本只读查询规范化结果为空");
            }
            var rewritten = cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.parseMigrationHook(product,
                    sql);
            if (rewritten.steps().size() != 1 || rewritten.steps().getFirst().kind()
                    != cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier.Kind.QUERY) {
                throw new IllegalStateException("脚本查询规范化结果不符合单语句契约");
            }
            authorized.add(rewritten.steps().getFirst().sql());
        }
        return String.join(";\n", authorized);
    }

    /**
     * 校验目标操作。
     *
     * @param databaseId 数据源编号
     * @param table 表
     * @param userId 用户编号
     * @param drop 删除
     * @param truncate 清空
     * @param update 更新
     */
    private void validateTargetOperations(Long databaseId, String table, Long userId,
            boolean drop, boolean truncate, boolean update) {
        throw new IllegalStateException("当前 API 接入尚未接入可执行目标写授权计划，禁止写入");
    }

    /**
     * 解析访问表。
     *
     * @param dataAccess 数据同步任务
     * @return 解析访问表结果集合
     */
    private List<String> resolveAccessTables(DataAccessDO dataAccess) {
        if (dataAccess.getDataAccessJson() == null || CollUtil.isEmpty(dataAccess.getDataAccessJson().getSource())) {
            return List.of();
        }
        SourceDataSourceProperties source = AccessTaskConfigMapper.toRuntime(
                dataAccess.getDataAccessJson(), dataAccess.getTargetDatabaseId()).getSource().getFirst();
        if (SourceType.SQL.getCode().equals(source.getSourceType())) {
            return List.of();
        }
        boolean include = Integer.valueOf(1).equals(source.getIncludeOrExclude());
        List<String> selected = AccessExactTableSelection.selectedNames(source);
        List<String> includes = include ? selected : List.of();
        List<String> excludes = include ? List.of() : selected;
        if (Integer.valueOf(1).equals(source.getIncludeOrExclude()) && !includes.isEmpty()) {
            return includes.stream().distinct().toList();
        }
        fillSourceConnection(source, dataAccess);
        var metadata = dbSwitchRuntime.jdbcMetadata(source.getSourceProductType());
        List<String> discovered = metadata.queryTableList(source.getUrl(), source.getUsername(), source.getPassword(),
                        source.getSourceSchema())
                .stream().filter(table -> !table.isViewTable()).map(TableDescription::getTableName)
                .filter(table -> !excludes.contains(table)).distinct().toList();
        return cn.iocoder.yudao.framework.dbswitch.data.config.SourceTableSelection.requireExactNames(discovered);
    }

    // ==================== 离线同步（DBSwitch） ====================

    /**
     * 执行DB Switch。
     *
     * @param dataAccess 数据同步任务
     * @param task 任务记录
     * @param runningTask 运行中任务
     * @param realTimeLog 实际时间日志
     * @param totalRows 总量行
     * @param totalBytes 总量字节
     * @param successTables 成功表
     * @param failTables 失败表
     */
    private void executeDbswitch(DataAccessDO dataAccess, DataAccessTaskDO task, RunningTask runningTask,
            StringBuffer realTimeLog, AtomicLong totalRows, AtomicLong totalBytes,
            AtomicLong successTables, AtomicLong failTables) throws Exception {
        if (dataAccess.getDataAccessJson() == null) {
            throw new IllegalStateException("任务未配置同步内容");
        }
        // 运行时填充源端 / 目的端连接信息（密码现取现解密，不落库）
        MigrationRequest properties = dataAccess.getDataAccessJson() == null ? null
                : AccessTaskConfigMapper.toRuntime(dataAccess.getDataAccessJson(), dataAccess.getTargetDatabaseId());
        SourceDataSourceProperties source = properties.getSource().get(0);
        var sourceBinding = fillSourceConnection(source, dataAccess);
        var targetBinding = fillTargetConnection(properties.getTarget(), dataAccess);
        MigrationService.validateAuthorizedConfiguration(properties);
        Long actor = cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService.parseExecutionUserId(dataAccess.getCreator());
        migrationAuthorization.requireTargetPermission(actor);
        // 当前契约只覆盖一个受管理源；不能用第一个源的身份代表多源配置。
        var syncBinding = properties.getSource().size() == 1
                ? AccessSyncEvidence.bind(task, sourceBinding, targetBinding, source, properties.getTarget()) : null;
        // 配置摘要指向已冻结的请求；实际解析修订在任何引擎写入之前另行冻结。
        if (syncBinding != null && !coordinator.recordSyncBinding(lease(task), syncBinding)) {
            throw new IllegalStateException("同步绑定持久化时已失去执行所有权");
        }
        if (syncBinding != null) {
            task.setSyncEvidence(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(syncBinding));
        }
        // 固定任务、源代次和驱动摘要；检查点仅由目标提交后的 CAS 回调推进。
        if (Boolean.TRUE.equals(properties.getTarget().getChangeDataSync())
                && ChangeDataSyncType.INCREASE_COLUMN.name().equals(properties.getTarget().getChangeDataSyncType())
                && properties.getSource().size() != 1) {
            throw new IllegalArgumentException("类型化增量只支持单个受管理源");
        }
        var cursorBindings = cursorCheckpointStore.prepare(dataAccess, task, lease(task),
                source, properties.getTarget(), sourceBinding, targetBinding);
        if (!cursorBindings.isEmpty()) {
            properties.getTarget().setCursorRunBindings(cursorBindings);
        }
        var groupRewrite = groupRewriteCoordinator.prepare(dataAccess, task, lease(task), source,
                properties.getTarget(), sourceBinding, targetBinding);
        runningTask.groupRewriteHandle = groupRewrite;
        if (groupRewrite != null) {
            properties.getTarget().setGroupRewriteBindingProvider(groupRewrite);
        }
        appendLog(realTimeLog, "Start to run data access task [" + dataAccess.getTaskName() + "]");
        updateTaskLog(task, realTimeLog);
        // 持久化表级结果，用于运行统计与结果核查。
        MigrationService migrationService = new MigrationService(properties, tableResult ->
                handleTableResult(dataAccess, task, tableResult, realTimeLog,
                        totalRows, totalBytes, successTables, failTables), null, dbSwitchRuntime,
                (tableName, targetColumns, plan) -> {
                    // 执行前复核任务运行权限及连接修订，不沿用队列等待前的身份判断。
                    var sourceTables = source.getSourceTableNames().stream().filter(sourceTable -> tableName.equals(
                            cn.iocoder.yudao.framework.dbswitch.data.util.MigrationTableNames.resolve(source,
                                    properties.getTarget(), sourceTable))).toList();
                    if (sourceTables.size() != 1) {
                        throw new IllegalStateException("同步目标无法唯一绑定固定源表");
                    }
                    return migrationAuthorization.authorizeTable(sourceBinding, targetBinding, actor,
                            sourceTables.getFirst(), tableName, targetColumns, plan);
                });
        runningTask.migrationService = migrationService;
        boundary(task);
        if (runningTask.cancelSignalled) {
            migrationService.interrupt();
        }
        runningTask.engineStarted = true; // 从此处开始，只有引擎自身的回执能证明已退出。
        DbSwitchResult result = migrationService.run();
        if (syncBinding != null) {
            task.setSyncEvidence(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(
                    AccessSyncEvidence.finish(syncBinding, result)));
        }
        // 正常结束，汇总结果
        boolean allSuccess = result.getIfAllSuccess().get();
        task.setRunStatus(
                allSuccess ? AccessRunStatusEnum.SUCCESS.getStatus() : AccessRunStatusEnum.FAILED.getStatus());
        task.setDataCount(result.getTotalRowCount().get());
        task.setTableSuccessCount(result.getTotalTableSuccessCount().get());
        task.setTableFailCount(result.getTotalTableFailCount().get());
        task.setByteCount(BytesUnitUtils.bytesSizeToHuman(result.getTotalBytes().get()));
        task.setErrorInfo(allSuccess ? null
                : String.format("有 %s 张表同步失败，可在表级同步结果中查看", task.getTableFailCount()));
        appendLog(realTimeLog, "Data access task end " + (allSuccess ? "succeed" : "failed"));
        appendLog(realTimeLog,
                String.format("dataCount:[%s], tableSuccessCount:[%s], tableFailCount:[%s], byteCount:[%s]",
                        task.getDataCount(), task.getTableSuccessCount(), task.getTableFailCount(),
                        task.getByteCount()));
    }

    /**
     * 单表迁移完成回调：保存表级结果、累计计数、写增量日志、刷新实时日志
     */
    private void handleTableResult(DataAccessDO dataAccess, DataAccessTaskDO task, DbSwitchTableResult tableResult,
            StringBuffer realTimeLog,
            AtomicLong totalRows, AtomicLong totalBytes,
            AtomicLong successTables, AtomicLong failTables) {
        coordinator.writeOwned(lease(task), () -> {
            boolean success = tableResult.getIfSuccess().get();
            DataAccessTaskDetailDO detail = DataAccessTaskDetailDO.builder()
                    .dataAccessId(dataAccess.getId())
                    .taskId(task.getId())
                    .sourceSchemaName(tableResult.getSourceSchemaName())
                    .sourceTableName(tableResult.getSourceTableName())
                    .targetSchemaName(tableResult.getTargetSchemaName())
                    .targetTableName(tableResult.getTargetTableName())
                    .syncCount(tableResult.getSyncCount().get())
                    .syncBytes(BytesUnitUtils.bytesSizeToHuman(tableResult.getSyncBytes().get()))
                    .ifSuccess(success ? 1 : 0)
                    .errorMsg(success ? null : "同步失败；原始驱动消息未持久化，避免泄露连接信息")
                    .successMsg(success ? "同步成功" : null)
                    .typeMappingPlan(AccessTypeMappingDiagnostics.write(AccessTypeMappingDiagnostics.from(
                            tableResult.getTypeMappingPlan(), tableResult.getSourceSchemaName(),
                            tableResult.getSourceTableName(),
                            tableResult.getTargetSchemaName(), tableResult.getTargetTableName())))
                    .orgId(dataAccess.getOrgId())
                    .build();
            taskDetailMapper.insert(detail);

            totalRows.addAndGet(tableResult.getSyncCount().get());
            totalBytes.addAndGet(tableResult.getSyncBytes().get());
            if (success) {
                successTables.incrementAndGet();
                appendLog(realTimeLog,
                        String.format("Sync table [%s]->[%s] success ==> syncCount:[%s], syncBytes:[%s], msg:[%s]",
                                tableResult.getSourceTableName(), tableResult.getTargetTableName(),
                                tableResult.getSyncCount().get(),
                                BytesUnitUtils.bytesSizeToHuman(tableResult.getSyncBytes().get()),
                                "同步成功"));
            } else {
                failTables.incrementAndGet();
                appendLog(realTimeLog, String.format("Sync table [%s]->[%s] failed ==> [%s]",
                        tableResult.getSourceTableName(), tableResult.getTargetTableName(),
                        "同步失败；原始驱动消息未持久化"));
            }
            synchronized (task) {
                task.setDataCount(totalRows.get());
                task.setTableSuccessCount(successTables.get());
                task.setTableFailCount(failTables.get());
                task.setByteCount(BytesUnitUtils.bytesSizeToHuman(totalBytes.get()));
                task.setRealTimeLog(realTimeLog.toString());
                writeProgress(task);
            }
        });
    }

    /**
     * 补充来源连接。
     *
     * @param source 来源数据
     * @param dataAccess 数据同步任务
     * @return 连接信息
     */
    private AccessDatabaseResolver.ConnectionInfo fillSourceConnection(SourceDataSourceProperties source,
            DataAccessDO dataAccess) {
        if (SourceType.TABLE.getCode().equals(source.getSourceType())) {
            // 执行与组恢复都经过该边界；旧 glob/空项配置不能绕过鉴权集合的冻结。
            AccessExactTableSelection.selectedNames(source);
        }
        AccessDatabaseResolver.ConnectionInfo conn = databaseResolver.resolve(
                dataAccess.getSourceDatabaseId(),
                cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_SOURCE_DATABASE_NOT_EXISTS);
        source.setSourceProductType(conn.getProductType());
        source.setUrl(conn.getJdbcUrl());
        source.setDriverClassName(conn.getProductType().getDriveClassName());
        source.setUsername(conn.getUsername());
        source.setPassword(conn.getPassword());
        source.setSourceSchema(conn.getSchema());
        return conn;
    }

    /**
     * 补充目标连接。
     *
     * @param target 目标数据
     * @param dataAccess 数据同步任务
     * @return 连接信息
     */
    private AccessDatabaseResolver.ConnectionInfo fillTargetConnection(TargetDataSourceProperties target,
            DataAccessDO dataAccess) {
        AccessDatabaseResolver.ConnectionInfo conn = databaseResolver.resolve(
                dataAccess.getTargetDatabaseId(),
                cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_TARGET_DATABASE_NOT_EXISTS);
        target.setTargetProductType(conn.getProductType());
        target.setUrl(conn.getJdbcUrl());
        target.setDriverClassName(conn.getProductType().getDriveClassName());
        target.setUsername(conn.getUsername());
        target.setPassword(conn.getPassword());
        target.setTargetSchema(conn.getSchema());
        return conn;
    }

    // ==================== API 接入 ====================

    /**
     * 最大翻页保护：防止第三方 API 忽略分页参数导致无限翻页
     */
    private static final int API_MAX_PAGES = 10000;

    private static final int API_PAGE_SIZE = 100;

    /**
     * API 接入：逐个映射拉取外部 API 数据写入目标表
     *
     * 参考 srt-cloud 的 ApiTaskAdapter，修正：翻页上限保护、单映射失败任务置失败、
     * 只写入已导入字段（不加载目标表全部列）
     */
    private void executeApiAccess(DataAccessDO dataAccess, DataAccessTaskDO task, StringBuffer realTimeLog,
            AtomicLong totalRows, AtomicLong successTables, AtomicLong failTables) {
        if (StrUtil.isBlank(dataAccess.getApiAccessJson())) {
            throw new IllegalStateException("任务未配置 API 接入内容");
        }
        JSONObject config = JSONUtil.parseObj(dataAccess.getApiAccessJson());
        boolean syncExist = config.getBool("syncExist", true);
        boolean targetDrop = config.getBool("targetDrop", false);
        boolean changeDataSync = config.getBool("changeDataSync", false);
        List<ApiAccessMappingVO> mappings = JSONUtil.toList(config.getJSONArray("mappings"), ApiAccessMappingVO.class);
        if (CollUtil.isEmpty(mappings)) {
            throw new IllegalStateException("任务未配置 API 与目标表的映射");
        }
        if (changeDataSync && mappings.stream().anyMatch(mapping -> !mapping.hasCompletePrimaryKeyMapping())) {
            throw new IllegalStateException("增量写入必须完整导入接口声明的主键字段");
        }
        AccessDatabaseResolver.ConnectionInfo conn = databaseResolver.resolve(
                dataAccess.getTargetDatabaseId(),
                cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_TARGET_DATABASE_NOT_EXISTS);
        appendLog(realTimeLog, "Start to run api access task [" + dataAccess.getTaskName() + "]");
        updateTaskLog(task, realTimeLog);

        boolean allSuccess = true;
        TargetDataSourceProperties properties = new TargetDataSourceProperties();
        properties.setUrl(conn.getJdbcUrl());
        properties.setDriverClassName(conn.getProductType().getDriveClassName());
        properties.setTargetProductType(conn.getProductType());
        properties.setUsername(conn.getUsername());
        properties.setPassword(conn.getPassword());
        properties.setPoolSize(4);
        // 观察组件公开的生命周期钩子；仅凭 close() 返回不能证明已退出。
        var ownedThreads = new java.util.concurrent.CopyOnWriteArrayList<Thread>();
        var physicalConnections = new java.util.concurrent.CopyOnWriteArrayList<java.sql.Connection>();
        java.util.concurrent.ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "access-api-pool-" + task.getId());
            thread.setDaemon(true);
            ownedThreads.add(thread);
            return thread;
        };
        boolean returned = false;
        try (com.zaxxer.hikari.HikariDataSource dataSource = DataSourceUtils.createTargetDataSource(properties, threads,
                physicalConnections::add, dbSwitchRuntime.drivers().find(conn.getProductType()).orElse(null),
                dbSwitchRuntime.drivers().runtimes(), dbSwitchRuntime.options())) {
            for (ApiAccessMappingVO mapping : mappings) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("任务已被手动停止");
                }
                String apiName = StrUtil.blankToDefault(mapping.getSourceApiName(),
                        mapping.getApiConfig().getApiName());
                try {
                    boundary(task);
                    authorization.execution(dataAccess, task.getExecutionUserId());
                    long count = syncOneApi(task, conn, dataSource, mapping,
                            syncExist, targetDrop, changeDataSync, realTimeLog);
                    totalRows.addAndGet(count);
                    successTables.incrementAndGet();
                    insertApiDetail(dataAccess, task, apiName, mapping.getTargetTable(), conn.getSchema(),
                            count, true, null);
                    appendLog(realTimeLog, String.format("Sync api [%s]->[%s] success ==> syncCount:[%s]",
                            apiName, mapping.getTargetTable(), count));
                } catch (Exception ex) {
                    // DDL、写入、同步或 HTTP 异常无法证明所有外部副作用均已结束。
                    RunningTask handle = runningTasks.get(localKey(task.getId()));
                    if (handle != null) {
                        handle.apiCallUncertain = true;
                    }
                    allSuccess = false;
                    failTables.incrementAndGet();
                    String errorMsg = "API 同步未收敛：" + ex.getClass().getSimpleName();
                    insertApiDetail(dataAccess, task, apiName, mapping.getTargetTable(), conn.getSchema(),
                            0L, false, errorMsg);
                    appendLog(realTimeLog, String.format("Sync api [%s]->[%s] failed ==> [%s]",
                            apiName, mapping.getTargetTable(), ex.getClass().getSimpleName()));
                }
                synchronized (task) {
                    task.setDataCount(totalRows.get());
                    task.setTableSuccessCount(successTables.get());
                    task.setTableFailCount(failTables.get());
                    task.setRealTimeLog(realTimeLog.toString());
                    persistProgress(task);
                }
            }
            returned = true;
        } finally {
            RunningTask handle = runningTasks.get(localKey(task.getId()));
            if (handle != null) {
                boolean stopped = Thread.interrupted();
                try {
                    // 这些是连接池负责的维护与关闭线程，而非已取消的 Future。
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                    for (Thread thread : ownedThreads) {
                        long remaining = deadline - System.nanoTime();
                        if (thread.isAlive() && remaining > 0) {
                            thread.join(Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
                        }
                    }
                    boolean closed = !physicalConnections.isEmpty();
                    for (java.sql.Connection connection : physicalConnections) {
                        closed &= connection.isClosed();
                    }
                    handle.apiTerminated = returned && closed && !handle.apiCallUncertain
                            && ownedThreads.stream().allMatch(thread -> thread.getState() == Thread.State.TERMINATED);
                } catch (Exception unconfirmed) {
                    handle.apiTerminated = false;
                } finally {
                    if (stopped) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        task.setRunStatus(
                allSuccess ? AccessRunStatusEnum.SUCCESS.getStatus() : AccessRunStatusEnum.FAILED.getStatus());
        task.setErrorInfo(allSuccess ? null
                : String.format("有 %s 个 API 同步失败，可在表级同步结果中查看", failTables.get()));
        appendLog(realTimeLog, "Api access task end " + (allSuccess ? "succeed" : "failed"));
    }

    /**
     * 同步单个 API 到目标表：建表（可选删除重建）→ 分页拉取 → 字段映射与类型转换 → 全量或按主键 upsert 写入
     *
     * @return 写入行数
     */
    private long syncOneApi(DataAccessTaskDO task, AccessDatabaseResolver.ConnectionInfo conn,
            com.zaxxer.hikari.HikariDataSource dataSource,
            ApiAccessMappingVO mapping, boolean syncExist, boolean targetDrop,
            boolean changeDataSync, StringBuffer realTimeLog) throws Exception {
        DataApiSaveReqVO apiConfig = mapping.getApiConfig();
        if (changeDataSync && !mapping.hasCompletePrimaryKeyMapping()) {
            throw new IllegalStateException("增量写入必须完整导入接口声明的主键字段");
        }
        if (apiConfig == null || !apiConfig.isRequestConfigurationValid()) {
            throw new IllegalStateException("接口配置不完整");
        }
        if (CollUtil.isEmpty(apiConfig.getDataColumns())) {
            throw new IllegalStateException("API 未配置返回字段");
        }
        Map<String, ResponseColumnItem> apiColumnMap = new HashMap<>();
        apiConfig.getDataColumns().forEach(column -> apiColumnMap.put(column.getFieldName(), column));

        List<ApiAccessMappingVO.ApiColumnMapVO> importMaps = mapping.getColumnMaps().stream()
                .filter(column -> Integer.valueOf(1).equals(column.getImportFlg())
                        && StrUtil.isNotBlank(column.getTargetColumn()))
                .collect(Collectors.toList());
        if (importMaps.isEmpty()) {
            throw new IllegalStateException("没有配置导入字段");
        }

        // 目标列描述（类型取自 API 字段定义），只包含导入字段
        List<ColumnDescription> targetColumns = new ArrayList<>();
        List<String> pkNames = new ArrayList<>();
        Map<String, Integer> jdbcTypeMap = new HashMap<>();
        for (ApiAccessMappingVO.ApiColumnMapVO columnMap : importMaps) {
            ResponseColumnItem sourceColumn = apiColumnMap.get(columnMap.getSourceColumn());
            int jdbcType = sourceColumn == null || sourceColumn.getFieldType() == null
                    ? java.sql.Types.VARCHAR : sourceColumn.getFieldType();
            jdbcTypeMap.put(columnMap.getTargetColumn(), jdbcType);
            ColumnDescription description = new ColumnDescription();
            description.setFieldName(columnMap.getTargetColumn());
            description.setLabelName(columnMap.getTargetColumn());
            description.setValType(jdbcToValType(jdbcType));
            description.setLength(sourceColumn == null || sourceColumn.getDisplaySize() == null
                    ? 255 : sourceColumn.getDisplaySize());
            description.setPrecision(sourceColumn == null || sourceColumn.getPrecisionSize() == null
                    ? 0 : sourceColumn.getPrecisionSize());
            description.setNullable(sourceColumn == null || !Boolean.FALSE.equals(sourceColumn.getNullable()));
            description.setRemarks(sourceColumn == null ? null : sourceColumn.getDescription());
            boolean pk = sourceColumn != null && Boolean.TRUE.equals(sourceColumn.getPk());
            description.setPk(pk);
            if (pk) {
                pkNames.add(columnMap.getTargetColumn());
            }
            targetColumns.add(description);
        }

        String schema = conn.getSchema();
        String table = mapping.getTargetTable();
        IMetaDataByJdbcService metaDataService = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        boundary(task);
        boolean exists = metaDataService.tableExist(
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), schema, table);
        if (targetDrop && exists) {
            boundary(task);
            // 只有实际观察到表不存在才可视为无影响。DROP 错误的结果可能不确定，必须
            // 进入逐表 UNKNOWN 校验流程，绝不能吞掉错误后继续写入。
            metaDataService.dropTable(conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), schema, table);
            exists = false;
        }
        if (!exists) {
            List<String> ddlList = GenerateSqlUtils.getDDLCreateTableSQL(
                    dbSwitchRuntime.products().metadata(conn.getProductType()), targetColumns, pkNames, schema, table,
                    apiConfig.getApiName(), false, null);
            for (String sql : ddlList) {
                boundary(task);
                metaDataService.executeSql(conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), sql);
            }
            appendLog(realTimeLog, String.format("Target table [%s.%s] created", schema, table));
        } else if (!syncExist) {
            appendLog(realTimeLog,
                    String.format("Target table [%s.%s] exists and syncExist=false, skip", schema, table));
            return 0;
        }

        List<String> fieldNames = importMaps.stream()
                .map(ApiAccessMappingVO.ApiColumnMapVO::getTargetColumn).collect(Collectors.toList());
        boolean upsert = changeDataSync;
        IDatabaseOperator operator = dbSwitchRuntime.reader(dataSource, conn.getProductType());
        IDatabaseWriter writer = dbSwitchRuntime.writer(dataSource, conn.getProductType(),
                cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.INSERT);
        writer.prepareWrite(schema, table, fieldNames);
        IDatabaseSynchronize synchronize = null;
        if (upsert) {
            synchronize = dbSwitchRuntime.synchronizer(dataSource, conn.getProductType());
            synchronize.prepare(schema, table, fieldNames, pkNames);
        } else {
            boundary(task);
            operator.truncateTableData(schema, table);
        }

        long count = 0;
        boolean paged = Integer.valueOf(1).equals(apiConfig.getIfPage());
        int page = 1;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("任务已被手动停止");
            }
            boundary(task);
            authorization.execution(AccessRunSnapshot.restore(task.getConfigJson()), task.getExecutionUserId());
            List<Map<String, Object>> rows;
            try {
                rows = dataApiClient.fetchRows(apiConfig, paged ? page : null, API_PAGE_SIZE);
            } catch (RuntimeException ambiguousResponse) {
                RunningTask handle = runningTasks.get(localKey(task.getId()));
                if (handle != null) {
                    handle.apiCallUncertain = true;
                }
                throw ambiguousResponse;
            }
            if (rows.isEmpty()) {
                break;
            }
            List<Object[]> inserts = new ArrayList<>();
            List<Object[]> updates = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object[] values = new Object[fieldNames.size()];
                Map<String, Object> valueMap = new HashMap<>();
                int index = 0;
                for (ApiAccessMappingVO.ApiColumnMapVO columnMap : importMaps) {
                    Object value = resolveMappedValue(row, columnMap.getSourceColumn(), columnMap.getTargetColumn());
                    value = convertByJdbcType(jdbcTypeMap.get(columnMap.getTargetColumn()), value);
                    values[index++] = value;
                    valueMap.put(columnMap.getTargetColumn(), value);
                }
                if (upsert) {
                    Map<String, Object> pkValues = new HashMap<>();
                    pkNames.forEach(pk -> pkValues.put(pk, valueMap.get(pk)));
                    if (Boolean.TRUE.equals(operator.getPkExist(schema, table, pkValues))) {
                        updates.add(values);
                    } else {
                        inserts.add(values);
                    }
                } else {
                    inserts.add(values);
                }
            }
            if (!inserts.isEmpty()) {
                boundary(task);
                writer.write(fieldNames, inserts);
                count += inserts.size();
            }
            if (!updates.isEmpty()) {
                boundary(task);
                synchronize.executeUpdate(updates);
                count += updates.size();
            }
            if (!paged) {
                break;
            }
            page++;
            if (page > API_MAX_PAGES) {
                throw new IllegalStateException("API 已达到最大翻页上限，输出不完整");
            }
        }
        return count;
    }

    /**
     * 从 API 返回行中取映射值：精确匹配源字段 → 精确匹配目标字段 → 归一化（去 _-空格转小写）匹配；
     * 复杂类型（数组/对象）序列化为 JSON 字符串
     */
    private Object resolveMappedValue(Map<String, Object> row, String sourceColumn, String targetColumn) {
        Object value = row.get(sourceColumn);
        if (value == null) {
            value = row.get(targetColumn);
        }
        if (value == null) {
            String normalizedSource = normalizeColumn(sourceColumn);
            String normalizedTarget = normalizeColumn(targetColumn);
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String normalizedKey = normalizeColumn(entry.getKey());
                if (normalizedKey.equals(normalizedSource) || normalizedKey.equals(normalizedTarget)) {
                    value = entry.getValue();
                    break;
                }
            }
        }
        if (value instanceof Map || value instanceof List) {
            return JSONUtil.toJsonStr(value);
        }
        return value;
    }

    /**
     * 规范化列。
     *
     * @param name 名称
     * @return 规范化列文本
     */
    private String normalizeColumn(String name) {
        return name == null ? "" : name.replace("_", "").replace("-", "").replace(" ", "").toLowerCase();
    }

    /**
     * 按目标 JDBC 类型转换值：4-整数 8-小数 12-字符串
     */
    private Object convertByJdbcType(Integer jdbcType, Object value) {
        if (value == null || jdbcType == null) {
            return value;
        }
        try {
            String text = String.valueOf(value);
            return switch (jdbcType) {
                case java.sql.Types.INTEGER -> value instanceof Number ? value
                        : StrUtil.isBlank(text) ? null : Long.parseLong(text.trim());
                case java.sql.Types.DOUBLE -> value instanceof Number ? value
                        : StrUtil.isBlank(text) ? null : Double.parseDouble(text.trim());
                case java.sql.Types.VARCHAR -> text;
                default -> value;
            };
        } catch (NumberFormatException ex) {
            // 类型转换失败时保留原值，由数据库驱动做最终校验
            return value;
        }
    }

    /**
     * JDBC 类型 → dbswitch 逻辑类型（建表 DDL 用）
     */
    private Integer jdbcToValType(int jdbcType) {
        return switch (jdbcType) {
            case java.sql.Types.INTEGER -> ColumnMetaData.TYPE_INTEGER;
            case java.sql.Types.DOUBLE -> ColumnMetaData.TYPE_NUMBER;
            default -> ColumnMetaData.TYPE_STRING;
        };
    }

    /**
     * 新增API详情。
     *
     * @param dataAccess 数据同步任务
     * @param task 任务记录
     * @param apiName API名称
     * @param targetTable 目标表
     * @param schema 结构
     * @param syncCount 同步数量
     * @param success 成功
     * @param errorMsg 错误消息
     */
    private void insertApiDetail(DataAccessDO dataAccess, DataAccessTaskDO task, String apiName, String targetTable,
            String schema, Long syncCount, boolean success, String errorMsg) {
        coordinator.writeOwned(lease(task), () -> taskDetailMapper.insert(DataAccessTaskDetailDO.builder()
                .dataAccessId(dataAccess.getId())
                .taskId(task.getId())
                .sourceSchemaName("API")
                .sourceTableName(apiName)
                .targetSchemaName(schema)
                .targetTableName(targetTable)
                .syncCount(syncCount)
                .ifSuccess(success ? 1 : 0)
                .errorMsg(errorMsg)
                .successMsg(success ? "同步成功" : null)
                .orgId(dataAccess.getOrgId())
                .build()));
    }

    // ==================== 实时同步（Seatunnel） ====================

    /**
     * 提交 Seatunnel 作业并轮询直至结束：
     * FINISHED 视为成功；FAILED/CANCELED 视为失败；线程被中断（手动停止/停服）时向集群发送停止指令
     */
    private void executeSeatunnel(DataAccessDO dataAccess, DataAccessTaskDO task, StringBuffer realTimeLog,
            String preparedConfig) {
        appendLog(realTimeLog, "Start to run seatunnel access task [" + dataAccess.getTaskName() + "]");
        updateTaskLog(task, realTimeLog);

        String jobId = task.getSeatunnelJobId();
        if (StrUtil.isBlank(jobId)) {
            boundary(task);
            JSONObject submitResult = SeatunnelRestApi.submitJob(preparedConfig,
                    NativeSeatunnelConfig.normalizeFormat(dataAccess.getConfigFormat()),
                    "access-" + task.getId());
            jobId = submitResult.getStr("jobId");
        }
        if (StrUtil.isBlank(jobId)) {
            throw new IllegalStateException("Seatunnel 提交结果缺少作业身份，须查询原实例");
        }
        appendLog(realTimeLog, "Submit to seatunnel success, jobId is " + jobId);
        synchronized (task) {
            task.setSeatunnelJobId(jobId);
            task.setRealTimeLog(realTimeLog.toString());
            persistProgress(task);
        }

        String lastDataCount = null;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                stopSeatunnelJobQuietly(jobId);
                throw new IllegalStateException("任务已被手动停止，已向 Seatunnel 集群发送停止指令，jobId=" + jobId);
            }
            if (coordinator.shouldStop(lease(task))) {
                stopSeatunnelJobQuietly(jobId);
                throw new IllegalStateException("停止已请求，等待远端终态");
            }
            try {
                // 长驻任务定期复核当前执行主体的运行权限；拒绝后请求远端停止。
                authorization.execution(dataAccess, task.getExecutionUserId());
            } catch (RuntimeException revoked) {
                stopSeatunnelJobQuietly(jobId);
                throw new IllegalStateException("实时同步授权复核失败，已请求远端停止，等待终态对账");
            }
            JSONObject jobInfo = SeatunnelRestApi.getJobInfo(jobId);
            if (StrUtil.isBlank(jobInfo.getStr("jobId"))) {
                throw new IllegalStateException(String.format("Seatunnel 作业 [%s] 不存在", jobId));
            }
            String jobStatus = jobInfo.getStr("jobStatus");
            String dataCount = readSinkWriteCount(jobInfo);
            if (dataCount != null && !dataCount.equals(lastDataCount)) {
                lastDataCount = dataCount;
                appendLog(realTimeLog,
                        String.format("seatunnel job[%s] has been sync data count[%s]", jobId, dataCount));
            }
            updateSeatunnelProgress(task, realTimeLog, dataCount);

            if ("FINISHED".equals(jobStatus)) {
                RunningTask handle = runningTasks.get(localKey(task.getId()));
                if (handle != null) {
                    handle.remoteTerminated = true;
                }
                String errorMsg = jobInfo.getStr("errorMsg");
                if (StrUtil.isNotBlank(errorMsg)) {
                    task.setRunStatus(AccessRunStatusEnum.FAILED.getStatus());
                    task.setErrorInfo("SeaTunnel 作业返回错误；原始远端消息未持久化");
                    return;
                }
                appendLog(realTimeLog, "Seatunnel access run success end, jobId is " + jobId);
                task.setRunStatus(AccessRunStatusEnum.SUCCESS.getStatus());
                return;
            }
            if ("FAILED".equals(jobStatus) || "CANCELED".equals(jobStatus) || "CANCEL".equals(jobStatus)) {
                RunningTask handle = runningTasks.get(localKey(task.getId()));
                if (handle != null) {
                    handle.remoteTerminated = true;
                }
                task.setRunStatus("FAILED".equals(jobStatus) ? 4 : 6);
                task.setErrorInfo("SeaTunnel 已确认终态：" + jobStatus);
                return;
            }
            try {
                Thread.sleep(SEATUNNEL_POLL_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                stopSeatunnelJobQuietly(jobId);
                throw new IllegalStateException("任务已被手动停止，已向 Seatunnel 集群发送停止指令，jobId=" + jobId);
            }
        }
    }

    /** 读取路径绝不在没有租约的情况下进行权威状态对账。 */
    public DataAccessTaskDO reconcileSeatunnel(DataAccessTaskDO task) {
        return task == null ? null : coordinator.run(task.getId());
    }

    /**
     * 获取数据同步任务的远程配置。
     *
     * @param snapshot 快照
     * @return 数据同步任务的远程配置文本
     */
    private String remoteConfig(DataAccessDO snapshot) {
        NativeSeatunnelConfig.validate(snapshot.getSeatunnelConfig(), snapshot.getConfigFormat());
        return snapshot.getSeatunnelConfig();
    }

    /**
     * 读取输出写入数量。
     *
     * @param jobInfo 任务信息
     * @return 输出写入数量文本
     */
    private String readSinkWriteCount(JSONObject jobInfo) {
        JSONObject metrics = jobInfo.getJSONObject("metrics");
        return metrics == null ? null : metrics.getStr("SinkWriteCount");
    }

    /**
     * 更新SeaTunnel进度。
     *
     * @param task 任务记录
     * @param realTimeLog 实际时间日志
     * @param dataCount 数据数量
     */
    private void updateSeatunnelProgress(DataAccessTaskDO task, StringBuffer realTimeLog, String dataCount) {
        synchronized (task) {
            if (dataCount != null) {
                try {
                    task.setDataCount(Long.parseLong(dataCount.trim()));
                } catch (NumberFormatException ignored) {
                    // SinkWriteCount 非数字时忽略，仅记录日志
                }
            }
            task.setRealTimeLog(realTimeLog.toString());
            persistProgress(task);
        }
    }

    /**
     * 停止SeaTunnel任务静默。
     *
     * @param jobId 任务编号
     */
    private void stopSeatunnelJobQuietly(String jobId) {
        try {
            JSONObject body = new JSONObject();
            body.set("jobId", jobId);
            body.set("isStopWithSavePoint", false);
            SeatunnelRestApi.stopJob(body.toString());
        } catch (Exception ex) {
            log.warn("[stopSeatunnelJobQuietly][停止 Seatunnel 作业({}) 失败：{}]", jobId,
                    ex.getClass().getSimpleName());
        }
    }

    // ==================== 公共 ====================

    // 仅由持久化接受操作推进调度时间。

    /**
     * 更新任务日志。
     *
     * @param task 任务记录
     * @param realTimeLog 实际时间日志
     */
    private void updateTaskLog(DataAccessTaskDO task, StringBuffer realTimeLog) {
        synchronized (task) {
            task.setRealTimeLog(realTimeLog.toString());
            persistProgress(task);
        }
    }

    /**
     * 追加数据同步任务的日志。
     *
     * @param realTimeLog 实际时间日志
     * @param message 消息
     */
    private void appendLog(StringBuffer realTimeLog, String message) {
        realTimeLog.append(LocalDateTimeUtil.formatNormal(LocalDateTime.now()))
                .append(" ").append(message).append("\r\n");
    }

}
