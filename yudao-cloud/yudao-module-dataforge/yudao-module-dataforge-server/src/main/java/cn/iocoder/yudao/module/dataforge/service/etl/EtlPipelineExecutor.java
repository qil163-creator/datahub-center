package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.engine.EtlPipelineEngine;
import cn.iocoder.yudao.framework.etl.core.engine.EtlRunOptions;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunListener;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunResult;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlNodeRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlPipelineDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlNodeRecordMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlPipelineMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlRecordMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可视化 ETL 执行提交器：承载引擎调用线程、维护运行注册表、持久化流程/节点级日志与状态
 *
 * @author Li
 */
@Slf4j
@Component
public class EtlPipelineExecutor {

    /** 运行观测快照写入间隔；每次覆盖一行运行记录，前端轮询间隔为 2 秒。 */
    static final long RUNTIME_SAMPLE_MILLIS = 3000;

    @Resource
    private EtlPolicyPlanService policyPlanService;

    @Resource
    private EtlQualityEvidenceService qualityEvidenceService;

    @Resource
    private EtlTargetBatchService targetBatchService;

    @Resource
    private EtlFileRecoveryService fileRecoveryService;

    @Resource
    private EtlFileSourceProofService fileSourceProofService;

    @Resource
    private EtlReplayService replayService;

    @Resource
    private EtlOperationsMetrics operationsMetrics;

    @Resource
    private EtlPipelineMapper pipelineMapper;

    @Resource
    private EtlRecordMapper recordMapper;

    @Resource
    private EtlNodeRecordMapper nodeRecordMapper;

    @Resource
    private EtlPipelineEngine pipelineEngine;

    @Resource
    private EtlScriptAdmissionService scriptAdmissionService;

    @Resource
    private EtlConnectionResolverAdapter connectionResolverAdapter;

    @Resource
    private EtlApiPortAdapter apiPortAdapter;

    @Resource
    private EtlNodeAccessService nodeAccessService;

    @Resource
    private EtlEnvironmentService environmentService;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService executionAccessService;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService qualityGateService;

    @Resource
    private EtlRunCoordinator runCoordinator;

    @Resource
    private cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties executionProperties;

    private ExecutorService executor;

    private java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(36);

    private java.util.concurrent.ScheduledExecutorService leases;

    private java.util.concurrent.ScheduledExecutorService stopSignals;

    private final String nodeInstanceId = java.util.UUID.randomUUID().toString();

    private final ConcurrentMap<String, LocalRun> localRuns = new ConcurrentHashMap<>();

    /** 内存只管理本节点线程；数据库令牌、停止意图和占位才是权威。 */
    private static final class LocalRun {

        final java.util.concurrent.atomic.AtomicBoolean stopping = new java.util.concurrent.atomic.AtomicBoolean();

        private Thread thread;

        java.util.concurrent.ScheduledFuture<?> heartbeat;

        java.util.concurrent.ScheduledFuture<?> stopPoll;

        /**
         * 关联本地运行。
         */
        synchronized void attach() {
            thread = Thread.currentThread();
            if (stopping.get()) {
                thread.interrupt();
            }
        }

        /**
         * 发送信号以停止。
         */
        synchronized void signalStop() {
            stopping.set(true);
            if (thread != null) {
                thread.interrupt();
            }
        }

        /**
         * 设置本地运行的线程。
         */
        synchronized void detach() {
            thread = null;
        }

        /**
         * 取消监控。
         */
        void cancelMonitors() {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            if (stopPoll != null) {
                stopPoll.cancel(false);
            }
        }
    }

    /**
     * 初始化ETL 流程。
     */
    @PostConstruct
    public void init() {
        AtomicInteger counter = new AtomicInteger();
        int concurrency = executionProperties.getWorkerConcurrency();
        int queueSize = executionProperties.getWorkerQueue();
        capacity = new java.util.concurrent.Semaphore(concurrency + queueSize);
        java.util.concurrent.BlockingQueue<Runnable> queue = queueSize == 0
                ? new java.util.concurrent.SynchronousQueue<>() : new java.util.concurrent.ArrayBlockingQueue<>(
                queueSize);
        executor = new java.util.concurrent.ThreadPoolExecutor(concurrency, concurrency, 0,
                java.util.concurrent.TimeUnit.SECONDS,
                queue, runnable -> {
            Thread thread = new Thread(runnable, "etl-pipeline-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        leases = Executors.newScheduledThreadPool(2, r -> daemon(r, "etl-lease"));
        stopSignals = Executors.newScheduledThreadPool(2, r -> daemon(r, "etl-stop"));
    }

    /**
     * 创建用于当前后台处理的守护线程。
     *
     * @param task 任务记录
     * @param name 名称
     * @return 线程
     */
    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * 在组件销毁时释放持有的运行资源。
     */
    @PreDestroy
    public void destroy() {
        localRuns.values().forEach(LocalRun::signalStop);
        if (executor != null) {
            executor.shutdownNow();
        }
        if (leases != null) {
            leases.shutdownNow();
        }
        if (stopSignals != null) {
            stopSignals.shutdownNow();
        }
        // 不因进程退出而伪造终态；未确认的持有者由数据库租约恢复协议处理。
    }

    /**
     * 判断当前执行是否仍处于运行状态。
     *
     * @param pipelineId 流程编号
     * @return 判断结果
     */
    public boolean isRunning(Long pipelineId) {
        if (recordMapper.countSlots(pipelineId) != 0) {
            return true;
        }
        return recordMapper.selectCount(Wrappers.lambdaQuery(EtlRecordDO.class)
                .eq(EtlRecordDO::getPipelineId, pipelineId)
                .and(w -> w.eq(EtlRecordDO::getOccupiesSlot, true)
                        .or().eq(EtlRecordDO::getRunStatus, EtlRecordDO.RUN_STATUS_WAITING))) > 0;
    }

    /**
     * 提交ETL 流程。
     *
     * @param recordId 记录编号
     */
    public void submit(Long recordId) {
        if (!capacity.tryAcquire()) {
            return;
        }
        boolean handedOff = false;
        try {
            var lease = runCoordinator.claim(recordId, nodeInstanceId);
            if (lease.isEmpty()) {
                return;
            }
            EtlRecordDO record = recordMapper.selectById(recordId);
            if (record == null) {
                return;
            }
            String token = lease.get().token();
            record.setExecutionToken(token);
            LocalRun control = new LocalRun();
            localRuns.put(token, control);
            try {
                control.heartbeat = leases.scheduleWithFixedDelay(() ->
                        {
                            try {
                                if (!runCoordinator.renew(recordId, token)) {
                                    control.signalStop();
                                }
                            } catch (RuntimeException unavailable) {
                                control.signalStop();
                                log.warn("ETL 租约续期不可确认，停止本地执行 recordId={}", recordId);
                            }
                        }, executionProperties.getHeartbeat().toSeconds(), executionProperties.getHeartbeat().toSeconds(),
                        java.util.concurrent.TimeUnit.SECONDS);
                control.stopPoll = stopSignals.scheduleWithFixedDelay(() ->
                {
                    try {
                        if (runCoordinator.shouldStop(recordId, token)) {
                            control.signalStop();
                        }
                    } catch (RuntimeException unavailable) {
                        control.signalStop();
                    }
                }, 0, executionProperties.getStopPoll().toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
                executor.execute(() -> {
                    control.attach();
                    try {
                        execute(record, control);
                    } finally {
                        control.detach();
                        control.cancelMonitors();
                        localRuns.remove(token, control);
                        capacity.release();
                    }
                });
                handedOff = true;
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                control.cancelMonitors();
                localRuns.remove(token, control);
                runCoordinator.releaseForCapacity(recordId, token);
                log.info("ETL 执行器暂满，已受理记录保留等待重试 recordId={}", recordId);
            }
        } finally {
            if (!handedOff) {
                capacity.release();
            }
        }
    }

    /** 停止请求可在任意节点受理；独立监控线程负责通知真正持有执行的节点。 */
    public void stop(Long recordId) {
        runCoordinator.requestStop(recordId);
    }

    /** 全局控制面只扫描身份；每项恢复显式进入所属租户。 */

    public void recoverExpired() {
        scanRecords(recordMapper.selectExpiredCandidates(executionProperties.getScanBatch()),
                "recover", record -> runCoordinator.recoverExpired(record.getId()));
    }

    /**
     * 派发等待中。
     */
    public void dispatchWaiting() {
        scanRecords(recordMapper.selectDispatchCandidates(executionProperties.getScanBatch(),
                        executionProperties.getPlatformActive(),
                        executionProperties.getPerDataSourceActive()),
                "dispatch", record -> submit(record.getId()));
    }

    /**
     * 评估质量。
     *
     * @param record 运行记录
     */
    private void evaluateQuality(EtlRecordDO record) {
        var claim = runCoordinator.claimQuality(record.getId());
        if (claim.isEmpty()) {
            return;
        }
        EtlRecordDO owned = claim.get();
        var result = qualityGateService.evaluate("ETL:" + owned.getId(), owned.getQualityTaskId(),
                owned.getExecutionUserId());
        runCoordinator.completeQuality(owned, result);
    }

    /**
     * 对账质量。
     */
    public void reconcileQuality() {
        operationsMetrics.refresh();
        qualityEvidenceService.relayPending();
        targetBatchService.relayPending();
        replayService.relay();
        scanRecords(recordMapper.selectQualityCandidates(executionProperties.getScanBatch(),
                executionProperties.getAutomaticFailureLimit()), "quality", this::evaluateQuality);
    }

    /** 独立候选继续处理；汇总失败交给 XXL-Job 告警，不伪造整批成功。 */
    private void scanRecords(List<EtlRecordDO> candidates, String phase,
            java.util.function.Consumer<EtlRecordDO> process) {
        int failures = 0;
        for (EtlRecordDO record : candidates) {
            try {
                process.accept(record);
            } catch (RuntimeException failure) {
                failures++;
                log.warn("ETL 扫描项失败，等待持久状态恢复 phase={} recordId={} causeType={}",
                        phase, record.getId(), failure.getClass().getSimpleName());
            }
        }
        if (failures > 0) {
            throw new IllegalStateException("ETL " + phase + " failed records=" + failures);
        }
    }

    /**
     * 判断当前运行是否已请求停止。
     *
     * @param record 运行记录
     * @param control 控制
     * @return 处理是否成功
     */
    private boolean stopRequested(EtlRecordDO record, LocalRun control) {
        return control.stopping.get() || runCoordinator.shouldStop(record.getId(), record.getExecutionToken());
    }

    /**
     * 执行ETL 流程处理。
     *
     * @param record 运行记录
     * @param control 控制
     */
    private void execute(EtlRecordDO record, LocalRun control) {
        StringBuilder pipelineLog = new StringBuilder();
        Map<String, StringBuilder> nodeLogs = new ConcurrentHashMap<>();
        Map<String, Long> nodeRecordIds = new ConcurrentHashMap<>();
        boolean effectsStarted = false;
        boolean terminated = true;
        int finalStatus = EtlRecordDO.RUN_STATUS_FAILED;
        cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect effect =
                cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.NONE;
        try {
            if (stopRequested(record, control)) {
                clearInterruptFor(
                        () -> runCoordinator.releaseBeforeExecution(record.getId(), record.getExecutionToken()));
                return;
            }
            JSONObject snapshot = JSONUtil.parseObj(record.getConfigJson());
            EtlDag dag = EtlDag.build(
                    EtlGraphConverter.toNodeDefs(toListOfMap(snapshot.getJSONArray(
                            snapshot.containsKey("executionNodes") ? "executionNodes" : "nodes"))),
                    EtlGraphConverter.toEdgeDefs(toListOfMap(snapshot.getJSONArray(
                            snapshot.containsKey("executionEdges") ? "executionEdges" : "edges"))));
            // 抢占时再次校验：当前工作节点可能不同于发布或重放准入节点。
            // 运行器不可用时，不得创建目标端或文件回执、节点占位记录，也不得启动副作用。
            scriptAdmissionService.requireAvailable(dag.getNodes());
            var sqlScriptPlans = assertNodePermissions(record, dag);
            var policyPlans = policyPlanService.decode(
                    JsonUtils.parseObject(record.getConfigJson(), Map.class).get("governancePlans"));
            policyPlanService.authorize(policyPlans, record.getExecutionUserId());
            Map<String, String> params = resolveParams(snapshot.getJSONArray("parameters"),
                    snapshot.getJSONObject("parameterValues"));
            var fileSources = fileSourceProofService.prepare(record, dag, params);
            var rowPolicyPort = policyPlanService.createPort(policyPlans, record);
            var stagedOutputPort = targetBatchService.createPort(record, dag, params);
            var fileOutputPort = fileSources.wrapFiles(fileRecoveryService.createPort(record, dag, params));
            if (!runCoordinator.writeOwned(record.getId(), record.getExecutionToken(), () -> {
                // PREPARE 阶段可能安全重领；仅逻辑清理该运行上次尚未执行的节点占位。
                nodeRecordMapper.deleteByRecordId(record.getId());
                for (EtlNodeDef node : dag.getNodes()) {
                    EtlNodeRecordDO nodeRecord = EtlNodeRecordDO.builder()
                            .recordId(record.getId()).pipelineId(record.getPipelineId())
                            .nodeNo(node.getNo()).nodeName(node.getName()).toolCode(node.getToolCode())
                            .runStatus(EtlRecordDO.RUN_STATUS_WAITING).readCount(0L).writeCount(0L).build();
                    nodeRecordMapper.insert(nodeRecord);
                    nodeRecordIds.put(node.getNo(), nodeRecord.getId());
                    nodeLogs.put(node.getNo(), new StringBuilder());
                }
            })) {
                return;
            }
            if (stopRequested(record, control) || !runCoordinator.startEffects(record.getId(),
                    record.getExecutionToken())) {
                clearInterruptFor(
                        () -> runCoordinator.releaseBeforeExecution(record.getId(), record.getExecutionToken()));
                return;
            }
            effectsStarted = true;
            // 在调用引擎前已持久写入副作用阶段；此后的异常没有退出证据时只能记 UNKNOWN。
            terminated = false;
            EtlRunListener listener = fileSources.wrapListener(
                    buildListener(record.getId(), record.getExecutionToken(), pipelineLog, nodeLogs, nodeRecordIds));
            EtlRunResult result = pipelineEngine.execute(dag, EtlRunOptions.builder().params(params)
                    .sqlScriptPlans(sqlScriptPlans)
                    .readPolicyPort(fileSources.wrapRead(nodeAccessService.createReadPort(record.getExecutionUserId())))
                    .captureSourceOrigins(true)
                    .stagedOutputPort(stagedOutputPort)
                    .fileOutputPort(fileOutputPort)
                    .connectionResolver(databaseId -> {
                        executionAccessService.requireDatabase(databaseId);
                        return fileSources.checkConnection(databaseId, connectionResolverAdapter.resolve(databaseId));
                    }).apiPort(apiPortAdapter).listener(listener).rowPolicyPort(rowPolicyPort)
                    .runtimeSampleMillis(RUNTIME_SAMPLE_MILLIS)
                    .stopRequested(() -> stopRequested(record, control))
                    .taskWrapper(task -> () -> task.run())
                    .build());
            terminated = !result.isTerminationUnconfirmed();
            for (var file : result.getFileOutputStates().entrySet()) {
                fenced(record.getId(), record.getExecutionToken(),
                        () -> nodeRecordMapper.updateById(EtlNodeRecordDO.builder()
                                .id(nodeRecordIds.get(file.getKey())).fileOutputState(file.getValue()).build()));
            }
            if (result.getFirstError() != null) {
                appendLog(pipelineLog, result.getFirstError());
            }
            boolean publishedFiles = result.getFileOutputStates().values().stream()
                    .anyMatch(state -> state.startsWith("PUBLISHED"));
            boolean unresolvedFiles = result.getFileOutputStates().values().stream()
                    .anyMatch(state -> java.util.Set.of("UNKNOWN", "CLEANUP_PENDING", "PUBLISHED_CLEANUP_PENDING")
                            .contains(state));
            finalStatus = !terminated ? EtlRecordDO.RUN_STATUS_UNKNOWN
                    : result.isSuccess() ? EtlRecordDO.RUN_STATUS_SUCCESS
                    : result.isStopped() ? EtlRecordDO.RUN_STATUS_STOPPED : EtlRecordDO.RUN_STATUS_FAILED;
            effect = !terminated ? cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.UNKNOWN
                    : result.isSuccess() ? cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.COMPLETE
                    : cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.PARTIAL;
            if (terminated && !result.isSuccess() && stagedOutputPort != null && !publishedFiles && !unresolvedFiles) {
                effect = cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.NONE;
            }
            if (unresolvedFiles) {
                finalStatus = EtlRecordDO.RUN_STATUS_UNKNOWN;
                effect = cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.UNKNOWN;
                appendLog(pipelineLog, "文件发布或清理结果尚未确认，请核对持久文件回执");
            }
            if (terminated && result.isSuccess() && !unresolvedFiles && targetBatchService.hasManagedOutput(dag)) {
                if (!targetBatchService.approve(record, dag)) {
                    finalStatus = EtlRecordDO.RUN_STATUS_FAILED;
                    effect = publishedFiles ? cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.PARTIAL
                            : cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.NONE;
                    appendLog(pipelineLog, "批次未满足整体准入策略，目标数据未提交");
                } else if (!targetBatchService.commitApproved(record)) {
                    finalStatus = EtlRecordDO.RUN_STATUS_UNKNOWN;
                    effect = cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.UNKNOWN;
                    appendLog(pipelineLog, "图执行已退出，目标提交等待稳定批次回执对账");
                }
            }
        } catch (Throwable ex) {
            log.warn("ETL 执行异常 recordId={}, stage={}", record.getId(),
                    effectsStarted ? "EXECUTION_STARTED" : "PREPARE");
            appendLog(pipelineLog,
                    effectsStarted ? "执行异常，外部写入及资源退出需核查" : "执行准备失败，未开始外部写入");
            if (effectsStarted) {
                finalStatus = EtlRecordDO.RUN_STATUS_UNKNOWN;
                terminated = false;
                effect = cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.UNKNOWN;
            }
        }
        // 引擎已返回或保守判定 UNKNOWN；随后处理的是独立对账，旧监控不可再中断该线程。
        // detach 与 signalStop 使用同一监视器，覆盖已经进入的轮询回调竞态。
        control.detach();
        control.cancelMonitors();
        final int outcome = finalStatus;
        final boolean exitConfirmed = terminated;
        final var effects = effect;
        boolean interrupted = Thread.interrupted();
        try {
            boolean finished = runCoordinator.finish(record.getId(), record.getExecutionToken(), outcome,
                    effects, exitConfirmed, snapshotLog(pipelineLog));
            if (finished && outcome == EtlRecordDO.RUN_STATUS_SUCCESS && record.getQualityTaskId() != null) {
                evaluateQuality(record);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 清理中断对应。
     *
     * @param operation 操作
     */
    private static void clearInterruptFor(Runnable operation) {
        boolean interrupted = Thread.interrupted();
        try {
            operation.run();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 固定ETL 流程的日志。
     *
     * @param log 日志
     * @return 固定ETL 流程的日志文本
     */
    private static String snapshotLog(StringBuilder log) {
        synchronized (log) {
            return log.toString();
        }
    }

    /**
     * 构造绑定租约持有者和有效期的更新条件，隔离已失去执行权的实例。
     *
     * @param recordId 记录编号
     * @param token 当前执行或租约令牌
     * @param databaseWrite 数据源写入
     */
    private void fenced(Long recordId, String token, Runnable databaseWrite) {
        if (!runCoordinator.writeOwned(recordId, token, databaseWrite)) {
            throw new java.util.concurrent.CancellationException("ETL execution ownership lost");
        }
    }

    /**
     * 引擎监听器：流程/节点日志与状态实时落库（设计器 2 秒轮询展示）
     */
    private EtlRunListener buildListener(Long recordId, String executionToken, StringBuilder pipelineLog,
            Map<String, StringBuilder> nodeLogs,
            Map<String, Long> nodeRecordIds) {
        return new EtlRunListener() {

            /**
             * 处理流程日志。
             *
             * @param line 行
             */
            @Override
            public void onPipelineLog(String line) {
                appendLog(pipelineLog, line);
                fenced(recordId, executionToken,
                        () -> recordMapper.update(null, Wrappers.lambdaUpdate(EtlRecordDO.class)
                                .set(EtlRecordDO::getLog, snapshotLog(pipelineLog))
                                .eq(EtlRecordDO::getId, recordId)
                                .eq(EtlRecordDO::getExecutionToken, executionToken)
                                .in(EtlRecordDO::getRunStatus, 1, 2)));
            }

            /**
             * 处理节点开始。
             *
             * @param nodeNo 节点序号
             */
            @Override
            public void onNodeStart(String nodeNo) {
                fenced(recordId, executionToken, () -> nodeRecordMapper.updateById(EtlNodeRecordDO.builder()
                        .id(nodeRecordIds.get(nodeNo))
                        .runStatus(EtlRecordDO.RUN_STATUS_RUNNING)
                        .startTime(LocalDateTime.now())
                        .build()));
            }

            /**
             * 处理节点日志。
             *
             * @param nodeNo 节点序号
             * @param line 行
             */
            @Override
            public void onNodeLog(String nodeNo, String line) {
                StringBuilder buffer = nodeLogs.get(nodeNo);
                if (buffer == null) {
                    return;
                }
                synchronized (buffer) {
                    if (buffer.length() < 8000) {
                        buffer.append(StrUtil.maxLength(line, 2000)).append('\n');
                    }
                    fenced(recordId, executionToken, () -> nodeRecordMapper.updateById(EtlNodeRecordDO.builder()
                            .id(nodeRecordIds.get(nodeNo))
                            .log(buffer.toString())
                            .build()));
                }
                appendLog(pipelineLog, line);
            }

            /**
             * 处理节点成功。
             *
             * @param nodeNo 节点序号
             * @param readCount 读取数量
             * @param writeCount 写入数量
             */
            @Override
            public void onNodeSuccess(String nodeNo, long readCount, long writeCount) {
                fenced(recordId, executionToken, () -> nodeRecordMapper.updateById(EtlNodeRecordDO.builder()
                        .id(nodeRecordIds.get(nodeNo))
                        .runStatus(EtlRecordDO.RUN_STATUS_SUCCESS)
                        .endTime(LocalDateTime.now())
                        .readCount(readCount)
                        .writeCount(writeCount)
                        .build()));
            }

            /**
             * 处理节点失败。
             *
             * @param nodeNo 节点序号
             * @param errorMessage 错误消息
             */
            @Override
            public void onNodeFail(String nodeNo, String errorMessage) {
                StringBuilder buffer = nodeLogs.get(nodeNo);
                String logText = buffer == null ? errorMessage
                        : buffer + "\n" + StrUtil.nullToEmpty(errorMessage);
                fenced(recordId, executionToken, () -> nodeRecordMapper.updateById(EtlNodeRecordDO.builder()
                        .id(nodeRecordIds.get(nodeNo))
                        .runStatus(EtlRecordDO.RUN_STATUS_FAILED)
                        .endTime(LocalDateTime.now())
                        .log(StrUtil.maxLength(logText, 8000))
                        .build()));
            }

            /**
             * 处理节点指标。
             *
             * @param nodeNo 节点序号
             * @param readCount 读取数量
             * @param writeCount 写入数量
             * @param portCounts 端口数量
             */
            @Override
            public void onNodeMetrics(String nodeNo, long readCount, long writeCount, Map<String, Long> portCounts) {
                fenced(recordId, executionToken, () -> nodeRecordMapper.updateById(EtlNodeRecordDO.builder()
                        .id(nodeRecordIds.get(nodeNo)).readCount(readCount).writeCount(writeCount)
                        .portCountsJson(JSONUtil.toJsonStr(portCounts)).build()));
            }

            /**
             * 处理运行时样本。
             *
             * @param sample 样本
             */
            @Override
            public void onRuntimeSample(cn.iocoder.yudao.framework.etl.core.runtime.EtlRuntimeSample sample) {
                String json = JsonUtils.toJsonString(sample);
                fenced(recordId, executionToken,
                        () -> recordMapper.update(null, Wrappers.lambdaUpdate(EtlRecordDO.class)
                                .set(EtlRecordDO::getRuntimeMetricsJson, json,
                                        "typeHandler=cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler")
                                .eq(EtlRecordDO::getId, recordId)
                                .eq(EtlRecordDO::getExecutionToken, executionToken)
                                .in(EtlRecordDO::getRunStatus, EtlRecordDO.RUN_STATUS_WAITING,
                                        EtlRecordDO.RUN_STATUS_RUNNING)));
            }
        };
    }

    /**
     * 追加ETL 流程的日志。
     *
     * @param pipelineLog 流程日志
     * @param line 行
     */
    private void appendLog(StringBuilder pipelineLog, String line) {
        synchronized (pipelineLog) {
            if (pipelineLog.length() < 64000) {
                pipelineLog.append(StrUtil.maxLength(line, 2000)).append('\n');
            }
        }
    }

    /**
     * 确认节点权限。
     *
     * @param record 运行记录
     * @param dag DAG
     * @return 处理后的键值映射
     */
    private Map<String, cn.iocoder.yudao.framework.etl.core.engine.EtlSqlScriptBinding> assertNodePermissions(
            EtlRecordDO record, EtlDag dag) {
        Long userId = record.getExecutionUserId();
        executionAccessService.requireUserPermission(userId,
                cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService.PERMISSION_ETL_RUN,
                cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService.PERMISSION_ETL_RELEASE);
        JSONObject snapshot = JSONUtil.parseObj(record.getConfigJson());
        environmentService.validateFrozen(snapshot, userId);
        return nodeAccessService.authorize(dag.getNodes(), userId,
                resolveParams(snapshot.getJSONArray("parameters"), snapshot.getJSONObject("parameterValues")));
    }

    /**
     * 解析参数。
     *
     * @param parameters 参数定义或取值
     * @param parameterValues 参数值
     * @return 处理后的键值映射
     */
    private Map<String, String> resolveParams(JSONArray parameters, JSONObject parameterValues) {
        return EtlRunParameterResolver.resolve(parameters, parameterValues);
    }

    /**
     * 转换为列表对应映射。
     *
     * @param array 数组
     * @return 转换为列表对应映射结果集合
     */
    private List<Map<String, Object>> toListOfMap(JSONArray array) {
        if (array == null) {
            return List.of();
        }
        return array.stream()
                .map(item -> (Map<String, Object>) ((JSONObject) item))
                .toList();
    }

}
