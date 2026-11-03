package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataTransUtils;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.PrepareStatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushPreviewRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushConfigDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushIncreaseLogDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskLogDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushConfigMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushIncreaseLogMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushTaskLogMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushTaskMapper;
import cn.iocoder.yudao.module.dataforge.enums.push.PushChannelTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.push.PushModeEnum;
import cn.iocoder.yudao.module.dataforge.enums.push.PushRunStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.push.PushRunTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.push.PushTypeEnum;
import cn.iocoder.yudao.module.dataforge.framework.push.config.DataPushProperties;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.push.channel.DbPushChannel;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_SOURCE_DATABASE_NOT_EXISTS;

/**
 * 数据推送执行器
 *
 * @author Li
 */
@Slf4j
@Component
public class DataPushExecutor {

    @Resource
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime;

    private static final int DEFAULT_BATCH = 100;

    @Resource
    private DataPushTaskMapper taskMapper;

    @Resource
    private DataPushConfigMapper configMapper;

    @Resource
    private DataPushTaskLogMapper logMapper;

    @Resource
    private DataPushIncreaseLogMapper increaseLogMapper;

    @Resource
    private AccessDatabaseResolver databaseResolver;

    @Resource
    private DatabaseQuerySupport databaseQuerySupport;

    @Resource
    private ExecutionAccessService executionAccessService;

    @Resource
    private DataPushChannelRegistry channelRegistry;

    @Resource
    private DbPushChannel dbPushChannel;

    @Resource
    private DataPushProperties pushProperties;

    @Resource
    private DataPushOpsSupport opsSupport;

    @Resource
    private DataPushRunCoordinator runCoordinator;

    private ExecutorService executor;

    private final ConcurrentMap<Long, RunningTask> runningTasks = new ConcurrentHashMap<>();

    private static class RunningTask {

        private volatile Future<?> future;

        private volatile Long logId;

        private volatile String dispatchToken;

        private final AtomicBoolean stopped = new AtomicBoolean(false);

        private final List<Runnable> abortHooks = new CopyOnWriteArrayList<>();

        /**
         * 注册中止。
         *
         * @param hook 钩子
         */
        private void registerAbort(Runnable hook) {
            if (hook == null) {
                return;
            }
            abortHooks.add(hook);
            if (stopped.get()) {
                safeAbort(hook);
            }
        }

        /**
         * 中止运行中任务。
         */
        private void abort() {
            stopped.set(true);
            abortHooks.forEach(DataPushExecutor::safeAbort);
            if (future != null) {
                future.cancel(true);
            }
        }

        /**
         * 判断是否满足已停止条件。
         *
         * @return 判断结果
         */
        private boolean isStopped() {
            return stopped.get() || Thread.currentThread().isInterrupted();
        }
    }

    /**
     * 初始化数据推送。
     */
    @PostConstruct
    public void init() {
        DataPushProperties.Executor cfg = pushProperties == null ? new DataPushProperties.Executor()
                : pushProperties.getExecutor();
        int core = Math.max(1, cfg.getCoreSize());
        int max = Math.max(core, cfg.getMaxSize());
        int queue = Math.max(1, cfg.getQueueSize());
        AtomicInteger counter = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                core, max, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queue),
                runnable -> {
                    Thread thread = new Thread(runnable, "data-push-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        executor = pool;
    }

    /**
     * 在组件销毁时释放持有的运行资源。
     */
    @PreDestroy
    public void destroy() {
        runningTasks.values().forEach(RunningTask::abort);
        executor.shutdownNow();
    }

    /**
     * 判断当前执行是否仍处于运行状态。
     *
     * @param taskId 任务编号
     * @return 判断结果
     */
    public boolean isRunning(Long taskId) {
        return runCoordinator.isBlocked(taskId);
    }

    /**
     * 提交数据推送。
     *
     * @param taskId 任务编号
     * @param runType 运行类型
     * @return 处理是否成功
     */
    public boolean submit(Long taskId, PushRunTypeEnum runType) {
        return submitOwned(taskId, runType);
    }

    /**
     * 提交持有执行权。
     *
     * @param taskId 任务编号
     * @param runType 运行类型
     * @return 处理是否成功
     */
    private boolean submitOwned(Long taskId, PushRunTypeEnum runType) {
        DataPushTaskLogDO accepted = runCoordinator.claim(taskId, runType);
        if (accepted == null) {
            return false;
        }
        // 成功受理以数据库记录为准；队列不可用不撤销意图，由 XXL-Job 继续派发。
        try {
            dispatch(accepted);
        } catch (RuntimeException ex) {
            log.warn("[submitOwned][推送运行({}) 已持久受理，本机派发失败，等待恢复；异常类型={}]",
                    accepted.getId(), ex.getClass().getSimpleName());
        }
        return true;
    }

    /**
     * 派发数据推送。
     *
     * @param accepted 已受理
     * @return 处理是否成功
     */
    public boolean dispatch(DataPushTaskLogDO accepted) {

        Long taskId = accepted.getPushTaskId();
        RunningTask runningTask = new RunningTask();
        if (runningTasks.putIfAbsent(taskId, runningTask) != null) {
            return false;
        }
        DataPushTaskLogDO claimed = null;
        try {
            claimed = runCoordinator.acquireDispatch(accepted);
            if (claimed == null) {
                runningTasks.remove(taskId, runningTask);
                return false;
            }
            DataPushTaskLogDO run = claimed;
            runningTask.logId = run.getId();
            runningTask.dispatchToken = run.getDispatchToken();
            runningTask.future = executor.submit(() -> {
                try {
                    execute(run);
                } finally {
                    runningTasks.remove(taskId, runningTask);
                }
            });
            if (runningTask.stopped.get()) {
                runningTask.abort();
            }
            return true;
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            runningTasks.remove(taskId, runningTask);
            if (claimed != null) {
                runCoordinator.deferDispatch(claimed);
                opsSupport.alertQueueFull(taskMapper.selectById(taskId));
            }
            return false;
        } catch (RuntimeException | Error ex) {
            runningTasks.remove(taskId, runningTask);
            // 领取或入队回执不确定时保留原租约，不能猜测未受理并立即重发。
            throw ex;
        }
    }

    /**
     * 停止数据推送执行。
     *
     * @param taskId 任务编号
     * @return 处理是否成功
     */
    public boolean stop(Long taskId) {
        RunningTask runningTask = runningTasks.get(taskId);
        boolean changed = runCoordinator.cancel(taskId);
        if (runningTask != null) {
            runningTask.abort();
            runningTasks.remove(taskId, runningTask);
        }
        return changed;
    }

    /**
     * 中止本地。
     *
     * @param taskId 任务编号
     * @param runId 运行编号
     */
    void abortLocal(Long taskId, Long runId) {
        RunningTask running = runningTasks.get(taskId);
        if (running != null && java.util.Objects.equals(runId, running.logId)) {
            running.abort();
            runningTasks.remove(taskId, running);
        }
    }

    /**
     * 刷新运行中心跳。
     */
    public void touchRunningHeartbeats() {
        for (var entry : runningTasks.entrySet()) {
            RunningTask running = entry.getValue();
            if (running.logId != null) {
                try {
                    {
                        if (!runCoordinator.heartbeat(running.logId, running.dispatchToken)) {
                            running.abort();
                            runningTasks.remove(entry.getKey(), running);
                        }
                    }
                } catch (Exception ex) {
                    running.abort();
                    runningTasks.remove(entry.getKey(), running);
                    log.warn("[touchRunningHeartbeats][推送运行({}) 续心跳失败，终止本机发送并保留持久状态]",
                            running.logId);
                }
            }
        }
    }

    /**
     * 执行数据推送处理。
     *
     * @param logDO 日志
     */
    private void execute(DataPushTaskLogDO logDO) {
        Long taskId = logDO.getPushTaskId();
        StringBuilder realTimeLog = new StringBuilder();
        RunningTask running = runningTasks.get(taskId);
        if (running != null && (!java.util.Objects.equals(logDO.getId(), running.logId)
                || !java.util.Objects.equals(logDO.getDispatchToken(), running.dispatchToken))) {
            return;
        }
        DataPushTaskDO task = null;
        DataPushConfigDO config = null;
        DataPushIncreaseLogDO completedWatermark = null;
        boolean sending = false;
        DataPushSession session = DataPushSession.NOOP;
        try {
            task = taskMapper.selectById(taskId);
            if (task == null || (PushRunTypeEnum.AUTO.getType().equals(logDO.getRunType())
                    && !java.util.Objects.equals(task.getStatus(), DataPushTaskDO.STATUS_RELEASED))) {
                throw new IllegalStateException("任务不存在或已取消发布");
            }
            if (running != null && running.isStopped()) {
                throw new InterruptedException();
            }
            config = configMapper.selectById(task.getPushConfigId());
            if (config == null) {
                throw new IllegalStateException("推送配置不存在");
            }
            DataPushChannel channel = channelRegistry.get(config.getChannelType());
            if (channel == null) {
                throw new IllegalStateException("通道类型不在白名单：" + config.getChannelType());
            }
            channel.validateConfiguration(config.getChannelJson());
            AccessDatabaseResolver.ConnectionInfo source = databaseResolver.resolve(
                    task.getSourceDatabaseId(), DATA_PUSH_SOURCE_DATABASE_NOT_EXISTS);
            if (PushTypeEnum.isSql(task.getPushType())) {
                task.setSqlText(DataPushSqlValidator.validateSelect(task.getSqlText()));
            }
            assertPushPermission(task);
            appendLog(realTimeLog, "Start task [" + task.getName() + "] sourceId=" + task.getSourceDatabaseId()
                    + " sourceReadOnly=true");

            DataPushIncreaseSupport.Watermark watermark = null;
            if (PushModeEnum.isIncrement(task.getPushMode())) {
                if (StrUtil.isBlank(task.getIncrementColumn())) {
                    throw new IllegalStateException("增量推送未配置增量字段");
                }
                watermark = resolveWatermark(task, source);
                if (watermark == null) {
                    appendLog(realTimeLog, "Increment interval is empty, skip");
                    logDO.setRunStatus(PushRunStatusEnum.SUCCESS.getStatus());
                    return;
                }
                appendLog(realTimeLog, String.format("Increment (%s, %s]",
                        StrUtil.nullToEmpty(watermark.startVal()), watermark.endVal()));
            }

            // 持久发送边界先于所有外部副作用。CAS 失败表示取消/回收已赢得竞争。
            if (!runCoordinator.startSending(logDO)) {
                return;
            }
            sending = true;
            long dataCount;
            if (PushChannelTypeEnum.DB.getType().equals(config.getChannelType())) {
                String start = watermark == null ? null : watermark.startVal();
                String end = watermark == null ? null : watermark.endVal();
                dataCount = dbPushChannel.syncFromSource(source, task, config.getChannelJson(),
                        start, end, realTimeLog,
                        hook -> {
                            if (running != null) {
                                running.registerAbort(hook);
                            }
                        });
            } else {
                session = channel.openSession(config.getChannelJson());
                if (running != null) {
                    running.registerAbort(session::abort);
                }
                dataCount = pushByBatch(task, source, channel, config.getChannelJson(),
                        watermark, realTimeLog, logDO, running);
            }
            logDO.setDataCount(dataCount);
            if (watermark != null) {
                DataPushIncreaseSupport.validateLength(watermark.startVal(), watermark.endVal(),
                        maxWatermarkLength());
                completedWatermark = DataPushIncreaseLogDO.builder()
                        .pushTaskId(taskId)
                        .increaseColumn(task.getIncrementColumn())
                        .startVal(watermark.startVal())
                        .endVal(watermark.endVal())
                        .build();
            }
            logDO.setRunStatus(PushRunStatusEnum.SUCCESS.getStatus());
            appendLog(realTimeLog, "Finished, rows=" + dataCount);
        } catch (Throwable ex) {
            logDO.setRunStatus(sending ? PushRunStatusEnum.UNKNOWN.getStatus() : PushRunStatusEnum.FAILED.getStatus());
            if (isStopException(ex, running)) {
                Thread.currentThread().interrupt();
                logDO.setErrorInfo(sending ? "发送后停止，结果未知，禁止重跑" : "发送前已取消");
                appendLog(realTimeLog, "Stopped by user");
            } else {
                log.warn("[execute][推送任务({}) 异常类型={}，sending={}]", taskId, ex.getClass().getSimpleName(),
                        sending);
                logDO.setErrorInfo(sending ? "发送结果未知，禁止自动重跑，请核查外部结果" : "发送前准备失败，未执行发送");
                appendLog(realTimeLog, logDO.getErrorInfo());
            }
        } finally {
            try {
                session.close();
            } catch (Throwable closeFailure) {
                logDO.setRunStatus(
                        sending ? PushRunStatusEnum.UNKNOWN.getStatus() : PushRunStatusEnum.FAILED.getStatus());
                logDO.setErrorInfo("推送会话清理失败，请核查外部结果");
            }
            logDO.setEndTime(LocalDateTime.now());
            logDO.setRealTimeLog(DataPushLogBuffer.snapshot(realTimeLog, maxLogChars()));
            // 中断标记不能阻止最终短事务；提交失败仍由持久占位防重。
            boolean interrupted = Thread.interrupted();
            try {
                if (logDO.getRunStatus() >= 3 && runCoordinator.finish(logDO, sending ? 2 : 1,
                        PushRunStatusEnum.SUCCESS.getStatus()
                                .equals(logDO.getRunStatus()) ? completedWatermark : null)) {
                    opsSupport.afterFinished(task, logDO);
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * 解析水位。
     *
     * @param task 任务记录
     * @param source 来源数据
     * @return 水位
     */
    private DataPushIncreaseSupport.Watermark resolveWatermark(DataPushTaskDO task,
            AccessDatabaseResolver.ConnectionInfo source) {
        IMetaDataByJdbcService meta = dbSwitchRuntime.jdbcMetadata(source.getProductType());
        String incrementColumn = DataPushIdentifierQuotes.quoteColumn(
                source.getProductType(), task.getIncrementColumn());
        String jdbcUrl = sourceReadOnlyUrl(source);
        Object maxVal;
        if (PushTypeEnum.SQL.getType().equals(task.getPushType())) {
            maxVal = meta.queryMaxVal(jdbcUrl, source.getUsername(), source.getPassword(),
                    task.getSqlText(), incrementColumn);
        } else if (StrUtil.isNotBlank(source.getSchema())) {
            maxVal = meta.queryMaxVal(jdbcUrl, source.getUsername(), source.getPassword(),
                    DataPushIdentifierQuotes.quoteSchema(source.getProductType(), source.getSchema()),
                    DataPushIdentifierQuotes.quoteTable(source.getProductType(), task.getTableName()),
                    incrementColumn);
        } else {
            String inner = "SELECT " + incrementColumn + " FROM "
                    + DataPushIdentifierQuotes.quoteTable(source.getProductType(), task.getTableName());
            maxVal = meta.queryMaxVal(jdbcUrl, source.getUsername(), source.getPassword(),
                    inner, incrementColumn);
        }
        String endVal = DataTransUtils.getDataStr(maxVal);
        DataPushIncreaseLogDO last = task.getId() == null ? null : increaseLogMapper.selectNewest(task.getId());
        String prevEnd = last != null ? last.getEndVal() : StrUtil.trimToNull(task.getInitStartVal());
        return DataPushIncreaseSupport.build(prevEnd, endVal);
    }

    /**
     * 试跑预览：只读源端前 N 行，不推送、不写水位、不写执行记录。
     */
    public DataPushPreviewRespVO preview(DataPushTaskDO task, int limit) throws Exception {
        DataPushConfigDO config = configMapper.selectById(task.getPushConfigId());
        return preview(task, config, limit);
    }

    /** 草稿配置只在内存中参与预览，沿用源端权限和只读查询路径。 */
    public DataPushPreviewRespVO preview(DataPushTaskDO task, DataPushConfigDO config, int limit) throws Exception {
        int rowLimit = clampPreviewRows(limit);
        if (config == null) {
            throw new IllegalStateException("推送配置不存在");
        }
        AccessDatabaseResolver.ConnectionInfo source = databaseResolver.resolve(
                task.getSourceDatabaseId(), DATA_PUSH_SOURCE_DATABASE_NOT_EXISTS);
        if (PushTypeEnum.isSql(task.getPushType())) {
            task.setSqlText(DataPushSqlValidator.validateSelect(task.getSqlText()));
        }
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            userId = resolvePushUserId(task);
        }
        executionAccessService.requireUserPermission(userId, "dataforge:data-push:query");
        PushQuerySql querySql = preparePushQuerySql(task, source, userId);
        if (PushTypeEnum.isSql(task.getPushType())) {
            task.setSqlText(querySql.executedSql());
        }

        DataPushIncreaseSupport.Watermark watermark = null;
        if (PushModeEnum.isIncrement(task.getPushMode())) {
            if (StrUtil.isBlank(task.getIncrementColumn())) {
                throw new IllegalStateException("增量推送未配置增量字段");
            }
            watermark = resolveWatermark(task, source);
        }

        DataPushPreviewRespVO vo = new DataPushPreviewRespVO();
        vo.setChannelType(config.getChannelType());
        vo.setObjectName(resolveObjectName(task));
        vo.setHint("仅读取源端前 " + rowLimit + " 行，不会写入接收方、不会推进水位、不会写执行记录");
        if (PushModeEnum.isIncrement(task.getPushMode()) && watermark == null) {
            vo.setEmptyInterval(true);
            vo.setColumns(List.of());
            vo.setRows(List.of());
            vo.setRowCount(0);
            vo.setPayloadPreview(JsonUtils.toJsonString(new PushPayload(vo.getObjectName(), List.of())));
            return vo;
        }
        vo.setEmptyInterval(false);
        if (watermark != null) {
            vo.setIncrementStart(watermark.startVal());
            vo.setIncrementEnd(watermark.endVal());
        }
        PreviewSlice slice = readPreviewRows(task, source, watermark, rowLimit, querySql);
        List<Map<String, Object>> outbound = slice.rows();
        vo.setColumns(slice.columns());
        vo.setRows(outbound);
        vo.setRowCount(outbound == null ? 0 : outbound.size());
        vo.setPayloadPreview(StrUtil.maxLength(
                JsonUtils.toJsonString(new PushPayload(vo.getObjectName(), outbound)), 8000));
        return vo;
    }

    /**
     * 读取预览行。
     *
     * @param task 任务记录
     * @param source 来源数据
     * @param watermark 水位
     * @param limit 读取或处理上限
     * @param querySql 查询SQL
     * @return 预览切片
     */
    private PreviewSlice readPreviewRows(DataPushTaskDO task,
            AccessDatabaseResolver.ConnectionInfo source,
            DataPushIncreaseSupport.Watermark watermark,
            int limit, PushQuerySql querySql) throws Exception {
        IMetaDataByJdbcService meta = dbSwitchRuntime.jdbcMetadata(source.getProductType());
        String jdbcUrl = sourceReadOnlyUrl(source);
        List<ColumnDescription> columnMetas = meta.querySqlColumnMeta(jdbcUrl, source.getUsername(),
                source.getPassword(), querySql.executedSql());
        List<String> columnNames = columnMetas.stream().map(ColumnDescription::getFieldName).toList();
        List<Map<String, Object>> rows = new ArrayList<>(limit);
        try (HikariDataSource dataSource = createDataSource(source)) {
            DataSource readOnly = DataPushReadOnlySupport.wrap(dataSource);
            IDatabaseOperator operator = dbSwitchRuntime.reader(
                    readOnly, source.getProductType());
            operator.setFetchSize(Math.min(limit, maxBatchSize()));
            ResultSet rs;
            AutoCloseable closeable;
            if (watermark != null) {
                ColumnDescription increase = projectedWatermark(columnMetas, task.getIncrementColumn());
                PrepareStatementResultSet prs = operator.queryIncreaseTableData(querySql.executedSql(),
                        columnNames,
                        increase, watermark.startVal(), watermark.endVal());
                closeable = prs;
                rs = prs.getResultset();
            } else {
                StatementResultSet srs = operator.queryTableData(querySql.executedSql(), columnNames);
                closeable = srs;
                rs = srs.getResultset();
            }
            try (closeable) {
                while (rs.next() && rows.size() < limit) {
                    rows.add(buildRowMap(columnNames, rs));
                }
            }
        }
        return new PreviewSlice(columnNames, rows);
    }

    /**
     * 限定预览行。
     *
     * @param limit 读取或处理上限
     * @return 限定预览行数值
     */
    private int clampPreviewRows(int limit) {
        int max = pushProperties == null ? 50 : Math.max(1, pushProperties.getPreviewMaxRows());
        return Math.min(max, Math.max(1, limit));
    }

    private record PreviewSlice(List<String> columns, List<Map<String, Object>> rows) {

    }

    /**
     * 获取数据推送的已投影水位。
     *
     * @param columns 列集合
     * @param name 名称
     * @return 列描述
     */
    static ColumnDescription projectedWatermark(List<ColumnDescription> columns, String name) {
        List<ColumnDescription> matches = columns.stream()
                .filter(column -> name != null && name.equals(column.getFieldName())).toList();
        if (matches.size() != 1 || matches.getFirst().getFieldType() == java.sql.Types.NULL) {
            throw new IllegalStateException("增量字段必须在已授权的精确投影中唯一出现并具有已知类型");
        }
        return matches.getFirst().copy();
    }

    /**
     * 推送按批次。
     *
     * @param task 任务记录
     * @param source 来源数据
     * @param channel 通道
     * @param channelJson 通道JSON
     * @param watermark 水位
     * @param realTimeLog 实际时间日志
     * @param run 运行
     * @param running 运行中
     * @return 推送按批次数值
     */
    private long pushByBatch(DataPushTaskDO task,
            AccessDatabaseResolver.ConnectionInfo source,
            DataPushChannel channel,
            String channelJson,
            DataPushIncreaseSupport.Watermark watermark,
            StringBuilder realTimeLog,
            DataPushTaskLogDO run,
            RunningTask running) throws Exception {
        int fetchSize = task.getBatchSize() == null || task.getBatchSize() <= 0
                ? DEFAULT_BATCH : task.getBatchSize();
        fetchSize = Math.min(fetchSize, maxBatchSize());
        String objectName = resolveObjectName(task);
        IMetaDataByJdbcService meta = dbSwitchRuntime.jdbcMetadata(source.getProductType());
        String jdbcUrl = sourceReadOnlyUrl(source);
        PushQuerySql querySql = preparePushQuerySql(task, source, resolvePushUserId(task));
        List<ColumnDescription> columns = meta.querySqlColumnMeta(jdbcUrl, source.getUsername(),
                source.getPassword(), querySql.executedSql());
        List<String> columnNames = columns.stream().map(ColumnDescription::getFieldName).toList();
        long dataCount = 0;
        try (HikariDataSource dataSource = createDataSource(source)) {
            if (running != null) {
                running.registerAbort(dataSource::close);
            }
            DataSource readOnly = DataPushReadOnlySupport.wrap(dataSource);
            IDatabaseOperator operator = dbSwitchRuntime.reader(
                    readOnly, source.getProductType());
            operator.setFetchSize(fetchSize);
            ResultSet rs;
            AutoCloseable closeable;
            if (watermark != null) {
                ColumnDescription increase = projectedWatermark(columns, task.getIncrementColumn());
                PrepareStatementResultSet prs = operator.queryIncreaseTableData(querySql.executedSql(),
                        columnNames,
                        increase, watermark.startVal(), watermark.endVal());
                closeable = prs;
                rs = prs.getResultset();
            } else {
                StatementResultSet srs = operator.queryTableData(querySql.executedSql(), columnNames);
                closeable = srs;
                rs = srs.getResultset();
            }
            if (running != null) {
                running.registerAbort(() -> closeQuietly(closeable));
            }
            try (closeable) {
                List<Map<String, Object>> batch = new ArrayList<>(fetchSize);
                int batchBytes = 0;
                int maxBytes = maxBatchBytes();
                while (rs.next()) {
                    if (running != null && running.isStopped()) {
                        throw new InterruptedException("任务已被手动停止");
                    }
                    Map<String, Object> row = buildRowMap(columnNames, rs);
                    int rowBytes = DataPushPayloadLimiter.estimateRowBytes(row);
                    if (!batch.isEmpty() && batchBytes + rowBytes > maxBytes) {
                        dataCount += flushPushBatch(task, channel, objectName, batch, channelJson, realTimeLog,
                                querySql);
                        refreshLog(run, dataCount, realTimeLog);
                        batch = new ArrayList<>(fetchSize);
                        batchBytes = 0;
                    }
                    batch.add(row);
                    batchBytes += rowBytes;
                    if (batch.size() >= fetchSize) {
                        dataCount += flushPushBatch(task, channel, objectName, batch, channelJson, realTimeLog,
                                querySql);
                        refreshLog(run, dataCount, realTimeLog);
                        batch = new ArrayList<>(fetchSize);
                        batchBytes = 0;
                    }
                }
                if (!batch.isEmpty()) {
                    dataCount += flushPushBatch(task, channel, objectName, batch, channelJson, realTimeLog,
                            querySql);
                }
            }
        }
        return dataCount;
    }

    /**
     * 确认推送权限。
     *
     * @param task 任务记录
     */
    private void assertPushPermission(DataPushTaskDO task) {
        Long userId = resolvePushUserId(task);
        executionAccessService.requireUserPermission(userId, "dataforge:data-push:run");
        String sql = databaseQuerySupport.normalizeSelect(task.getSourceDatabaseId(), resolvePushSql(task));
        if (StrUtil.isBlank(sql)) {
            throw new IllegalStateException("推送只读查询规范化结果为空");
        }
        // 所有通道和水位计算都使用经过组件校验的 SQL。
        if (PushTypeEnum.isSql(task.getPushType())) {
            task.setSqlText(sql);
        }
    }

    /**
     * 刷出推送批次。
     *
     * @param task 任务记录
     * @param channel 通道
     * @param objectName 对象名称
     * @param batch 目标批次信息
     * @param channelJson 通道JSON
     * @param realTimeLog 实际时间日志
     * @param querySql 查询SQL
     * @return 符合条件或受影响的记录数量
     */
    private int flushPushBatch(DataPushTaskDO task, DataPushChannel channel, String objectName,
            List<Map<String, Object>> batch, String channelJson,
            StringBuilder realTimeLog, PushQuerySql querySql) throws Exception {
        channel.push(new PushPayload(objectName, batch), channelJson, realTimeLog);
        return batch == null ? 0 : batch.size();
    }

    /**
     * 解析推送用户编号。
     *
     * @param task 任务记录
     * @return 解析推送用户编号数值
     */
    private Long resolvePushUserId(DataPushTaskDO task) {
        Long userId = ExecutionAccessService.parseExecutionUserId(task.getCreator());
        return userId != null ? userId : task.getReleaseUserId();
    }

    /**
     * 解析推送SQL。
     *
     * @param task 任务记录
     * @return 解析推送SQL文本
     */
    private String resolvePushSql(DataPushTaskDO task) {
        if (PushTypeEnum.SQL.getType().equals(task.getPushType()) && StrUtil.isNotBlank(task.getSqlText())) {
            return task.getSqlText();
        }
        var source = databaseResolver.resolve(task.getSourceDatabaseId(), DATA_PUSH_SOURCE_DATABASE_NOT_EXISTS);
        return "SELECT * FROM " + RestrictedSelectGuard.qualifiedTableName(source.getProductType(),
                source.getSchema(), task.getTableName());
    }

    /**
     * 准备推送查询SQL。
     *
     * @param task 任务记录
     * @param source 来源数据
     * @param userId 用户编号
     * @return 推送查询SQL
     */
    private PushQuerySql preparePushQuerySql(DataPushTaskDO task,
            AccessDatabaseResolver.ConnectionInfo source, Long userId) {
        String originalSql = PushTypeEnum.SQL.getType().equals(task.getPushType()) ? task.getSqlText()
                : "SELECT * FROM " + RestrictedSelectGuard.qualifiedTableName(source.getProductType(),
                source.getSchema(), task.getTableName());
        String executedSql = databaseQuerySupport.normalizeSelect(task.getSourceDatabaseId(), originalSql);
        if (StrUtil.isBlank(executedSql)) {
            throw new IllegalStateException("推送未获得可执行的只读查询");
        }
        return new PushQuerySql(originalSql, executedSql);
    }

    private record PushQuerySql(String originalSql, String executedSql) {

    }

    /**
     * 刷新数据推送的日志。
     *
     * @param run 运行
     * @param dataCount 数据数量
     * @param realTimeLog 实际时间日志
     */
    private void refreshLog(DataPushTaskLogDO run, long dataCount, StringBuilder realTimeLog) {
        if (run == null) {
            return;
        }
        if (!runCoordinator.progress(run, dataCount, DataPushLogBuffer.snapshot(realTimeLog, maxLogChars()))) {
            throw new IllegalStateException("推送执行身份已失效，停止后续发送");
        }
    }

    /**
     * 创建数据推送的数据源。
     *
     * @param conn 已解析的数据源连接信息
     * @return Hikari数据源
     */
    private HikariDataSource createDataSource(AccessDatabaseResolver.ConnectionInfo conn) {
        TargetDataSourceProperties properties = new TargetDataSourceProperties();
        properties.setUrl(sourceReadOnlyUrl(conn));
        properties.setDriverClassName(conn.getProductType().getDriveClassName());
        properties.setTargetProductType(conn.getProductType());
        properties.setUsername(conn.getUsername());
        properties.setPassword(conn.getPassword());
        properties.setPoolSize(4);
        return DataSourceUtils.createTargetDataSource(properties, null, null,
                dbSwitchRuntime.drivers().find(conn.getProductType()).orElse(null),
                dbSwitchRuntime.drivers().runtimes(), dbSwitchRuntime.options());
    }

    /**
     * 构造行映射。
     *
     * @param columns 列集合
     * @param rs JDBC 查询结果集
     * @return 处理后的键值映射
     */
    private Map<String, Object> buildRowMap(List<String> columns, ResultSet rs) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        int maxLob = pushProperties == null ? 65_536 : Math.max(0, pushProperties.getMaxLobBytes());
        int maxText = pushProperties == null ? 16_384 : Math.max(1, pushProperties.getMaxStringChars());
        for (String column : columns) {
            row.put(column, DataPushPayloadLimiter.readColumn(rs, column, maxLob, maxText));
        }
        return row;
    }

    /**
     * 获取数据推送的来源只读URL。
     *
     * @param conn 已解析的数据源连接信息
     * @return 数据推送的来源只读URL文本
     */
    private static String sourceReadOnlyUrl(AccessDatabaseResolver.ConnectionInfo conn) {
        return DataPushReadOnlySupport.applyUrl(conn.getProductType(), conn.getJdbcUrl());
    }

    /**
     * 解析对象名称。
     *
     * @param task 任务记录
     * @return 解析对象名称文本
     */
    private String resolveObjectName(DataPushTaskDO task) {
        if (StrUtil.isNotBlank(task.getObjectName())) {
            return task.getObjectName();
        }
        if (StrUtil.isNotBlank(task.getTableName())) {
            return task.getTableName().toUpperCase();
        }
        return "CUSTOM_SQL";
    }

    /**
     * 追加数据推送的日志。
     *
     * @param log 日志
     * @param line 行
     */
    private void appendLog(StringBuilder log, String line) {
        DataPushLogBuffer.append(log, line, maxLogChars());
    }

    /**
     * 获取数据推送的最大值日志字符。
     *
     * @return 最大值日志字符数值
     */
    private int maxLogChars() {
        return pushProperties == null ? 32_000 : Math.max(1024, pushProperties.getMaxLogChars());
    }

    /**
     * 获取数据推送的最大值水位长度。
     *
     * @return 最大值水位长度数值
     */
    private int maxWatermarkLength() {
        return pushProperties == null ? 256 : Math.max(1, pushProperties.getWatermarkMaxLength());
    }

    /**
     * 获取数据推送的最大值批次大小。
     *
     * @return 最大值批次大小数值
     */
    private int maxBatchSize() {
        return pushProperties == null ? 1_000 : Math.max(1, pushProperties.getMaxBatchSize());
    }

    /**
     * 获取数据推送的最大值批次字节。
     *
     * @return 最大值批次字节数值
     */
    private int maxBatchBytes() {
        return pushProperties == null ? 2_097_152 : Math.max(8_192, pushProperties.getMaxBatchBytes());
    }

    /**
     * 结合任务停止标记、线程中断及停止异常，判断本次失败是否由主动停止引起。
     *
     * @param ex 异常信息
     * @param running 运行中
     * @return 判断结果
     */
    private static boolean isStopException(Throwable ex, RunningTask running) {
        if (running != null && running.stopped.get()) {
            return true;
        }
        if (ex instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
            return true;
        }
        return ex != null && "任务已被手动停止".equals(ex.getMessage());
    }

    /**
     * 尽力调用中止钩子，忽略清理异常以保留原处理结果。
     *
     * @param hook 钩子
     */
    private static void safeAbort(Runnable hook) {
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (Exception ignored) {
        }
    }

    /**
     * 关闭持有的资源，并忽略清理阶段的关闭异常。
     *
     * @param closeable 可关闭资源
     */
    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

}
