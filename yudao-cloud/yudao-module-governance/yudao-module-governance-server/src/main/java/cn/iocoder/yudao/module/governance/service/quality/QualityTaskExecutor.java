package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_RUN_FORBIDDEN;

import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO;

/**
 * 质量任务执行器：把任务下的「检查对象 × 规则」真实回源执行
 *
 * <p>执行链路：解析数据源连接（RPC 现取现用）→ 引擎生成统计 SQL → 回源执行
 * → 写检测结果明细（audit_log）→ 失败规则按需留存问题数据（定位SQL，不复制源数据）
 * → 汇总任务实例 → 严重规则未通过时产生告警
 *
 * @author Li
 */
@Slf4j
@Component
public class QualityTaskExecutor {

    @Resource
    private DbSwitchRuntime dbSwitchRuntime;

    private static final DateTimeFormatter INSTANCE_TIME =
            DateTimeFormatter.ofPattern(DatePattern.PURE_DATETIME_PATTERN);

    /** 问题明细定位 SQL 的行数上限 */
    private static final int ISSUE_DETAIL_LIMIT = 1000;

    /** 单条规则 SQL 执行超时（秒） */
    private static final int QUERY_TIMEOUT_SECONDS = 300;

    /** 规则执行出错重试间隔（毫秒） */
    private static final long RETRY_BACKOFF_MS = 1000L;

    @Resource
    private QualityTaskInstanceMapper instanceMapper;

    @Resource
    private QualitySyncEvidenceCollector syncEvidenceCollector;

    @Resource
    private QualityTaskObjectMapper taskObjectMapper;

    @Resource
    private QualityTaskRuleMapper taskRuleMapper;

    @Resource
    private QualityCheckObjectMapper checkObjectMapper;

    @Resource
    private QualityRuleMapper ruleMapper;

    @Resource
    private QualityRuleSetItemMapper ruleSetItemMapper;

    @Resource
    private StandardQualityRuleBindingService standardRuleBindings;

    @Resource
    private CurrentStandardQualityRules currentStandardRules;

    @Resource
    private QualityTaskEvaluationService evaluationService;

    @Resource
    private QualityAuditLogMapper auditLogMapper;

    @Resource
    private QualityIssueDataMapper issueDataMapper;

    @Resource
    private QualityAlertMapper alertMapper;

    @Resource
    private MasterDatabaseResolver databaseResolver;

    @Resource
    private AuditSqlRegistry auditSqlRegistry;

    @Resource
    private NotifyMessageSendApi notifyMessageSendApi;

    private ExecutorService executor;

    /** 研发质量门禁使用独立有界池，避免与旧并行质量任务互相等待。 */
    private java.util.concurrent.ThreadPoolExecutor gateExecutor;

    private java.util.concurrent.ScheduledExecutorService gateHeartbeat;

    @org.springframework.beans.factory.annotation.Value("${dataforge.quality.max-concurrent:2}")
    private int gateConcurrency = 2;

    @Resource
    private QualityRunAuthorizationService gateAuthorization;

    @lombok.Data
    public static class GateDefinition {

        private QualityTaskDO task;

        private List<QualityCheckObjectDO> objects;

        private List<QualityRuleDO> rules;

        /** 由服务端构造的单个已提交 ETL 输出批次的不可变范围。 */
        private EtlBatchScope etlBatchScope;
    }

    @lombok.Data
    public static class EtlBatchScope {

        private String batchColumn;

        private String batchValue;

        private String statusColumn;

        private List<String> admittedStatuses;

        private Long admittedRowCount;
    }

    /**
     * 校验质量任务的门禁定义，不满足约束时拒绝当前操作。
     */
    public void validateGateDefinition(QualityTaskDO task) {
        List<QualityCheckObjectDO> objects = loadCheckObjects(task.getId());
        List<QualityRuleDO> rules = loadRules(task, objects);
        if (objects.isEmpty() || rules.isEmpty() || objects.size() > 20 || rules.size() > 100) {
            throw exception(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_RUN_NOT_READY);
        }
    }

    /** 复用实际 SQL 生成路径预检 ETL 新任务；不读取目标数据，也不启动任何检测。 */
    public void validateEtlDraftRules(MasterDatabaseResolver.ConnectionInfo connection,
            QualityCheckObjectDO object, List<QualityRuleDO> rules, List<String> physicalColumns) {
        for (var rule : rules) {
            var generator = auditSqlRegistry.getGenerator(rule.getStrategyKey());
            if (generator == null) {
                throw exception(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_RUN_NOT_READY);
            }
            if (generator.getExecutionMode() == AuditSqlGenerator.ExecutionMode.METADATA) {
                throw new IllegalArgumentException("结构与元信息稽查以字段计数，不能用于 ETL 行准入");
            }
            if ("RECORD_COMPLETENESS_VALIDATION".equals(generator.getStrategyKey())) {
                generator.validateConfig(buildContext(connection, object, rule));
                continue;
            }
            var context = buildContext(connection, object, rule);
            if (QualityRuleDO.CHECK_LEVEL_FIELD.equals(rule.getCheckLevel()) && context.resolveColumns().isEmpty()) {
                throw exception(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_RUN_NOT_READY);
            }
            if (!context.resolveColumns().isEmpty()) {
                String reference = cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.qualifiedTableName(
                        connection.getProductType(), connection.getSchema(), object.getTableName());
                cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.restrictProjection(
                        connection.getProductType(),
                        "SELECT " + String.join(",", context.resolveColumns()) + " FROM " + reference,
                        connection.getSchema(), object.getTableName(), physicalColumns, new HashSet<>(physicalColumns));
            }
            generator.buildCountSql(context);
            generator.buildErrorDataSql(context, 1);
        }
    }

    /**
     * 生成质量门禁执行时使用的冻结定义。
     */
    public String snapshotGateDefinition(QualityTaskDO task) {
        return snapshotDefinition(task, true);
    }

    /**
     * 生成 ETL 批次质量执行所需的冻结定义。
     */
    public String snapshotEtlBatchDefinition(QualityTaskDO task, QualityCheckObjectDO boundObject,
            String batchColumn, String batchValue, String statusColumn,
            List<String> admittedStatuses, Long admittedRowCount) {
        List<QualityRuleDO> rules = loadRules(task, boundObject == null ? List.of() : List.of(boundObject));
        if (rules.isEmpty() || rules.size() > 100 || boundObject == null || admittedStatuses == null
                || admittedStatuses.isEmpty() || admittedRowCount == null || admittedRowCount <= 0) {
            throw exception(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_RUN_NOT_READY);
        }
        for (QualityRuleDO rule : rules) {
            var generator = auditSqlRegistry.getGenerator(rule.getStrategyKey());
            if (generator == null || generator.getExecutionMode() == AuditSqlGenerator.ExecutionMode.METADATA) {
                throw exception(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_RUN_NOT_READY);
            }
        }
        QualityTaskDO frozen = cn.iocoder.yudao.framework.common.util.object.BeanUtils.toBean(task,
                QualityTaskDO.class);
        frozen.setExecuteStrategy(QualityTaskDO.STRATEGY_SERIAL);
        frozen.setRetryCount(0);
        EtlBatchScope scope = new EtlBatchScope();
        scope.setBatchColumn(batchColumn);
        scope.setBatchValue(batchValue);
        scope.setStatusColumn(statusColumn);
        scope.setAdmittedStatuses(List.copyOf(admittedStatuses));
        scope.setAdmittedRowCount(admittedRowCount);
        GateDefinition definition = new GateDefinition();
        definition.setTask(frozen);
        definition.setObjects(List.of(boundObject));
        definition.setRules(rules);
        definition.setEtlBatchScope(scope);
        return JsonUtils.toJsonString(definition);
    }

    /**
     * 生成指定质量任务的冻结执行定义。
     */
    public String snapshotTaskDefinition(QualityTaskDO task) {
        return snapshotDefinition(task, false);
    }

    /**
     * 汇总质量对象、规则与参数形成冻结执行快照。
     */
    private String snapshotDefinition(QualityTaskDO task, boolean gateLimits) {
        GateDefinition definition = new GateDefinition();
        QualityTaskDO frozen = cn.iocoder.yudao.framework.common.util.object.BeanUtils.toBean(task,
                QualityTaskDO.class);
        if (gateLimits) {
            frozen.setExecuteStrategy(QualityTaskDO.STRATEGY_SERIAL);
            frozen.setRetryCount(0);
        }
        definition.setTask(frozen);
        definition.setObjects(loadCheckObjects(task.getId()));
        definition.setRules(loadRules(task, definition.getObjects()));
        if (evaluationService != null) {
            evaluationService.freezeObjects(definition);
        }
        if (definition.getObjects().isEmpty() || definition.getRules().isEmpty()) {
            throw exception(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_RUN_NOT_READY);
        }
        if (gateLimits && (definition.getObjects().size() > 20 || definition.getRules().size() > 100)) {
            throw exception(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_RUN_NOT_READY);
        }
        return JsonUtils.toJsonString(definition);
    }

    /** 队列记录持久化在先；真正开始执行时CAS抢占，重复扫描不会重复执行。 */
    public void dispatchGate(Long instanceId) {
        QualityTaskInstanceDO instance = instanceMapper.selectById(instanceId);
        if (instance == null || !QualityTaskInstanceDO.STATUS_QUEUED.equals(instance.getInstanceStatus())) {
            return;
        }

        try {
            gateExecutor.execute(() -> {
                LocalDateTime now = LocalDateTime.now();
                try {
                    if (instance.getExecutionUserId() != null) {
                        gateAuthorization.require(instance.getTaskId(), instance.getExecutionUserId());
                    }
                } catch (cn.iocoder.yudao.framework.common.exception.ServiceException denied) {
                    instanceMapper.update(null, Wrappers.lambdaUpdate(QualityTaskInstanceDO.class)
                            .set(QualityTaskInstanceDO::getInstanceStatus, "FAILED")
                            .set(QualityTaskInstanceDO::getEndTime, now)
                            .set(QualityTaskInstanceDO::getErrorFlag, "执行前授权或任务可用性校验未通过")
                            .eq(QualityTaskInstanceDO::getId, instanceId)
                            .eq(QualityTaskInstanceDO::getInstanceStatus, QualityTaskInstanceDO.STATUS_QUEUED));
                    return;
                } catch (Exception unavailable) {
                    // RPC暂不可用时保留队列，由恢复作业重试，绝不绕过授权。
                    log.warn("[qualityGate][实例({})等待执行身份校验恢复]", instanceId);
                    return;
                }
                int claimed = instanceMapper.update(null, Wrappers.lambdaUpdate(QualityTaskInstanceDO.class)
                        .set(QualityTaskInstanceDO::getInstanceStatus, QualityTaskInstanceDO.STATUS_RUNNING)
                        .set(QualityTaskInstanceDO::getStartTime, now)
                        .set(QualityTaskInstanceDO::getLeaseUntil, now.plusMinutes(3))
                        .eq(QualityTaskInstanceDO::getId, instanceId)
                        .eq(QualityTaskInstanceDO::getInstanceStatus, QualityTaskInstanceDO.STATUS_QUEUED));
                if (claimed == 0) {
                    return;
                }
                java.util.concurrent.ScheduledFuture<?> heartbeat = gateHeartbeat.scheduleAtFixedRate(() ->
                {
                    try {
                        instanceMapper.update(null, Wrappers.lambdaUpdate(QualityTaskInstanceDO.class)
                                .set(QualityTaskInstanceDO::getLeaseUntil, LocalDateTime.now().plusMinutes(3))
                                .eq(QualityTaskInstanceDO::getId, instanceId)
                                .eq(QualityTaskInstanceDO::getInstanceStatus, "RUNNING"));
                    } catch (Exception ex) {
                        log.warn("[qualityGate][质检实例({})心跳失败]", instanceId);
                    }
                }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    GateDefinition definition = JsonUtils.parseObject(instance.getDefinitionSnapshot(),
                            GateDefinition.class);
                    if (definition == null || definition.getTask() == null) {
                        finishInstance(instanceId, QualityTaskInstanceDO.STATUS_FAILED, now, "缺少冻结检测计划",
                                List.of());
                    } else {
                        execute(instanceId, definition.getTask(), definition);
                    }
                } catch (Exception ex) {
                    finishInstance(instanceId, QualityTaskInstanceDO.STATUS_FAILED, now, "质量检查执行失败", List.of());
                } finally {
                    heartbeat.cancel(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            // 保持QUEUED，由恢复作业再次调度；绝不将排队失败报告为已完成。
            log.info("[qualityGate][队列已满，实例({})保留待执行]", instanceId);
        }
    }

    /** 运行中：taskId → instanceId（防同任务并发） */
    private final ConcurrentMap<Long, Long> runningTasks = new ConcurrentHashMap<>();

    /**
     * 初始化质量任务使用的运行资源。
     */
    @PostConstruct
    public void init() {
        if (gateConcurrency < 1 || gateConcurrency > 16) {
            throw new IllegalArgumentException("质量门禁并发必须在1到16之间");
        }
        gateExecutor = new java.util.concurrent.ThreadPoolExecutor(gateConcurrency, gateConcurrency, 0,
                java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(64),
                runnable -> {
                    Thread t = new Thread(runnable, "quality-gate");
                    t.setDaemon(true);
                    return t;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        // 内部租约心跳；每节点仅维护本节点正在执行的实例，不承担业务调度。
        gateHeartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "quality-gate-heartbeat");
            t.setDaemon(true);
            return t;
        });
        AtomicInteger counter = new AtomicInteger();
        executor = new java.util.concurrent.ThreadPoolExecutor(2, 8, 60,
                java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(64),
                runnable -> {
                    Thread thread = new Thread(runnable, "quality-task-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 释放质量任务持有的运行资源。
     */
    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
        if (gateExecutor != null) {
            gateExecutor.shutdownNow();
        }
        if (gateHeartbeat != null) {
            gateHeartbeat.shutdownNow();
        }
    }

    /**
     * 判断质量任务是否处于运行状态。
     */
    public boolean isRunning(Long taskId) {
        return instanceMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QualityTaskInstanceDO>()
                        .eq(QualityTaskInstanceDO::getTaskId, taskId)
                        .in(QualityTaskInstanceDO::getInstanceStatus,
                                QualityTaskInstanceDO.STATUS_QUEUED, QualityTaskInstanceDO.STATUS_RUNNING)) > 0;
    }

    /**
     * 提交任务执行：创建实例并异步执行
     *
     * @return 实例编号；任务已在运行时返回 null
     */
    public Long submit(QualityTaskDO task, String runType, String execUser) {
        Long executionUserId = QualityTaskInstanceDO.RUN_TYPE_SCHEDULE.equals(runType) ? task.getOwnerUserId()
                : cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId();
        gateAuthorization.require(task.getId(), executionUserId);
        LocalDateTime now = LocalDateTime.now();
        int runNo = Math.toIntExact(instanceMapper.selectCountByTaskId(task.getId())) + 1;
        String snapshot = snapshotTaskDefinition(task);
        QualityTaskInstanceDO instance = QualityTaskInstanceDO.builder()
                .taskId(task.getId())
                .instanceName(task.getTaskName() + "-" + runNo + "-" + now.format(INSTANCE_TIME))
                .runType(runType)
                .instanceStatus(QualityTaskInstanceDO.STATUS_QUEUED)
                .definitionSnapshot(snapshot)
                .scheduleTime(QualityTaskInstanceDO.RUN_TYPE_SCHEDULE.equals(runType) ? now : null)
                .runCount(runNo)
                .execUser(execUser)
                .executionUserId(executionUserId)
                .host(resolveHost())
                .totalCount(0L).passCount(0L).failCount(0L)
                .build();
        instanceMapper.insert(instance);
        dispatchGate(instance.getId());
        return instance.getId();
    }

    // ==================== 任务执行 ====================

    /**
     * 执行质量任务处理流程，并维护相应运行结果。
     */
    private void execute(Long instanceId, QualityTaskDO task) {
        execute(instanceId, task, null);
    }

    /**
     * 执行质量任务处理流程，并维护相应运行结果。
     */
    private void execute(Long instanceId, QualityTaskDO task, GateDefinition definition) {
        LocalDateTime start = LocalDateTime.now();
        try {
            List<QualityCheckObjectDO> objects = definition == null ? loadCheckObjects(
                    task.getId()) : definition.getObjects();
            List<QualityRuleDO> rules = definition == null ? loadRules(task, objects) : definition.getRules();
            if (objects.isEmpty() || rules.isEmpty()) {
                finishInstance(instanceId, QualityTaskInstanceDO.STATUS_FAILED, start,
                        "任务未配置检查对象或检查规则", List.of());
                return;
            }
            // 数据源连接信息按连接编号解析一次
            Map<Long, MasterDatabaseResolver.ConnectionInfo> connMap = new LinkedHashMap<>();
            for (QualityCheckObjectDO object : objects) {
                connMap.computeIfAbsent(object.getDatasourceConnId(),
                        connId -> databaseResolver.resolve(connId, QUALITY_CHECK_OBJECT_NOT_EXISTS));
            }
            List<QualityAuditLogDO> auditLogs = Collections.synchronizedList(new ArrayList<>());
            if (QualityTaskDO.STRATEGY_PARALLEL.equals(task.getExecuteStrategy()) && objects.size() > 1) {
                // 并行策略：按检查对象并行执行，各自独立建连

                List<CompletableFuture<Void>> futures = objects.stream()
                        .map(object -> CompletableFuture.runAsync(() ->
                                executeObject(instanceId, task, connMap.get(object.getDatasourceConnId()),
                                        object, rules, auditLogs,
                                        definition == null ? null : definition.getEtlBatchScope()), executor))
                        .toList();
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } else {
                // 串行策略（默认）
                for (QualityCheckObjectDO object : objects) {
                    executeObject(instanceId, task, connMap.get(object.getDatasourceConnId()),
                            object, rules, auditLogs, definition == null ? null : definition.getEtlBatchScope());
                }
            }
            finishInstance(instanceId, QualityTaskInstanceDO.STATUS_SUCCESS, start,
                    null, new ArrayList<>(auditLogs));
            createAlertIfNecessary(task, new ArrayList<>(auditLogs), rules);
        } catch (Exception ex) {
            log.warn("[execute][质量任务({}) 实例({}) 执行失败]", task.getId(), instanceId, ex);
            finishInstance(instanceId, QualityTaskInstanceDO.STATUS_FAILED, start,
                    StrUtil.maxLength(ex.getMessage(), 480), List.of());
        }
    }

    /**
     * 执行单个检查对象下的全部规则（独立建连，串/并行策略共用）
     */
    private void executeObject(Long instanceId, QualityTaskDO task,
            MasterDatabaseResolver.ConnectionInfo conn,
            QualityCheckObjectDO object, List<QualityRuleDO> rules,
            List<QualityAuditLogDO> auditLogs, EtlBatchScope etlBatchScope) {
        rules = rules.stream().filter(rule -> rule.getExecutionObjectId() == null
                || Objects.equals(rule.getExecutionObjectId(), object.getId())).toList();
        // 使用元数据及复检凭据采用的同一个已解析并固定的驱动，不回退到类路径驱动。
        try (var dataSource = databaseResolver.createDataSource(conn)) {
            for (QualityRuleDO rule : rules) {
                validateStandardRelation(conn, object, rule);
            }
            try (Connection connection = dataSource.getConnection()) {
                for (QualityRuleDO rule : rules) {
                    auditLogs.add(executeRule(instanceId, task, connection, conn, object, rule, etlBatchScope));
                }
            }
        } catch (Exception ex) {
            // 单对象建连失败：为其全部规则补记出错结果，不中断其他对象
            if (conn.getSourceGenerationPin() == null) {
                log.warn("[executeObject][任务({}) 对象({}) 连接失败]", task.getId(), object.getId(), ex);
            } else {
                log.warn("[executeObject][任务({}) 对象({}) 连接失败] {}", task.getId(), object.getId(),
                        managedReadFailure(ex));
            }
            for (QualityRuleDO rule : rules) {
                QualityAuditLogDO auditLog = QualityAuditLogDO.builder()
                        .instanceId(instanceId).taskId(task.getId())
                        .ruleId(rule.getId()).evaluationId(rule.getExecutionEvaluationId()).ruleName(rule.getRuleName())
                        .ruleType(rule.getRuleType())
                        .checkObjectId(object.getId()).tableName(object.getTableName())
                        .checkTime(LocalDateTime.now())
                        .totalCount(0L).failCount(0L).passRate(BigDecimal.ZERO)
                        .resultType(QualityAuditLogDO.RESULT_ERROR)
                        .errorDetail("数据源连接失败：" + (conn.getSourceGenerationPin() == null
                                ? StrUtil.maxLength(ex.getMessage(), 1500) : managedReadFailure(ex)))
                        .build();
                auditLogMapper.insert(auditLog);
                auditLogs.add(auditLog);
            }
        }
    }

    /** 受管源端的数据库厂商诊断不得暴露账号、端点、SQL 或嵌套异常原因。 */
    private static String managedReadFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if (state == null || !state.matches("[A-Z0-9]{5}")) {
                    state = "UNKNOWN";
                }
                return "受管源读取失败 [SQLState=" + state + ", vendorCode=" + sql.getErrorCode() + "]";
            }
        }
        return "受管源读取失败，请按任务/实例编号检查源库状态与部署配置";
    }

    /**
     * 加载检查对象。
     */
    private List<QualityCheckObjectDO> loadCheckObjects(Long taskId) {
        List<Long> objectIds = taskObjectMapper.selectListByTaskId(taskId).stream()
                .map(QualityTaskObjectDO::getObjectId).toList();
        return objectIds.isEmpty() ? List.of() : checkObjectMapper.selectBatchIds(objectIds);
    }

    /**
     * 显式规则按原配置执行；标准模板按检查对象的当前数据元展开，随后整体冻结。
     */
    private List<QualityRuleDO> loadRules(QualityTaskDO task, List<QualityCheckObjectDO> objects) {
        if (evaluationService != null && evaluationService.count(task.getId()) > 0) {
            return evaluationService.executionRules(task.getId());
        }
        List<Long> ruleIds = taskRuleMapper.selectListByTaskId(task.getId()).stream()
                .map(QualityTaskRuleDO::getRuleId).toList();
        if (ruleIds.isEmpty() && task.getRuleSetId() != null) {
            ruleIds = ruleSetItemMapper.selectListByRuleSetId(task.getRuleSetId()).stream()
                    .map(QualityRuleSetItemDO::getRuleId).toList();
        }
        List<QualityRuleDO> configured = ruleIds.isEmpty() ? List.of() : ruleMapper.selectBatchIds(ruleIds).stream()
                .filter(rule -> Objects.equals(rule.getStatus(), QualityRuleDO.STATUS_ENABLE))
                .toList();
        if (StandardQualityRuleMaterializer.ASSET_TASK_CATEGORY.equals(task.getTaskCategory())) {
            return currentStandardRules.assetExecution(task, objects, configured);
        }
        return standardRuleBindings.resolveForExecution(task, objects, configured);
    }

    /**
     * 执行单条规则并写检测结果；失败且未关闭「采集问题明细」时留存定位记录
     */
    private QualityAuditLogDO executeRule(Long instanceId, QualityTaskDO task, Connection connection,
            MasterDatabaseResolver.ConnectionInfo conn,
            QualityCheckObjectDO object, QualityRuleDO rule,
            EtlBatchScope etlBatchScope) {
        LocalDateTime checkTime = LocalDateTime.now();
        long startMs = System.currentTimeMillis();
        QualityAuditLogDO auditLog = QualityAuditLogDO.builder()
                .instanceId(instanceId).taskId(task.getId())
                .ruleId(rule.getId()).evaluationId(rule.getExecutionEvaluationId()).ruleName(rule.getRuleName())
                .ruleType(rule.getRuleType())
                .checkObjectId(object.getId()).tableName(object.getTableName())
                .groupName(StrUtil.blankToDefault(task.getGroupField(), null))
                .checkTime(checkTime)
                .totalCount(0L).failCount(0L).passRate(BigDecimal.ZERO)
                .resultType(QualityAuditLogDO.RESULT_PASS)
                .build();
        AuditSqlGenerator generator = auditSqlRegistry.getGenerator(rule.getStrategyKey());
        boolean customSql = generator == null && StrUtil.isNotBlank(rule.getRuleContent())
                && StrUtil.startWithIgnoreCase(rule.getRuleContent().trim(), "SELECT");
        if (generator == null && (!customSql || etlBatchScope != null)) {
            auditLog.setResultType(QualityAuditLogDO.RESULT_ERROR);
            auditLog.setErrorDetail("规则未配置引擎策略（strategyKey）或自定义查询 SQL，无法执行");
            auditLog.setCostMs(System.currentTimeMillis() - startMs);
            auditLogMapper.insert(auditLog);
            return auditLog;
        }
        // 失败重试：任务的 retry_count 对规则级执行出错生效（判定未通过不重试）
        int maxAttempts = 1 + Math.max(0, task.getRetryCount() == null ? 0 : task.getRetryCount());
        long[] counts = null;
        String locationSql = null;
        AuditRuleContext context = null;
        QualityKeyDefinition keyDefinition = QualityKeyDefinition.from(Map.of());
        Exception lastError = null;
        QualitySyncEvidenceCollector.Observation syncBefore = null;
        OrgRead orgRead = null;
        String pkValue = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // 失败轮次的统计/凭证不能与下一次快照混用，清理成功之前不发布结果。
                counts = null;
                orgRead = null;
                pkValue = null;
                auditLog.setReadEvidence(null);
                Map<String, Object> ruleParameters = parseParams(rule);
                keyDefinition = QualityKeyDefinition.from(ruleParameters);
                LocalDateTime readStartedAt = LocalDateTime.now();
                QualityReadEvidence.KeyScope keyScope = null;
                syncBefore = syncEvidenceCollector == null ? null
                        : syncEvidenceCollector.observe(instanceId, task.getId(), conn, object);
                // RPC 观察在目标快照外；组件拥有读取事务，业务模块只编排领域查询。
                try (var readSnapshot = openReadSnapshot(connection, conn)) {
                    String relationBefore = null;
                    boolean relationUnproven = false;
                    if (generator != null) {
                        try {
                            relationBefore = dbSwitchRuntime.products().metadata(conn.getProductType())
                                    .queryRelationFingerprint(connection, physicalRuleTableName(conn, object, rule),
                                            conn.getSourceGenerationPin());
                        } catch (java.sql.SQLFeatureNotSupportedException unsupported) {
                            // 未验证的产品/关系或身份读取权限不足仍可做普通质检，但不能自动验收。
                            relationUnproven = true;
                        }
                    }
                    if (generator != null) {
                        cn.iocoder.yudao.framework.quality.core.audit.AuditSqlDialect.validateConnection(connection,
                                conn.getProductType());
                        context = buildContext(conn, object, rule);
                        boolean metadata = generator.getExecutionMode() == AuditSqlGenerator.ExecutionMode.METADATA;
                        if (metadata && etlBatchScope != null) {
                            throw new IllegalArgumentException("结构与元信息稽查不能用于 ETL 行准入");
                        }
                        if (metadata || "RECORD_COMPLETENESS_VALIDATION".equals(generator.getStrategyKey())) {
                            evaluationService.populateMetadata(connection, context, generator);
                        }
                        if (etlBatchScope != null) {
                            if ("RECORD_COMPLETENESS_VALIDATION".equals(generator.getStrategyKey())) {
                                var excluded = java.util.Set.of(
                                        cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.quotePhysicalIdentifier(
                                                conn.getProductType(), etlBatchScope.getBatchColumn()),
                                        cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.quotePhysicalIdentifier(
                                                conn.getProductType(), etlBatchScope.getStatusColumn()));
                                var business = context.resolveColumns().stream()
                                        .filter(field -> !excluded.contains(field)).toList();
                                if (business.isEmpty()) {
                                    throw new IllegalArgumentException("整记录检查没有非主键业务字段");
                                }
                                context.setColumns(business);
                                context.setColumn(business.getFirst());
                            }
                            context.addTextEqualsScope(etlBatchScope.getBatchColumn(), etlBatchScope.getBatchValue());
                            context.addTextInScope(etlBatchScope.getStatusColumn(),
                                    etlBatchScope.getAdmittedStatuses());
                        }
                        List<?> checkFields = rule.getExecutionEvaluationId() != null
                                && ruleParameters.get(
                                "columns") instanceof List<?> fields ? fields : context.resolveColumns();
                        auditLog.setCheckField(StrUtil.blankToDefault(CollUtil.join(checkFields, ","), "整表"));
                        if (metadata) {
                            auditLog.setCheckField("表结构（计数单位：字段）");
                        }
                        String countSql = generator.buildCountSql(context);
                        AuditKeySnapshot snapshot = null;
                        if (!metadata && keyDefinition.configured() && keyDefinition.valid()) {
                            try {
                                snapshot = keyDefinition.composite()
                                        ? AuditKeySnapshot.readComposite(connection, generator, context,
                                        keyDefinition.columns(),
                                        AuditKeySnapshot.MAX_KEYS, QUERY_TIMEOUT_SECONDS)
                                        : AuditKeySnapshot.read(connection, generator, context, keyDefinition.column(),
                                        AuditKeySnapshot.MAX_KEYS, QUERY_TIMEOUT_SECONDS);
                                keyScope = QualityReadEvidence.keyScope(keyDefinition, snapshot);
                            } catch (UnsupportedOperationException ignored) {
                                // 未验证产品/复杂键/非行级策略仍执行原统计，但不声称有逐行覆盖证明。
                            }
                        }
                        if (snapshot != null && snapshot.total() >= 0) {
                            countSql = snapshot.sql();
                            counts = new long[]{snapshot.total(), snapshot.fail()};
                        } else {
                            // 超限不使用部分计数。普通统计可继续，但不完整凭证不会被用于验收通过。
                            counts = queryCounts(connection, countSql);
                        }
                        if (etlBatchScope != null && counts[0] != etlBatchScope.getAdmittedRowCount()) {
                            throw new IllegalStateException("ETL批次准入行数与实际受检范围不一致");
                        }
                        auditLog.setResultSql(countSql);
                        locationSql = generator.buildErrorDataSql(context, ISSUE_DETAIL_LIMIT);
                    } else {
                        // 自定义 SQL 规则：规则内容即「问题行查询」，命中行计为失败
                        String errorSql = "SELECT COUNT(*) FROM (" + StrUtil.removeSuffix(
                                rule.getRuleContent().trim(), ";") + ") t__";
                        String totalSql = "SELECT COUNT(*) FROM " + object.getTableName();
                        auditLog.setResultSql(errorSql);
                        long errorCount = querySingleCount(connection, errorSql);
                        long totalCount = querySingleCount(connection, totalSql);
                        counts = new long[]{totalCount, errorCount};
                        locationSql = rule.getRuleContent().trim();
                    }
                    // finishedAt 继续表示基础统计结束，保留旧机构凭证时序契约；快照包含后续读取。
                    LocalDateTime countFinishedAt = LocalDateTime.now();
                    // 基础计数、机构计数与展示用主键在同一规则快照内完成。
                    boolean metadata = generator != null && generator.getExecutionMode() == AuditSqlGenerator.ExecutionMode.METADATA;
                    orgRead = metadata ? null : readOrgCounts(connection, task, rule, generator, context, readSnapshot);
                    if (counts[1] > 0 && shouldCollectIssueDetail(rule.getCollectIssueDetail())
                            && !metadata && keyDefinition.valid() && !keyDefinition.composite()) {
                        try (var checkpoint = readSnapshot == null ? null : readSnapshot.checkpoint()) {
                            pkValue = collectPkValues(connection, locationSql, keyDefinition.column(), task.getId(),
                                    instanceId);
                        }
                    }
                    String relationAfter = relationBefore == null ? null
                            : dbSwitchRuntime.products().metadata(conn.getProductType())
                            .queryRelationFingerprint(connection, physicalRuleTableName(conn, object, rule),
                                    conn.getSourceGenerationPin());
                    if (relationBefore != null && !relationBefore.equals(relationAfter)) {
                        throw new IllegalStateException("Relation changed during quality read");
                    }
                    QualityReadEvidence read = QualityReadEvidence.capture(conn, object,
                            auditLog.getResultSql(), generator != null, readStartedAt, countFinishedAt);
                    if (relationAfter != null) {
                        read.setSchemaVersion(QualityReadEvidence.RELATION_SCHEMA_VERSION);
                        read.setRelationFingerprint(relationAfter);
                    } else if (relationUnproven) {
                        read.setCoverage("RELATION_UNPROVEN");
                    }
                    if (metadata) {
                        read.setCoverage("METADATA_COLUMNS");
                    }
                    if (ruleParameters.containsKey("pkColumns")) {
                        read.setSchemaVersion(relationAfter == null ? 2 : QualityReadEvidence.COMPOSITE_SCHEMA_VERSION);
                    }
                    read.setKeyScope(keyScope);
                    if (orgRead != null) {
                        read.setOrgScope(orgRead.evidence());
                    }
                    if (readSnapshot != null) {
                        read.setSnapshotScope(readSnapshot.evidence());
                    }
                    read.setCommitScope(QualitySyncEvidenceCollector.inspectCommits(syncBefore, readSnapshot));
                    read.setCommitRangeScope(QualitySyncEvidenceCollector.inspectCommitRanges(syncBefore, readSnapshot,
                            object.getTableName()));
                    auditLog.setReadEvidence(JsonUtils.toJsonString(read));
                } // 快照复验、只读回滚和连接恢复失败也进入本轮失败分支，不留下成功凭证。
                lastError = null;
                break;
            } catch (Exception ex) {
                auditLog.setReadEvidence(null);
                lastError = ex;
                if (conn.getSourceGenerationPin() == null) {
                    log.warn("[executeRule][任务({}) 规则({}) 表({}) 第 {}/{} 次执行出错]",
                            task.getId(), rule.getId(), object.getTableName(), attempt, maxAttempts, ex);
                } else {
                    log.warn("[executeRule][任务({}) 规则({}) 实例({}) 第 {}/{} 次执行出错] {}",
                            task.getId(), rule.getId(), instanceId, attempt, maxAttempts, managedReadFailure(ex));
                }
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (lastError != null || counts == null) {
            auditLog.setResultType(QualityAuditLogDO.RESULT_ERROR);
            auditLog.setErrorDetail(StrUtil.maxLength(
                    lastError == null ? "执行被中断" : conn.getSourceGenerationPin() == null
                            ? lastError.getMessage() : managedReadFailure(lastError), 2000));
            auditLog.setCostMs(System.currentTimeMillis() - startMs);
            auditLogMapper.insert(auditLog);
            return auditLog;
        }
        long total = counts[0];
        long fail = counts[1];
        auditLog.setTotalCount(total);
        auditLog.setFailCount(fail);
        auditLog.setPassRate(rate(total - fail, total));
        if (total <= 0) {
            auditLog.setResultType(QualityAuditLogDO.RESULT_SKIPPED);
            auditLog.setErrorDetail("无样本，不产生合格结论");
        } else {
            auditLog.setResultType(judgeFail(rule, total, fail)
                    ? QualityAuditLogDO.RESULT_FAIL : QualityAuditLogDO.RESULT_PASS);
        }
        auditLog.setIndexValue(String.valueOf(fail));
        QualityReadEvidence finalRead = JsonUtils.parseObject(auditLog.getReadEvidence(), QualityReadEvidence.class);
        // 最后一个批次观察覆盖基础计数和机构分组读取，不能在机构读取之前结束观察区间。
        if (finalRead != null) {
            var syncAfter = syncEvidenceCollector == null ? null
                    : syncEvidenceCollector.observe(instanceId, task.getId(), conn, object);
            finalRead.setSyncScope(QualitySyncEvidenceCollector.combine(syncBefore, syncAfter));
            if (finalRead.getCommitScope() != null && !Objects.equals(syncBefore, syncAfter)) {
                finalRead.setCommitScope(
                        QualitySyncEvidenceCollector.ReadCommits.unavailable("BATCH_CHANGED_DURING_READ"));
            }
            if (finalRead.getCommitRangeScope() != null && !Objects.equals(syncBefore, syncAfter)) {
                finalRead.setCommitRangeScope(
                        QualitySyncEvidenceCollector.ReadRanges.unavailable("BATCH_CHANGED_DURING_READ"));
            }
            auditLog.setReadEvidence(JsonUtils.toJsonString(finalRead));
        }
        auditLog.setCostMs(System.currentTimeMillis() - startMs);
        auditLogMapper.insert(auditLog);
        if (fail > 0 && shouldCollectIssueDetail(rule.getCollectIssueDetail())) {
            // 主键留存：规则参数配置了 pkColumn 时留存问题行主键值（表外留存，不复制业务数据）
            // 复合键不塞进旧单列 CSV 展示字段；覆盖证明使用有序元组摘要。
            // 全部合计口径留存
            issueDataMapper.insert(QualityIssueDataDO.builder()
                    .instanceId(instanceId).auditLogId(auditLog.getId())
                    .taskId(task.getId()).ruleId(rule.getId()).objectId(object.getId())
                    .tableName(object.getTableName()).checkField(auditLog.getCheckField())
                    .statScope(QualityIssueDataDO.SCOPE_ALL)
                    .issueCount(fail).pkValue(pkValue).locationSql(locationSql)
                    .issueStatus(QualityIssueDataDO.STATUS_UNPROCESSED)
                    .checkTime(checkTime)
                    .build());
            // 机构口径留存（任务配置了机构标识字段时）
        }
        // 使用刚才同一次机构统计，不二次查询而把变化后的结果关联到旧读取凭证。
        if (shouldCollectIssueDetail(rule.getCollectIssueDetail()) && orgRead != null) {
            collectOrgIssues(instanceId, task, object, rule, generator, context, auditLog, checkTime, orgRead);
        }
        return auditLog;
    }

    /**
     * 为质量检查打开具有明确读取边界的数据快照。
     */
    private ReadSnapshot openReadSnapshot(
            Connection connection, MasterDatabaseResolver.ConnectionInfo conn) throws java.sql.SQLException {
        try {
            return dbSwitchRuntime.products().metadata(conn.getProductType())
                    .openReadSnapshot(connection, QUERY_TIMEOUT_SECONDS);
        } catch (java.sql.SQLFeatureNotSupportedException unsupported) {
            // 未验证产品保留既有普通质检，不生成快照凭证；PG 初始化/权限/网络失败不走降级。
            return null;
        }
    }

    /**
     * 未显式关闭时留存问题定位记录。历史规则库默认 0，由迁移纠正后再执行才会写入问题数据。
     */
    static boolean shouldCollectIssueDetail(Integer collectIssueDetail) {
        return !Objects.equals(collectIssueDetail, 0);
    }

    /**
     * 容差阈值判定（融合 DataQ）：规则 param_schema 支持
     * toleranceType=NONE（默认，失败数>0 即未通过）/ PASS_RATE（通过率低于 passRateLimit 才未通过）
     * / FAIL_COUNT（失败数超过 failCountLimit 才未通过）
     */
    private boolean judgeFail(QualityRuleDO rule, long total, long fail) {
        if (fail <= 0) {
            return false;
        }
        Map<String, Object> params = parseParams(rule);
        String type = String.valueOf(params.getOrDefault("toleranceType", "NONE"));
        if ("PASS_RATE".equalsIgnoreCase(type)) {
            double limit = toDouble(params.get("passRateLimit"), 100D);
            double passRate = total <= 0 ? 0 : (total - fail) * 100.0 / total;
            return passRate < limit;
        }
        if ("FAIL_COUNT".equalsIgnoreCase(type)) {
            long limit = (long) toDouble(params.get("failCountLimit"), 0D);
            return fail > limit;
        }
        return true;
    }

    private record OrgRow(String key, long total, long fail) {

    }

    private record OrgRead(QualityReadEvidence.OrgScope evidence, List<OrgRow> rows) {

    }

    /** 复用现有分组统计，只在完整读取后声明机构证据有效。原值仅供本次问题记录映射。 */
    private OrgRead readOrgCounts(Connection connection, QualityTaskDO task, QualityRuleDO rule,
            AuditSqlGenerator generator, AuditRuleContext context, ReadSnapshot readSnapshot) {
        if (StrUtil.isBlank(task.getOrgField()) || generator == null || context == null) {
            return null;
        }
        var evidence = new QualityReadEvidence.OrgScope();
        evidence.setSchemaVersion(1);
        evidence.setField(task.getOrgField().trim());
        evidence.setComplete(false);
        evidence.setStartedAt(LocalDateTime.now().toString());
        List<OrgRow> rows = new ArrayList<>();
        List<QualityReadEvidence.OrgCount> groups = new ArrayList<>();
        evidence.setGroups(groups);
        // 现有组件接收列标识符；这里限制为单一普通列，不允许表达式或任意 SQL 片段。
        if (!evidence.getField().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            evidence.setReasonCode("ORG_FIELD_UNSUPPORTED");
            evidence.setFinishedAt(LocalDateTime.now().toString());
            return new OrgRead(evidence, rows);
        }
        QualityKeyDefinition keyDefinition = QualityKeyDefinition.from(parseParams(rule));
        if (keyDefinition.configured()) {
            try (var checkpoint = readSnapshot == null ? null : readSnapshot.checkpoint()) {
                if (!keyDefinition.valid()) {
                    throw new UnsupportedOperationException("Invalid key configuration");
                }
                var snapshot = keyDefinition.composite()
                        ? AuditKeySnapshot.readGroupedComposite(connection, generator, context, keyDefinition.columns(),
                        evidence.getField(), AuditKeySnapshot.MAX_KEYS, QualityReadEvidence.MAX_ORG_GROUPS,
                        QUERY_TIMEOUT_SECONDS)
                        : AuditKeySnapshot.readGrouped(connection, generator, context, keyDefinition.column(),
                        evidence.getField(), AuditKeySnapshot.MAX_KEYS, QualityReadEvidence.MAX_ORG_GROUPS,
                        QUERY_TIMEOUT_SECONDS);
                if (snapshot.complete()) {
                    if (snapshot.groupEncoding() != null) {
                        evidence.setSchemaVersion(2);
                        evidence.setGroupEncoding(snapshot.groupEncoding());
                        evidence.setGroupJdbcType(snapshot.groupJdbcType());
                    }
                    evidence.setSqlHash(cn.hutool.crypto.digest.DigestUtil.sha256Hex(snapshot.sql()));
                    for (var group : snapshot.groups()) {
                        var counts = group.snapshot();
                        String groupHash = cn.hutool.crypto.digest.DigestUtil.sha256Hex(group.key());
                        var keys = QualityReadEvidence.keyScope(keyDefinition, counts);
                        keys.setGroupKeyHash(groupHash);
                        rows.add(new OrgRow(group.key(), counts.total(), counts.fail()));
                        groups.add(new QualityReadEvidence.OrgCount(groupHash, counts.total(), counts.fail(),
                                judgeFail(rule, counts.total(), counts.fail())
                                        ? QualityAuditLogDO.RESULT_FAIL : QualityAuditLogDO.RESULT_PASS, keys));
                    }
                    evidence.setComplete(true);
                    evidence.setFinishedAt(LocalDateTime.now().toString());
                    return new OrgRead(evidence, rows);
                }
                evidence.setKeyCaptureReason(snapshot.reasonCode());
            } catch (UnsupportedOperationException ex) {
                evidence.setKeyCaptureReason("KEY_SCOPE_UNSUPPORTED");
            } catch (Exception ex) {
                evidence.setKeyCaptureReason("KEY_READ_FAILED");
                log.warn("[readOrgCounts][任务({}) 规则({}) 机构主键统计失败，异常类型({})]",
                        task.getId(), rule.getId(), ex.getClass().getSimpleName());
            }
            // 不拼接部分键结果。回退只维持既有机构统计/问题留存，主键资格必须另行证明。
            rows.clear();
            groups.clear();
        }
        try (var checkpoint = readSnapshot == null ? null : readSnapshot.checkpoint();
             Statement statement = connection.createStatement()) {
            String sql = generator.buildGroupCountSql(context, evidence.getField());
            evidence.setSqlHash(cn.hutool.crypto.digest.DigestUtil.sha256Hex(sql));
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setMaxRows(QualityReadEvidence.MAX_ORG_GROUPS + 1);
            boolean valid = true;
            int scanned = 0;
            Set<String> keys = new HashSet<>();
            try (ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    if (++scanned > QualityReadEvidence.MAX_ORG_GROUPS) {
                        evidence.setReasonCode("ORG_LIMIT_EXCEEDED");
                        valid = false;
                        break;
                    }
                    String key = rs.getString(1);
                    long total = rs.getLong(2);
                    boolean nullTotal = rs.wasNull();
                    long failed = rs.getLong(3);
                    boolean nullFail = rs.wasNull();
                    // 未归属/过长/类型文本碰撞不能声称已完整覆盖；不截断键后再提供凭证。
                    if (StrUtil.isBlank(key) || key.length() > 64 || !keys.add(key)
                            || nullTotal || nullFail || total <= 0 || failed < 0 || failed > total) {
                        valid = false;
                        evidence.setReasonCode("ORG_COUNTS_INVALID");
                        continue;
                    }
                    rows.add(new OrgRow(key, total, failed));
                    groups.add(new QualityReadEvidence.OrgCount(cn.hutool.crypto.digest.DigestUtil.sha256Hex(key),
                            total, failed, judgeFail(rule, total, failed)
                            ? QualityAuditLogDO.RESULT_FAIL : QualityAuditLogDO.RESULT_PASS));
                }
            }
            evidence.setComplete(valid);
        } catch (Exception ex) {
            // 机构证据失败不抹掉全表检测，但不得留下可被采信的半份机构结果。
            evidence.setComplete(false);
            evidence.setReasonCode("ORG_READ_FAILED");
            groups.clear();
            rows.clear();
            log.warn("[readOrgCounts][任务({}) 规则({}) 机构统计失败，异常类型({})]",
                    task.getId(), rule.getId(), ex.getClass().getSimpleName());
        }
        evidence.setFinishedAt(LocalDateTime.now().toString());
        return new OrgRead(evidence, rows);
    }

    /**
     * 收集组织问题。
     */
    private void collectOrgIssues(Long instanceId, QualityTaskDO task,
            QualityCheckObjectDO object, QualityRuleDO rule,
            AuditSqlGenerator generator, AuditRuleContext context,
            QualityAuditLogDO auditLog, LocalDateTime checkTime, OrgRead read) {
        String orgField = task.getOrgField().trim();
        try {
            for (OrgRow row : read.rows()) {
                String groupKey = row.key();
                long groupFail = row.fail();
                if (groupFail <= 0) {
                    continue;
                }
                issueDataMapper.insert(QualityIssueDataDO.builder()
                        .instanceId(instanceId).auditLogId(auditLog.getId())
                        .taskId(task.getId()).ruleId(rule.getId()).objectId(object.getId())
                        .tableName(object.getTableName()).checkField(auditLog.getCheckField())
                        .statScope(QualityIssueDataDO.SCOPE_ORG)
                        .orgId(orgId(groupKey))
                        .regionCode(groupKey)
                        .issueCount(groupFail)
                        .locationSql(orgIssueLocationSql(generator, context, orgField, groupKey,
                                read.evidence().getGroupEncoding()))
                        .issueStatus(QualityIssueDataDO.STATUS_UNPROCESSED)
                        .checkTime(checkTime)
                        .build());
            }
        } catch (Exception ex) {
            // 保留已经写入的审计读取证据，不伪造未成功保存的问题记录。
            log.warn("[collectOrgIssues][任务({}) 规则({}) 机构问题留存失败，异常类型({})]",
                    task.getId(), rule.getId(), ex.getClass().getSimpleName());
        }
    }

    /**
     * 从读取数据或对象上下文中解析组织编号。
     */
    private Long orgId(String key) {
        if (!key.matches("[0-9]+")) {
            return null;
        }
        try {
            return Long.valueOf(key);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 集合边界先在原受检范围判定，再从问题集合筛选机构，避免重新估计机构内的四分位距。 */
    private String orgIssueLocationSql(AuditSqlGenerator generator, AuditRuleContext context,
            String field, String value, String encoding) {
        if ("NUMERIC_OUTLIER_VALIDATION".equals(generator.getStrategyKey())
                && "IQR".equals(context.getConfig().get("method"))) {
            String predicate = orgFilter(context, field, value, encoding);
            String errors = generator.buildErrorDataSql(context, Integer.MAX_VALUE);
            return context.database().limitGeneratedSelect("SELECT * FROM (" + errors
                    + ") qdata_org_errors WHERE " + predicate, ISSUE_DETAIL_LIMIT);
        }
        return generator.buildErrorDataSql(cloneWithOrgFilter(context, field, value, encoding), ISSUE_DETAIL_LIMIT);
    }

    /**
     * 构造质量检查使用的组织范围筛选条件。
     */
    private String orgFilter(AuditRuleContext context, String field, String value, String encoding) {
        return AuditKeySnapshot.supportsTextGroupEncoding(context.getProductType().name(), encoding)
                ? dbSwitchRuntime.products().metadata(context.getProductType())
                .textKeyProjection(null, field, AuditKeySnapshot.MAX_KEY_BYTES).equalitySql(value)
                : cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.quotePhysicalIdentifier(
                context.getProductType(), field)
                + " = '" + value.replace("'", "''") + "'";
    }

    /** 复制上下文；已验证产品的文本机构使用与凭证一致的精确 UTF-8 过滤。 */
    private AuditRuleContext cloneWithOrgFilter(AuditRuleContext source, String orgField, String orgValue,
            String groupEncoding) {
        AuditRuleContext copy = new AuditRuleContext(source.products());
        copy.setProductType(source.getProductType());
        copy.setTableName(source.getTableName());
        copy.setColumn(source.getColumn());
        copy.setColumns(source.getColumns());
        copy.setConfig(source.getConfig());
        copy.setScopePredicates(source.getScopePredicates());
        String orgFilter = orgFilter(source, orgField, orgValue, groupEncoding);
        copy.setWhereClause(StrUtil.isBlank(source.getWhereClause())
                ? orgFilter : "(" + source.getWhereClause() + ") AND " + orgFilter);
        return copy;
    }

    /**
     * 留存问题行主键值：从错误明细 SQL 包一层只取主键列，最多 50 个，超长截断
     */
    private String collectPkValues(Connection connection, String errorRowsSql, String pkColumn, Long taskId,
            Long instanceId) {
        if (StrUtil.isBlank(pkColumn) || "null".equalsIgnoreCase(pkColumn)
                || StrUtil.isBlank(errorRowsSql)) {
            return null;
        }
        String sql = "SELECT " + pkColumn.trim() + " FROM ("
                + StrUtil.removeSuffix(errorRowsSql.trim(), ";") + ") t__pk";
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(50);
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next() && values.size() < 50) {
                    Object value = rs.getObject(1);
                    if (value != null) {
                        values.add(String.valueOf(value));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("[collectPkValues][任务({}) 实例({}) 主键留存失败] {}", taskId, instanceId,
                    managedReadFailure(ex));
            return null;
        }
        return values.isEmpty() ? null : StrUtil.maxLength(String.join(",", values), 250);
    }

    /**
     * 解析质量任务的参数。
     */
    private Map<String, Object> parseParams(QualityRuleDO rule) {
        if (StrUtil.isBlank(rule.getParamSchema())) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = JsonUtils.parseObject(rule.getParamSchema(), Map.class);
        return parsed == null ? Map.of() : parsed;
    }

    /**
     * 转换浮点数。
     */
    private double toDouble(Object value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /**
     * 由规则参数（param_schema JSON）构建引擎执行上下文：
     * column/columns/where 为保留键，其余键作为策略配置
     */
    @SuppressWarnings("unchecked")
    private AuditRuleContext buildContext(MasterDatabaseResolver.ConnectionInfo conn,
            QualityCheckObjectDO object, QualityRuleDO rule) {
        if (rule.getExecutionEvaluationId() != null) {
            Map<String, Object> parameters = new java.util.LinkedHashMap<>(JsonUtils.parseMap(rule.getParamSchema()));
            var input = new cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskEvaluationSaveReqVO();
            input.setStrategyKey(rule.getStrategyKey());
            Object columns = parameters.remove("columns");
            input.setColumnNames(
                    columns instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of());
            parameters.remove("column");
            Object where = parameters.remove("where");
            input.setWhereClause(where == null ? null : String.valueOf(where));
            // 参数已在保存时标准化，字符规则直接使用冻结的正则。
            if ("CHARACTER_VALIDATION".equals(rule.getStrategyKey())) {
                parameters.put("useRegex", true);
            }
            input.setConfig(parameters);
            return evaluationService.context(conn.getProductType(), object.getTableName(), input);
        }
        validateStandardRelation(conn, object, rule);
        Map<String, Object> params = new HashMap<>();
        if (StrUtil.isNotBlank(rule.getParamSchema())) {
            Map<String, Object> parsed = JsonUtils.parseObject(rule.getParamSchema(), Map.class);
            if (parsed != null) {
                params.putAll(parsed);
            }
        }
        AuditRuleContext context = new AuditRuleContext(dbSwitchRuntime.products());
        context.setProductType(conn.getProductType());
        context.setTableName(physicalRuleTableName(conn, object, rule));
        Object column = params.remove("column");
        if (column != null) {
            context.setColumn(String.valueOf(column));
        }
        Object columns = params.remove("columns");
        if (columns instanceof Collection<?> collection) {
            context.setColumns(collection.stream().map(String::valueOf).toList());
        }
        Object where = params.remove("where");
        if (where != null && StrUtil.isNotBlank(String.valueOf(where))) {
            context.setWhereClause(String.valueOf(where));
        }
        context.setConfig(params);
        return context;
    }

    /** 冻结后连接默认 schema 也可能变化，执行前复用组件解析器精确复核关系。 */
    private static void validateStandardRelation(MasterDatabaseResolver.ConnectionInfo conn,
            QualityCheckObjectDO object, QualityRuleDO rule) {
        if (rule.getStandardMappingId() == null) {
            return;
        }
        var relation = cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard
                .parseTableReference(conn.getProductType(), object.getTableName(), conn.getSchema());
        if (!Objects.equals(rule.getExecutionObjectId(), object.getId())
                || !Objects.equals(rule.getExecutionTableName(), relation.getTableName())
                || !Objects.equals(rule.getExecutionSchemaName(), relation.getSchemaName())) {
            throw new IllegalStateException("标准落标的冻结关系与本次执行连接不一致");
        }
    }

    /** 标准字段执行使用冻结的限定名，不能依赖 JDBC 连接未设置的默认 search_path。 */
    private static String physicalRuleTableName(MasterDatabaseResolver.ConnectionInfo conn,
            QualityCheckObjectDO object, QualityRuleDO rule) {
        return rule.getStandardMappingId() == null ? object.getTableName()
                : cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.qualifiedTableName(
                conn.getProductType(), rule.getExecutionSchemaName(), rule.getExecutionTableName());
    }

    /** 执行统计 SQL：返回 [totalCount, errorCount] */
    private long[] queryCounts(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = statement.executeQuery(sql)) {
                if (rs.next()) {
                    return new long[]{rs.getLong(1), rs.getLong(2)};
                }
                return new long[]{0L, 0L};
            }
        }
    }

    /**
     * 执行质量统计查询并读取单一计数结果。
     */
    private long querySingleCount(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = statement.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /**
     * 结束实例。
     */
    private void finishInstance(Long instanceId, String status, LocalDateTime start,
            String errorFlag, List<QualityAuditLogDO> auditLogs) {
        long total = auditLogs.stream().mapToLong(l -> l.getTotalCount() == null ? 0 : l.getTotalCount()).sum();
        long fail = auditLogs.stream().mapToLong(l -> l.getFailCount() == null ? 0 : l.getFailCount()).sum();
        boolean executionError = auditLogs.stream()
                .anyMatch(value -> QualityAuditLogDO.RESULT_ERROR.equals(value.getResultType()));
        if (executionError) {
            status = QualityTaskInstanceDO.STATUS_FAILED;
            errorFlag = "部分评测执行失败，请查看规则执行结果";
        }
        LocalDateTime end = LocalDateTime.now();
        BigDecimal passRate = executionError || !QualityTaskInstanceDO.STATUS_SUCCESS.equals(status) ? null : rate(
                total - fail, total);
        instanceMapper.update(null, Wrappers.lambdaUpdate(QualityTaskInstanceDO.class)
                .set(QualityTaskInstanceDO::getInstanceStatus, status)
                .set(QualityTaskInstanceDO::getEndTime, end)
                .set(QualityTaskInstanceDO::getDurationMs, Duration.between(start, end).toMillis())
                .set(QualityTaskInstanceDO::getErrorFlag, errorFlag)
                .set(QualityTaskInstanceDO::getTotalCount, total)
                .set(QualityTaskInstanceDO::getPassCount, total - fail)
                .set(QualityTaskInstanceDO::getFailCount, fail)
                .set(QualityTaskInstanceDO::getPassRate, passRate)
                .set(QualityTaskInstanceDO::getScore, passRate)
                .eq(QualityTaskInstanceDO::getId, instanceId)
                .eq(QualityTaskInstanceDO::getInstanceStatus, QualityTaskInstanceDO.STATUS_RUNNING));
    }

    /**
     * 严重/高危规则未通过时产生告警（等级映射：CRITICAL→危险，HIGH→严重，其他→一般）
     */
    private void createAlertIfNecessary(QualityTaskDO task, List<QualityAuditLogDO> auditLogs,
            List<QualityRuleDO> frozenRules) {
        List<QualityAuditLogDO> failed = auditLogs.stream()
                .filter(l -> QualityAuditLogDO.RESULT_FAIL.equals(l.getResultType()))
                .toList();
        if (failed.isEmpty()) {
            return;
        }
        String maxSeverity = failed.stream()
                .map(log -> frozenRules.stream().filter(rule -> log.getEvaluationId() != null
                        ? Objects.equals(log.getEvaluationId(), rule.getExecutionEvaluationId())
                        : Objects.equals(log.getRuleId(), rule.getId())).findFirst().orElse(null))
                .filter(Objects::nonNull)
                .map(QualityRuleDO::getSeverity)
                .reduce("LOW", (a, b) -> severityRank(b) > severityRank(a) ? b : a);
        if (severityRank(maxSeverity) < severityRank("HIGH")) {
            return;
        }
        long issueCount = failed.stream().mapToLong(QualityAuditLogDO::getFailCount).sum();
        String alertTitle = task.getTaskName() + " 质量检测未通过";
        alertMapper.insert(QualityAlertDO.builder()
                .alertTitle(alertTitle)
                .taskType("质量任务")
                .taskId(task.getId()).taskName(task.getTaskName())
                .alertLevel("CRITICAL".equals(maxSeverity)
                        ? QualityAlertDO.LEVEL_DANGER : QualityAlertDO.LEVEL_SERIOUS)
                .issueCount(issueCount)
                .ruleName(failed.stream().map(QualityAuditLogDO::getRuleName)
                        .filter(StrUtil::isNotBlank).distinct().limit(3)
                        .reduce((a, b) -> a + "、" + b).orElse(null))
                .alertStatus(QualityAlertDO.STATUS_UNPROCESSED)
                .alertTime(LocalDateTime.now())
                .build());
        notifyQualityAlert(task, alertTitle, issueCount);
    }

    /**
     * 通知质量告警。
     */
    private void notifyQualityAlert(QualityTaskDO task, String alertTitle, long issueCount) {
        if (task.getOwnerUserId() == null) {
            return;
        }
        try {
            NotifySendSingleToUserReqDTO reqDTO = new NotifySendSingleToUserReqDTO();
            reqDTO.setUserId(task.getOwnerUserId());
            reqDTO.setTemplateCode("quality_alert");
            reqDTO.setTemplateParams(Map.of(
                    "taskName", StrUtil.blankToDefault(task.getTaskName(), ""),
                    "issueCount", String.valueOf(issueCount),
                    "alertTitle", alertTitle));
            notifyMessageSendApi.sendSingleMessageToAdmin(reqDTO);
        } catch (Exception ex) {
            log.warn("[notifyQualityAlert][质量告警通知失败 taskId={}：{}]", task.getId(), ex.getMessage());
        }
    }

    /**
     * 将问题严重级别转换为可比较的排序值。
     */
    private int severityRank(String severity) {
        return switch (StrUtil.nullToEmpty(severity)) {
            case "CRITICAL" -> 3;
            case "HIGH" -> 2;
            case "MID" -> 1;
            default -> 0;
        };
    }

    /**
     * 根据分子与分母计算质量统计比率。
     */
    private BigDecimal rate(long pass, long total) {
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(pass).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    /**
     * 解析质量任务的主机。
     */
    private String resolveHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            return "unknown";
        }
    }

}
