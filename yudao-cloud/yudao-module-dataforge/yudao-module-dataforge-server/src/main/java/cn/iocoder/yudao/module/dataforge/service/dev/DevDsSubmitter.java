package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.request.CreateWorkflowDefinitionRequest;
import cn.iocoder.yudao.framework.dolphinscheduler.request.TriggerWorkflowDefinitionRequest;
import cn.iocoder.yudao.framework.dolphinscheduler.request.QueryWorkflowInstanceListRequest;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevRecordMapper;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 持久化 DS 运行编排；XXL-Job 按有数量上限的批次重复检查，不由进程本地状态持有运行归属权。 */
@Slf4j
@Component
public class DevDsSubmitter {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Pattern JDBC_URL_PATTERN =
            Pattern.compile("jdbc:[\\w:]+://([^:/?;]+)(?::(\\d+))?(?:[/;]([^?;]+))?.*");

    @Resource
    private DolphinSchedulerClient dsClient;

    @Resource
    private DevRecordMapper recordMapper;

    @Resource
    private AccessDatabaseResolver databaseResolver;

    @Resource
    private cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper databaseMapper;

    @Resource
    private ExecutionAccessService executionAccessService;

    @Resource
    private DevScriptAuthorizationSupport scriptAuthorizationSupport;

    @Resource
    private DevNativeRuntimeSupport nativeRuntimeSupport;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<org.springframework.boot.ssl.SslBundles> sslBundles;

    @Resource
    private QualityGateService qualityGateService;

    @Value("${dataforge.dev.ds-project-code:0}")
    private Long projectCode;

    /** 研发任务统一提交到的 DolphinScheduler Worker 组；为空时使用 default */
    @Value("${dataforge.dev.worker-group:}")
    private String workerGroup;

    /** 研发任务在 DolphinScheduler 侧的执行租户；脚本类任务必须配置非 root 租户 */
    @Value("${dataforge.dev.tenant-code:}")
    private String tenantCode;

    /** 管理员配置的普通开发环境，保存快照后执行时不覆盖冻结值。 */
    @Value("${dataforge.dev.environment-code:-1}")
    private Long environmentCode = -1L;

    public Long configuredEnvironmentCode() { return environmentCode == null ? -1L : environmentCode; }

    public Long executionEnvironment(DevTaskSnapshot snapshot) {
        if (DevNativeRuntimeSupport.isNative(snapshot.getTask())) return nativeRuntimeSupport.requireFrozen(snapshot).getEnvironmentCode();
        return snapshot.getManagedEnvironmentCode() == null ? -1L : snapshot.getManagedEnvironmentCode();
    }

    record ExecutionTarget(String workerGroup, String tenantCode) {

    }

    /**
     * 获取开发调度平台提交器的执行目标。
     *
     * @param task 任务记录
     * @return 执行目标
     */
    ExecutionTarget executionTarget(DevTaskDO task) {
        if (DevNativeRuntimeSupport.isNative(task)) {
            var profile = nativeRuntimeSupport.resolve(task);
            return new ExecutionTarget(profile.getWorkerGroup(), profile.getTenantCode());
        }
        var config = DevTaskDefinition.readExecutionConfig(task.getExecutionConfigJson());
        String managedGroup = StrUtil.blankToDefault(workerGroup, "default");
        if (!"default".equals(config.getWorkerGroup()) && !managedGroup.equals(config.getWorkerGroup())) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "只能选择管理员配置的受管 Worker 组");
        }
        validateExecutionTarget(task, workerGroup, tenantCode);
        return new ExecutionTarget(managedGroup, tenantCode);
    }

    /**
     * 校验执行目标。
     *
     * @param task 任务记录
     * @param group 分组
     * @param tenant 外部执行租户
     */
    private void validateExecutionTarget(DevTaskDO task, String group, String tenant) {
        boolean isolated = DevTaskDO.TYPE_SHELL.equals(task.getTaskType())
                || DevTaskDO.TYPE_PROCEDURE.equals(task.getTaskType())
                || "PROCEDURE".equals(
                DevTaskDefinition.readExecutionConfig(task.getExecutionConfigJson()).getSqlType());
        if (isolated && (StrUtil.isBlank(group) || "default".equalsIgnoreCase(group)
                || StrUtil.isBlank(tenant) || "root".equalsIgnoreCase(tenant))) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "脚本或存储过程任务必须配置专用 Worker 组和非 root 执行租户");
        }
    }

    /**
     * 校验已配置。
     */
    public void validateConfigured() {
        if (projectCode == null || projectCode <= 0) {
            throw exception(DEV_DS_NOT_CONFIGURED);
        }
    }

    /** 返回冻结目标的项目；只读取已有配置，不发起远程请求。 */
    public Long executionProject(DevTaskSnapshot snapshot) {
        if (DevNativeRuntimeSupport.isNative(snapshot.getTask())) {
            return nativeRuntimeSupport.requireFrozen(snapshot).getDsProjectCode();
        }
        validateConfigured();
        return projectCode;
    }

    /**
     * 判断当前执行是否仍处于运行状态。
     *
     * @param taskId 任务编号
     * @return 判断结果
     */
    public boolean isRunning(Long taskId) {
        return recordMapper.selectCount(Wrappers.<DevRecordDO>lambdaQuery()
                .eq(DevRecordDO::getTaskId, taskId)
                .and(q -> q.in(DevRecordDO::getStatus, DevRecordDO.ACTIVE_STATUSES)
                        .or(x -> x.eq(DevRecordDO::getStatus, DevRecordDO.STATUS_SUCCESS)
                                .in(DevRecordDO::getQualityStatus, "PENDING", "UNKNOWN")))) > 0;
    }

    /** 在调度事务内调用；仅记录执行意图，绝不联系 DS。 */
    public Long enqueue(DevTaskDO task, DevTaskVersionDO version, String trigger, String runKey,
            LocalDate businessDate) {
        if (recordMapper.selectCount(Wrappers.<DevRecordDO>lambdaQuery().eq(DevRecordDO::getTaskId, task.getId())
                .isNull(DevRecordDO::getVersionId).eq(DevRecordDO::getStatus, DevRecordDO.STATUS_UNKNOWN)) > 0) {
            throw exception(DEV_TASK_RUNNING);
        }
        DevRecordDO existing = recordMapper.selectOne(DevRecordDO::getRunKey, runKey);
        if (existing != null) {
            return matchingRun(existing, task, businessDate);
        }
        // 调用方已持有任务行锁；先返回业务原因，数据库单活索引继续保留为最后一道并发保护。
        if (isRunning(task.getId())) {
            throw exception(DEV_TASK_RUNNING);
        }
        DevTaskSnapshot snapshot = DevTaskSnapshot.read(version.getSnapshotJson());
        Long managedProject = projectCode;
        if (DevNativeRuntimeSupport.isNative(snapshot.getTask())) {
            managedProject = nativeRuntimeSupport.requireFrozen(snapshot).getDsProjectCode();
        } else {
            validateConfigured();
            DevTaskDefinition.requireExecutable(snapshot.getTask());
        }
        Long userId = snapshot.getTask().getExecutionUserId();
        if (userId == null) {
            throw exception(DEV_EXECUTION_IDENTITY_REQUIRED);
        }
        DevRecordDO record = DevRecordDO.builder().taskId(task.getId()).taskName(snapshot.getTask().getName())
                .businessDate(businessDate)
                .databaseId(snapshot.getTask().getDatabaseId()).datasourceName(snapshot.getTask().getDatasourceName())
                .runKey(runKey).versionId(version.getId()).snapshotJson(version.getSnapshotJson())
                .executionUserId(userId).dsProjectCode(managedProject).triggerType(trigger)
                .status(DevRecordDO.STATUS_WAITING).submissionStage("JOB".equals(trigger) ? "JOB_RESERVED" : "PREPARE").cancelRequested(false)
                .qualityStatus(snapshot.getTask().getQualityTaskId() == null ? "NOT_CONFIGURED" : "PENDING")
                .statementTotal(0).statementSuccess(0).affectedRows(0L).log("").build();

        record.setCreator(String.valueOf(userId));record.setUpdater(String.valueOf(userId));
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException ex) {
            existing = recordMapper.selectOne(DevRecordDO::getRunKey, runKey);
            if (existing == null) {
                throw ex;
            }
            return matchingRun(existing, task, businessDate);
        }
        return record.getId();
    }

    /** 纯读复核执行护栏和冻结目标，不创建记录、定义或触发请求。 */
    public cn.iocoder.yudao.module.dataforge.model.dev.DevJobNode previewJobNode(DevTaskSnapshot snapshot, Long versionId) {
        var task = snapshot.getTask();
        executionAccessService.requireUserPermission(task.getExecutionUserId(), ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
        if (DevNativeRuntimeSupport.isNative(task)) {
            nativeRuntimeSupport.requireActor(task, task.getExecutionUserId());
            nativeRuntimeSupport.requireFrozen(snapshot);
        } else {
            DevTaskDefinition.requireExecutable(task);
            validateExecutionTarget(task, snapshot.getWorkerGroup(), snapshot.getTenantCode());
        }
        validateForSave(task);
        return new cn.iocoder.yudao.module.dataforge.model.dev.DevJobNode(task.getId(), versionId, null,
                executionProject(snapshot), null, StrUtil.blankToDefault(snapshot.getWorkerGroup(), "default"),
                snapshot.getTenantCode(), executionEnvironment(snapshot),
                DevNativeRuntimeSupport.isNative(task) || task.getQualityTaskId() != null);
    }

    /** 专属外部父流程只准备一次；独立调度扫描绝不能把这类记录再次投递。 */
    public cn.iocoder.yudao.module.dataforge.model.dev.DevJobNode prepareJobNode(Long recordId) {
        var record = recordMapper.selectById(recordId);
        if (record == null || !"JOB".equals(record.getTriggerType()) || record.getRunKey() == null || !record.getRunKey().startsWith("JOB:")) throw exception(DEV_RECORD_NOT_EXISTS);
        var snapshot = DevTaskSnapshot.read(record.getSnapshotJson());
        var target = previewJobNode(snapshot, record.getVersionId());
        if (!dependenciesReady(record, snapshot)) throw exception(DEV_TASK_DEPENDENCY_INVALID, "开发任务的冻结上游版本或质量门禁尚未满足");
        String owner = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        if (recordMapper.update(null, Wrappers.<DevRecordDO>lambdaUpdate().eq(DevRecordDO::getId, recordId)
                .eq(DevRecordDO::getTriggerType, "JOB").isNull(DevRecordDO::getDsInstanceId)
                .in(DevRecordDO::getSubmissionStage, "JOB_RESERVED", "JOB_PREPARED")
                .and(q -> q.isNull(DevRecordDO::getLeaseUntil).or().lt(DevRecordDO::getLeaseUntil, now))
                .set(DevRecordDO::getLeaseOwner, owner).set(DevRecordDO::getLeaseUntil, now.plusMinutes(5))
                .set(DevRecordDO::getStatus, DevRecordDO.STATUS_SUBMITTING)) != 1) throw exception(DEV_TASK_RUNNING);
        var logs = new StringBuilder(StrUtil.nullToEmpty(record.getLog()));
        try {
            var task = snapshot.getTask();
            var authorized = authorizeScript(task);
            Long definition = ensureRunWorkflow(record, task, buildTaskParams(task, logs, authorized, "dh_native_" + recordId), owner, snapshot.getWorkerGroup());
            if (definition == null) throw exception(DEV_TASK_RUNNING);
            if (updateOwned(record, owner).set(DevRecordDO::getSubmissionStage, "JOB_PREPARED").update() != true) throw exception(DEV_TASK_RUNNING);
            return new cn.iocoder.yudao.module.dataforge.model.dev.DevJobNode(target.taskId(), target.versionId(), recordId,
                    target.projectCode(), definition, target.workerGroup(), target.tenantCode(), target.environmentCode(), target.terminalGuardRequired());
        } finally {
            updateOwned(record, owner).set(DevRecordDO::getLog, StrUtil.subSufByLength(logs.toString(), 64000))
                    .set(DevRecordDO::getLeaseOwner, null).set(DevRecordDO::getLeaseUntil, null).update();
        }
    }

    /** 只接受已核对的子定义与实例，绑定后沿用原有 observe、原生终态与质量门禁。 */
    public DevRecordDO bindJobNode(Long recordId, Long project, Long definition, Integer instanceId) {
        var record = recordMapper.selectById(recordId);
        if (record == null || !"JOB".equals(record.getTriggerType()) || !Objects.equals(project, record.getDsProjectCode())
                || !Objects.equals(definition, record.getDsWorkflowCode()) || instanceId == null || instanceId <= 0) throw exception(DEV_RECORD_NOT_EXISTS);
        var response = dsClient.workflowInstance().queryWorkflowInstanceById(project, instanceId);
        var actual = response == null || response.getInt("code", -1) != 0 ? null : response.getJSONObject("data");
        if (actual == null || !Objects.equals(actual.getInt("id"), instanceId) || !Objects.equals(actual.getLong("projectCode"), project)
                || !Objects.equals(actual.getLong("workflowDefinitionCode"), definition)) throw exception(DEV_TASK_SNAPSHOT_INVALID, "子工作流身份与冻结开发记录不一致");
        if (recordMapper.update(null, Wrappers.<DevRecordDO>lambdaUpdate().eq(DevRecordDO::getId, recordId)
                .eq(DevRecordDO::getTriggerType, "JOB").eq(DevRecordDO::getDsProjectCode, project).eq(DevRecordDO::getDsWorkflowCode, definition)
                .and(q -> q.isNull(DevRecordDO::getDsInstanceId).or().eq(DevRecordDO::getDsInstanceId, instanceId))
                .set(DevRecordDO::getDsInstanceId, instanceId).set(DevRecordDO::getSubmissionStage, "JOB_BOUND")
                .set(DevRecordDO::getStartTime, record.getStartTime() == null ? LocalDateTime.now() : record.getStartTime())) != 1) throw exception(DEV_RUN_REQUEST_CONFLICT);
        process(recordId);
        return recordMapper.selectById(recordId);
    }

    public DevRecordDO observeJobNode(Long recordId) {
        var record = recordMapper.selectById(recordId);
        if (record == null || !"JOB".equals(record.getTriggerType())) throw exception(DEV_RECORD_NOT_EXISTS);
        if (record.getDsInstanceId() != null) process(recordId);
        return recordMapper.selectById(recordId);
    }

    /** 后台取消只接受该作业的冻结本人，保留独立运行、原生权限与项目核验。 */
    public DevRecordDO authorizeJobNode(Long recordId,Long actor) {
        var record=authorizeJobNodeForCaller(recordId,actor);
        if(!Objects.equals(actor,record.getExecutionUserId()))throw exception(DEV_EXECUTION_IDENTITY_REQUIRED);
        var snapshot=DevTaskSnapshot.read(record.getSnapshotJson());
        if(!Objects.equals(actor,snapshot.getTask().getExecutionUserId()))throw exception(DEV_EXECUTION_IDENTITY_REQUIRED);
        return record;
    }

    /** 发起停止的人独立持有权限；允许清理冻结旧目标，不能改投当前新项目。 */
    public DevRecordDO authorizeJobNodeForCaller(Long recordId,Long caller) {
        var record=recordMapper.selectById(recordId);
        if(record==null||!"JOB".equals(record.getTriggerType())||caller==null)throw exception(DEV_EXECUTION_IDENTITY_REQUIRED);
        var snapshot=DevTaskSnapshot.read(record.getSnapshotJson());
        executionAccessService.requireUserPermission(caller,ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
        if(DevNativeRuntimeSupport.isNative(snapshot.getTask())) {
            nativeRuntimeSupport.requireActor(snapshot.getTask(),caller);
            if(!Objects.equals(record.getDsProjectCode(),snapshot.getNativeProjectCode()))throw exception(DEV_RECORD_NOT_EXISTS);
        }
        return record;
    }

    /** 调用方必须已证明业务父流程没有发送触发；这里再次限制为未绑定实例的准备阶段。 */
    public void cancelPreparedJobNode(Long recordId) {
        recordMapper.update(null, Wrappers.<DevRecordDO>lambdaUpdate().eq(DevRecordDO::getId, recordId)
                .eq(DevRecordDO::getTriggerType, "JOB").isNull(DevRecordDO::getDsInstanceId)
                .in(DevRecordDO::getSubmissionStage, "JOB_RESERVED", "JOB_PREPARED")
                .set(DevRecordDO::getStatus, DevRecordDO.STATUS_STOPPED).set(DevRecordDO::getCancelRequested, true)
                .set(DevRecordDO::getEndTime, LocalDateTime.now()).set(DevRecordDO::getLastError, "父流程未触发，作业准备已取消"));
    }

    /**
     * 获取开发调度平台提交器的匹配运行。
     *
     * @param record 运行记录
     * @param task 任务记录
     * @param date 日期
     * @return 记录编号
     */
    private Long matchingRun(DevRecordDO record, DevTaskDO task, LocalDate date) {
        if (!Objects.equals(record.getTaskId(), task.getId()) || !Objects.equals(record.getBusinessDate(), date)) {
            throw exception(DEV_RUN_REQUEST_CONFLICT);
        }
        return record.getId();
    }

    /** 取消操作会持久化；仅引擎观察到的终态能结束已提交运行。 */
    public void stop(Long recordId) {
        recordMapper.update(null, Wrappers.lambdaUpdate(DevRecordDO.class)
                .set(DevRecordDO::getCancelRequested, true)
                .eq(DevRecordDO::getId, recordId).in(DevRecordDO::getStatus, DevRecordDO.ACTIVE_STATUSES));
    }

    /**
     * 处理待处理。
     */
    public void processPending() {
        List<DevRecordDO> records = recordMapper.selectList(Wrappers.<DevRecordDO>lambdaQuery()
                .and(q -> q.ne(DevRecordDO::getTriggerType, "JOB").or().isNotNull(DevRecordDO::getDsInstanceId))
                .and(q -> q.in(DevRecordDO::getStatus, DevRecordDO.ACTIVE_STATUSES)
                        .or(x -> x.eq(DevRecordDO::getStatus, DevRecordDO.STATUS_SUCCESS)
                                .in(DevRecordDO::getQualityStatus, "PENDING", "UNKNOWN")))
                .last("ORDER BY heartbeat_time ASC NULLS FIRST, id ASC LIMIT 100"));
        for (DevRecordDO record : records) {
            process(record.getId());
        }
    }

    /**
     * 处理开发调度平台提交器。
     *
     * @param recordId 记录编号
     */
    void process(Long recordId) {
        String owner = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        int claimed = recordMapper.update(null, Wrappers.lambdaUpdate(DevRecordDO.class)
                .set(DevRecordDO::getLeaseOwner, owner).set(DevRecordDO::getLeaseUntil, now.plusMinutes(5))
                .set(DevRecordDO::getHeartbeatTime, now).eq(DevRecordDO::getId, recordId)
                .and(q -> q.ne(DevRecordDO::getTriggerType, "JOB").or().isNotNull(DevRecordDO::getDsInstanceId))
                .and(q -> q.in(DevRecordDO::getStatus, DevRecordDO.ACTIVE_STATUSES)
                        .or(x -> x.eq(DevRecordDO::getStatus, DevRecordDO.STATUS_SUCCESS)
                                .in(DevRecordDO::getQualityStatus, "PENDING", "UNKNOWN")))
                .and(q -> q.isNull(DevRecordDO::getLeaseUntil).or().lt(DevRecordDO::getLeaseUntil, now)));
        if (claimed != 1) {
            return;
        }
        DevRecordDO record = recordMapper.selectById(recordId);
        StringBuilder logs = new StringBuilder(StrUtil.nullToEmpty(record.getLog()));
        try {
            if (StrUtil.isBlank(record.getSnapshotJson()) || record.getExecutionUserId() == null) {
                updateOwned(record, owner).set(DevRecordDO::getStatus, DevRecordDO.STATUS_UNKNOWN)
                        .set(DevRecordDO::getLastError, "缺少可信执行身份或执行快照，需人工核对历史实例").update();
                return;
            }
            DevTaskSnapshot snapshot = DevTaskSnapshot.read(record.getSnapshotJson());
            DevTaskDO task = snapshot.getTask();
            if (DevRecordDO.STATUS_SUCCESS.equals(record.getStatus())) {
                updateQuality(record, owner, task);
                return; // 质量检查待处理期间，不再重复观察已验证的执行终态。
            }
            if (Boolean.TRUE.equals(record.getCancelRequested()) && "PREPARE".equals(record.getSubmissionStage())) {
                finish(record, owner, DevRecordDO.STATUS_STOPPED, "尚未投递，已取消");
                return;
            }
            if ("PREPARE".equals(record.getSubmissionStage())) {
                if (DevNativeRuntimeSupport.isNative(task)) {
                    var profile = nativeRuntimeSupport.requireFrozen(snapshot);
                    nativeRuntimeSupport.requireActor(task, record.getExecutionUserId());
                    if (!Objects.equals(profile.getDsProjectCode(), record.getDsProjectCode())
                            || !Objects.equals(profile.getWorkerGroup(), snapshot.getWorkerGroup())
                            || !Objects.equals(profile.getTenantCode(), snapshot.getTenantCode())) {
                        throw exception(DEV_NATIVE_RUNTIME_NOT_READY);
                    }
                } else {
                    DevTaskDefinition.requireExecutable(task);
                }
                executionAccessService.requireUserPermission(record.getExecutionUserId(),
                        ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
                if (!DevNativeRuntimeSupport.isNative(task)) {
                    validateExecutionTarget(task, snapshot.getWorkerGroup(), snapshot.getTenantCode());
                }
                if (!dependenciesReady(record, snapshot)) {
                    return;
                }
                var authorizedScript = authorizeScript(task);
                // 部分唯一索引确保同一任务跨节点只有一个活跃执行槽位。
                try {
                    if (updateOwned(record, owner).set(DevRecordDO::getStatus, DevRecordDO.STATUS_SUBMITTING)
                            .update() != true) {
                        return;
                    }
                } catch (DuplicateKeyException ex) {
                    return;
                }
                Long workflow = ensureRunWorkflow(record, task,
                        buildTaskParams(task, logs, authorizedScript, "dh_native_" + record.getId()), owner,
                        snapshot.getWorkerGroup());
                if (workflow == null) {
                    return;
                }
                if (DevNativeRuntimeSupport.isNative(task)) {
                    executionAccessService.requireUserPermission(record.getExecutionUserId(),
                            ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
                    nativeRuntimeSupport.requireActor(task, record.getExecutionUserId());
                    nativeRuntimeSupport.requireFrozen(snapshot);
                }
                // 先持久化，再触发非幂等的外部操作；响应丢失时绝不盲目重试。
                if (updateOwned(record, owner).set(DevRecordDO::getSubmissionStage, "TRIGGER_SENT")
                        .set(DevRecordDO::getStartTime, now)
                        .eq(DevRecordDO::getCancelRequested, false)
                        .gt(DevRecordDO::getLeaseUntil, LocalDateTime.now()).update() != true) {
                    return;
                }
                record.setSubmissionStage("TRIGGER_SENT");
                record.setStartTime(now);
                TriggerWorkflowDefinitionRequest request = new TriggerWorkflowDefinitionRequest();
                request.setWorkflowDefinitionCode(workflow);
                request.setScheduleTime("");
                request.setFailureStrategy("END");
                request.setWarningType("NONE");
                request.setExecType("START_PROCESS");
                request.setWorkflowInstancePriority(
                        DevTaskDefinition.readExecutionConfig(task.getExecutionConfigJson()).getPriority());
                request.setWorkerGroup(StrUtil.blankToDefault(snapshot.getWorkerGroup(), "default"));
                request.setTenantCode(snapshot.getTenantCode());
                request.setEnvironmentCode(executionEnvironment(snapshot));
                request.setDryRun(0);
                JSONObject response = dsClient.executor().triggerWorkflowDefinition(record.getDsProjectCode(), request);
                // DS 3.4.2 START_PROCESS 返回工作流实例编号数组，不是 triggerCode。
                JSONArray instanceIds = response.getJSONArray("data");
                if (instanceIds == null || instanceIds.size() != 1
                        || !(instanceIds.get(0) instanceof Integer || instanceIds.get(0) instanceof Long)) {
                    throw new IllegalStateException("调度器未返回唯一工作流实例编号");
                }
                long returnedId = instanceIds.getLong(0);
                if (returnedId <= 0 || returnedId > Integer.MAX_VALUE) {
                    throw new IllegalStateException("调度器未返回有效工作流实例编号");
                }
                int instanceId = (int) returnedId;
                updateOwned(record, owner).set(DevRecordDO::getDsInstanceId, instanceId)
                        .set(DevRecordDO::getStatus, DevRecordDO.STATUS_SUBMITTING).update();
                record.setDsInstanceId(instanceId);
            }
            observe(record, owner, task, logs);
        } catch (Exception ex) {
            if (DevRecordDO.STATUS_SUCCESS.equals(record.getStatus())) {
                updateOwned(record, owner).set(DevRecordDO::getQualityStatus, "UNKNOWN")
                        .set(DevRecordDO::getLastError, "执行已成功，质量结果待核对").update();
                return;
            }
            if ("PREPARE".equals(record.getSubmissionStage())
                    && ex instanceof cn.iocoder.yudao.framework.common.exception.ServiceException) {
                fail(record, owner, "任务执行身份或数据访问授权校验失败");
                return;
            }
            appendLog(logs, "执行核对失败，等待恢复：" + ex.getClass().getSimpleName());
            updateOwned(record, owner).set(DevRecordDO::getStatus, DevRecordDO.STATUS_UNKNOWN)
                    .set(DevRecordDO::getLastError, "执行结果暂不可确认，请查看调度器或等待自动核对").update();
            // 上游 payload/脚本可能进入异常消息，日志只记录异常类型与关联 ID。
            log.warn("[process][Run {} requires reconciliation: {}]", recordId, ex.getClass().getSimpleName());
        } finally {
            updateOwned(record, owner).set(DevRecordDO::getLog, StrUtil.subSufByLength(logs.toString(), 64000))
                    .set(DevRecordDO::getHeartbeatTime, LocalDateTime.now())
                    .set(DevRecordDO::getLeaseOwner, null).set(DevRecordDO::getLeaseUntil, null).update();
        }
    }

    /**
     * 更新持有执行权。
     *
     * @param record 运行记录
     * @param owner 所有者
     * @return Lambda更新处理链条件包装器开发记录
     */
    private com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<DevRecordDO>
    updateOwned(DevRecordDO record, String owner) {
        return new com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<>(recordMapper)
                .eq(DevRecordDO::getId, record.getId()).eq(DevRecordDO::getLeaseOwner, owner)
                .gt(DevRecordDO::getLeaseUntil, LocalDateTime.now());
    }

    /** 上游同业务日期的保存版本必须已成功；配置了质量任务的上游还必须质量通过。 */
    private boolean dependenciesReady(DevRecordDO record, DevTaskSnapshot snapshot) {
        for (Map.Entry<Long, Long> dependency : snapshot.getDependencyVersions().entrySet()) {
            Long count = recordMapper.selectCount(Wrappers.<DevRecordDO>lambdaQuery()
                    .eq(DevRecordDO::getTaskId, dependency.getKey())
                    .eq(DevRecordDO::getVersionId, dependency.getValue())
                    .eq(DevRecordDO::getBusinessDate, record.getBusinessDate())
                    .eq(DevRecordDO::getStatus, DevRecordDO.STATUS_SUCCESS)
                    .in(DevRecordDO::getQualityStatus, List.of("PASSED", "NOT_CONFIGURED")));
            if (count == 0) {
                return false;
            }
        }
        return true;
    }

    /** 保存前校验在本地状态迁移事务之外执行，不把未授权脚本变成可调度版本。 */
    public void validateForSave(DevTaskDO task) {
        if (DevNativeRuntimeSupport.isNative(task)) {
            nativeRuntimeSupport.resolve(task);
            nativeRuntimeSupport.requireActor(task, task.getExecutionUserId());
            buildNativeParams(task);
            return;
        }
        authorizeScript(task);
    }

    /**
     * 授权脚本。
     *
     * @param task 任务记录
     * @return 已授权脚本
     */
    private DevScriptAuthorizationSupport.AuthorizedScript authorizeScript(DevTaskDO task) {
        if (DevNativeRuntimeSupport.isNative(task) || DevTaskDO.TYPE_SHELL.equals(task.getTaskType())) {
            return new DevScriptAuthorizationSupport.AuthorizedScript(null, null, List.of());
        }
        return scriptAuthorizationSupport.authorize(task);
    }

    /**
     * 确保运行工作流。
     *
     * @param record 运行记录
     * @param task 任务记录
     * @param params 业务参数映射
     * @param owner 所有者
     * @param workerGroup 工作线程分组
     * @return 确保运行工作流数值
     */
    private Long ensureRunWorkflow(DevRecordDO record, DevTaskDO task, JSONObject params, String owner,
            String workerGroup) {
        if (record.getDsWorkflowCode() != null) {
            // 创建与发布是两次独立远程写入，需要恢复两者之间发生的崩溃。
            dsClient.workflowDefinition().releaseWorkflowDefinition(record.getDsProjectCode(),
                    record.getDsWorkflowCode(), "ONLINE", "dh_dev_run_" + record.getId());
            return record.getDsWorkflowCode();
        }
        Long dsProject = record.getDsProjectCode();
        String name = "dh_dev_run_" + record.getId();
        JSONObject existing = dsClient.workflowDefinition().queryWorkflowDefinitionListPaging(
                dsProject, name, null, null, 1, 100);
        JSONObject page = existing.getJSONObject("data");
        JSONArray definitions = page == null ? null : page.getJSONArray("totalList");
        Long code = null;
        if (definitions != null) {
            for (int i = 0; i < definitions.size(); i++) {
                JSONObject definition = definitions.getJSONObject(i);
                if (name.equals(definition.getStr("name"))) {
                    code = definition.getLong("code");
                }
            }
        }
        if (code == null) {
            long nodeCode = genTaskCode(dsProject);
            CreateWorkflowDefinitionRequest request = new CreateWorkflowDefinitionRequest();
            request.setName(name);
            request.setDescription("保存版本 " + record.getVersionId() + " 业务日期 " + record.getBusinessDate());
            JSONArray globals = new JSONArray();
            globals.add(new JSONObject().set("prop", "business_date").set("direct", "IN")
                    .set("type", "VARCHAR").set("value", record.getBusinessDate().toString()));
            request.setGlobalParams(globals.toString());
            request.setLocations("[]");
            request.setTimeout(0);
            request.setTaskRelationJson(buildTaskRelationJson(nodeCode));
            request.setTaskDefinitionJson(buildTaskDefinitionJson(task, nodeCode, params, workerGroup,
                    executionEnvironment(DevTaskSnapshot.read(record.getSnapshotJson()))));
            // 作业子定义每次专属；并发由平台作业队列与开发任务单活保护，不再叠加 DS 串行队列。
            request.setExecutionType("JOB".equals(record.getTriggerType())?"PARALLEL":"SERIAL_WAIT");
            JSONObject created = dsClient.workflowDefinition().createWorkflowDefinition(dsProject, request);
            code = created.getJSONObject("data").getLong("code");
        }
        if (code == null) {
            throw new IllegalStateException("创建工作流未返回编号");
        }
        if (updateOwned(record, owner).set(DevRecordDO::getDsWorkflowCode, code).update() != true) {
            return null;
        }
        record.setDsWorkflowCode(code);
        dsClient.workflowDefinition().releaseWorkflowDefinition(dsProject, code, "ONLINE", name);
        return code;
    }

    /**
     * 读取开发调度平台提交器当前观测状态。
     *
     * @param record 运行记录
     * @param owner 所有者
     * @param task 任务记录
     * @param logs 日志
     */
    private void observe(DevRecordDO record, String owner, DevTaskDO task, StringBuilder logs) {
        Integer instanceId = record.getDsInstanceId();
        if (instanceId == null) {
            JSONArray instances;
            if (record.getDsTriggerCode() != null) {
                instances = dsClient.workflowInstance().queryWorkflowInstancesByTriggerCode(
                        record.getDsProjectCode(), record.getDsTriggerCode()).getJSONArray("data");
            } else if (record.getDsWorkflowCode() != null) {
                JSONObject response = dsClient.workflowInstance().queryWorkflowInstanceList(record.getDsProjectCode(),
                        new QueryWorkflowInstanceListRequest().setWorkflowDefinitionCode(record.getDsWorkflowCode())
                                .setPageNo(1).setPageSize(2));
                JSONObject data = response.getJSONObject("data");
                instances = data == null ? null : data.getJSONArray("totalList");
            } else {
                instances = null;
            }
            if (instances == null || instances.size() != 1) {
                updateOwned(record, owner).set(DevRecordDO::getStatus, DevRecordDO.STATUS_UNKNOWN)
                        .set(DevRecordDO::getLastError, "等待确认调度器实例，禁止重复投递").update();
                return;
            }
            instanceId = instances.getJSONObject(0).getInt("id");
            if (instanceId == null) {
                throw new IllegalStateException("实例缺少编号");
            }
            updateOwned(record, owner).set(DevRecordDO::getDsInstanceId, instanceId).update();
            record.setDsInstanceId(instanceId);
        }
        JSONObject response = dsClient.workflowInstance()
                .queryWorkflowInstanceById(record.getDsProjectCode(), instanceId);
        JSONObject instance = response.getJSONObject("data");
        String schedulerState = instance == null ? null : instance.getStr("state");
        String mapped = mapInstanceState(schedulerState);
        if (mapped != null) {
            if (DevNativeRuntimeSupport.isNative(task)) {
                String verified = confirmedNativeTerminalStatus(record, mapped);
                if (verified == null) {
                    updateOwned(record, owner).set(DevRecordDO::getStatus, DevRecordDO.STATUS_UNKNOWN)
                            .set(DevRecordDO::getLastError,
                                    "调度器已结束；原生引擎终态尚未确认或与调度器不一致，请核实集群终态后人工收敛").update();
                    return;
                }
                mapped = verified;
            }
            if (DevRecordDO.STATUS_SUCCESS.equals(mapped)) {
                finish(record, owner, DevRecordDO.STATUS_SUCCESS, null);
                if (!updateQuality(record, owner, task)) {
                    return;
                }
            }
            appendLog(logs, "调度器确认执行终态：" + mapped);
            finish(record, owner, mapped, null);
            return;
        }
        DevRecordDO fresh = recordMapper.selectById(record.getId());
        boolean expired = record.getStartTime() != null && task.getTimeoutSeconds() != null && task.getTimeoutSeconds() > 0
                && record.getStartTime().plusSeconds(task.getTimeoutSeconds() + 300L).isBefore(LocalDateTime.now());
        if (Boolean.TRUE.equals(fresh.getCancelRequested()) || expired) {
            updateOwned(record, owner).set(DevRecordDO::getCancelRequested, true)
                    .set(DevRecordDO::getStatus, DevRecordDO.STATUS_CANCEL_REQUESTED).update();
            dsClient.executor().controlWorkflowInstance(record.getDsProjectCode(), instanceId, "STOP");
        } else {
            // 调度器受理或串行排队仍占用本任务执行槽，只有开始执行才计为运行中。
            boolean running = "RUNNING_EXECUTION".equals(schedulerState);
            boolean queued = schedulerState != null && java.util.Set.of("SERIAL_WAIT", "SUBMITTED_SUCCESS",
                    "WAIT_TO_RUN", "WAITING_THREAD", "WAITING_DEPEND", "DELAY_EXECUTION", "DISPATCH")
                    .contains(schedulerState);
            updateOwned(record, owner).set(DevRecordDO::getStatus, running ? DevRecordDO.STATUS_RUNNING
                            : queued ? DevRecordDO.STATUS_SUBMITTING : DevRecordDO.STATUS_UNKNOWN)
                    .set(DevRecordDO::getLastError, running ? null
                            : queued ? "调度器已受理，等待开始执行" : "调度器状态待确认，禁止重复投递").update();
        }
    }

    /** 本地引擎独立核实整个进程树；YARN 继续使用原有 ResourceManager 终态门禁。 */
    private String confirmedNativeTerminalStatus(DevRecordDO record, String schedulerStatus) {
        try {
            var profile = nativeRuntimeSupport.requireFrozen(DevTaskSnapshot.read(record.getSnapshotJson()));
            if (!"LOCAL".equals(profile.getDeployMode())) {
                return nativeTerminationConfirmed(record, schedulerStatus) ? schedulerStatus : null;
            }
            if (!Objects.equals(profile.getDsProjectCode(), record.getDsProjectCode())) {
                return null;
            }
            String name = "dh_native_" + record.getId();
            try (var api = localApi(profile)) {
                return localTerminalStatus(api.find(name, profile.getTenantCode()), name, profile.getTenantCode(),
                        profile.getEngine(), schedulerStatus, Boolean.TRUE.equals(record.getCancelRequested()));
            }
        } catch (RuntimeException unverifiable) {
            return null;
        }
    }

    /** 缺失、身份不符、未观察到启动或仍有后代进程的记录均不能释放执行槽位。 */
    static String localTerminalStatus(
            List<cn.iocoder.yudao.framework.dolphinscheduler.api.LocalApplicationApi.Execution> executions,
            String name, String user, String engine, String schedulerStatus, boolean cancellationRequested) {
        if (executions == null || executions.isEmpty() || executions.size() > 64
                || executions.stream().anyMatch(execution -> execution == null || !Objects.equals(name, execution.name())
                || !Objects.equals(user, execution.user()) || !Objects.equals(engine, execution.engine())
                || !execution.started() || !execution.finished() || execution.processTreeAlive()
                || !java.util.Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(execution.state()))) {
            return null;
        }
        if (DevRecordDO.STATUS_SUCCESS.equals(schedulerStatus)) {
            return executions.stream().allMatch(execution -> "SUCCEEDED".equals(execution.state())
                    && Integer.valueOf(0).equals(execution.exitCode()))
                    ? DevRecordDO.STATUS_SUCCESS : null;
        }
        if (DevRecordDO.STATUS_STOPPED.equals(schedulerStatus)
                || (cancellationRequested && DevRecordDO.STATUS_FAILED.equals(schedulerStatus))) {
            return DevRecordDO.STATUS_STOPPED;
        }
        return DevRecordDO.STATUS_FAILED.equals(schedulerStatus) ? DevRecordDO.STATUS_FAILED : null;
    }

    cn.iocoder.yudao.framework.dolphinscheduler.api.LocalApplicationApi localApi(
            cn.iocoder.yudao.module.dataforge.framework.dev.DevNativeRuntimeProperties.Profile profile) {
        var bundles = sslBundles.getIfAvailable();
        if (bundles == null) {
            throw exception(DEV_NATIVE_RUNTIME_NOT_READY);
        }
        return new cn.iocoder.yudao.framework.dolphinscheduler.api.LocalApplicationApi(
                java.net.URI.create(profile.getLocalObserverEndpoint()),
                bundles.getBundle(profile.getLocalObserverSslBundle()).createSslContext());
    }

    /**
     * 判断原生终止已确认条件是否成立。
     *
     * @param record 运行记录
     * @param schedulerStatus 调度器状态
     * @return 处理是否成功
     */
    private boolean nativeTerminationConfirmed(DevRecordDO record, String schedulerStatus) {
        try {
            var profile = nativeRuntimeSupport.requireFrozen(DevTaskSnapshot.read(record.getSnapshotJson()));
            if (StrUtil.isBlank(profile.getYarnEndpoint()) || StrUtil.isBlank(profile.getYarnSslBundle())
                    || !Objects.equals(profile.getDsProjectCode(), record.getDsProjectCode())) {
                return false;
            }
            String name = "dh_native_" + record.getId();
            try (var api = yarnApi(profile)) {
                var applications = api.find(name, profile.getTenantCode());
                return !applications.isEmpty() && applications.size() <= 64 && applications.stream()
                        .allMatch(application ->
                                application.terminal() && Objects.equals(application.user(), profile.getTenantCode())
                                        && Objects.equals(application.queue(), profile.getQueue()) && Objects.equals(
                                        application.name(), name)
                                        && (!DevRecordDO.STATUS_SUCCESS.equals(schedulerStatus)
                                        || application.finalStatus() == cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.FinalStatus.SUCCEEDED));
            }
        } catch (RuntimeException unverifiable) {
            return false; // 缺证据不释放活跃执行槽位；原始 diagnostics/HTTP body 不透出。
        }
    }

    /**
     * 获取开发调度平台提交器的YARNAPI。
     *
     * @param profile 配置档案
     * @return YARN应用API
     */
    cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi yarnApi(
            cn.iocoder.yudao.module.dataforge.framework.dev.DevNativeRuntimeProperties.Profile profile) {
        var bundles = sslBundles.getIfAvailable();
        if (bundles == null) {
            throw exception(DEV_NATIVE_RUNTIME_NOT_READY);
        }
        return new cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi(
                java.net.URI.create(profile.getYarnEndpoint()),
                bundles.getBundle(profile.getYarnSslBundle()).createSslContext());
    }

    /**
     * 更新质量。
     *
     * @param record 运行记录
     * @param owner 所有者
     * @param task 任务记录
     * @return 处理是否成功
     */
    private boolean updateQuality(DevRecordDO record, String owner, DevTaskDO task) {
        QualityGateService.Result quality = qualityGateService.evaluate("DEV:" + record.getId(),
                task.getQualityTaskId(), record.getExecutionUserId());
        boolean accepted = "PASSED".equals(quality.status()) || "NOT_CONFIGURED".equals(quality.status());
        updateOwned(record, owner).set(DevRecordDO::getQualityStatus, quality.status())
                .set(DevRecordDO::getQualityInstanceId, quality.instanceId())
                .set(DevRecordDO::getLastError, accepted ? null : "质量门禁尚未通过：" + quality.message()).update();
        return accepted;
    }

    /**
     * 映射实例状态。
     *
     * @param state 状态
     * @return 映射实例状态文本
     */
    static String mapInstanceState(String state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case "SUCCESS" -> DevRecordDO.STATUS_SUCCESS;
            case "FAILURE", "FAILED" -> DevRecordDO.STATUS_FAILED;
            case "STOP", "KILL", "PAUSE" -> DevRecordDO.STATUS_STOPPED;
            default -> null;
        };
    }

    /**
     * 标记失败开发调度平台提交器。
     *
     * @param record 运行记录
     * @param owner 所有者
     * @param message 消息
     */
    private void fail(DevRecordDO record, String owner, String message) {
        finish(record, owner, DevRecordDO.STATUS_FAILED, message);
    }

    /**
     * 保存开发调度平台提交器结束状态。
     *
     * @param record 运行记录
     * @param owner 所有者
     * @param status 目标状态
     * @param message 消息
     */
    private void finish(DevRecordDO record, String owner, String status, String message) {
        LocalDateTime end = LocalDateTime.now();
        record.setStatus(status);
        updateOwned(record, owner).set(DevRecordDO::getStatus, status).set(DevRecordDO::getEndTime, end)
                .set(DevRecordDO::getLastError, message)
                .set(DevRecordDO::getDurationMs,
                        record.getStartTime() == null ? 0L : Duration.between(record.getStartTime(), end).toMillis())
                .set(DevRecordDO::getStatementSuccess, DevRecordDO.STATUS_SUCCESS.equals(status) ? 1 : 0).update();
    }

    /**
     * 构造任务参数。
     *
     * @param task 任务记录
     * @param logBuffer 日志缓冲区
     * @return JSON对象
     */
    private JSONObject buildTaskParams(DevTaskDO task, StringBuilder logBuffer) {
        if (!DevNativeRuntimeSupport.isNative(task)) {
            DevTaskDefinition.requireExecutable(task);
        }
        return buildTaskParams(task, logBuffer, authorizeScript(task));
    }

    /**
     * 构造任务参数。
     *
     * @param task 任务记录
     * @param logBuffer 日志缓冲区
     * @param authorizedScript 已授权脚本
     * @return JSON对象
     */
    private JSONObject buildTaskParams(DevTaskDO task, StringBuilder logBuffer,
            DevScriptAuthorizationSupport.AuthorizedScript authorizedScript) {
        return buildTaskParams(task, logBuffer, authorizedScript, "dh_native_" + task.getId());
    }

    /**
     * 构造任务参数。
     *
     * @param task 任务记录
     * @param logBuffer 日志缓冲区
     * @param authorizedScript 已授权脚本
     * @param applicationName 应用名称
     * @return JSON对象
     */
    private JSONObject buildTaskParams(DevTaskDO task, StringBuilder logBuffer,
            DevScriptAuthorizationSupport.AuthorizedScript authorizedScript, String applicationName) {
        if (DevNativeRuntimeSupport.isNative(task)) {
            return buildNativeParams(task, applicationName);
        }
        DevTaskDefinition.requireExecutable(task);
        var config = DevTaskDefinition.readExecutionConfig(task.getExecutionConfigJson());
        JSONObject taskParams = new JSONObject();
        JSONArray parameters = new JSONArray();
        for (var param : config.getLocalParams()) {
            parameters.add(new JSONObject().set("prop", param.getProp()).set("type", param.getType())
                    .set("value", param.getValue()).set("direct", "IN"));
        }
        taskParams.set("localParams", parameters);
        taskParams.set("resourceList", new JSONArray());
        if (DevTaskDO.TYPE_SHELL.equals(task.getTaskType())) {
            taskParams.set("rawScript", StrUtil.nullToEmpty(task.getContent()));
            return taskParams;
        }
        var steps = authorizedScript.steps();
        if (steps.isEmpty()) {
            throw exception(DEV_TASK_CONTENT_EMPTY);
        }
        DsDatasource datasource = resolveDsDatasource(task, logBuffer, authorizedScript.configurationRevision());
        boolean lastIsQuery = steps.getLast().query();
        JSONArray preStatements = new JSONArray();
        steps.subList(0, lastIsQuery ? steps.size() - 1 : steps.size()).forEach(step -> preStatements.add(step.sql()));
        taskParams.set("type", datasource.type());
        taskParams.set("datasource", datasource.id());
        // DS 主 SQL 会再次分段；完整 DDL/过程/CALL 放逐项原样预编译的 preStatements。
        // 查询只返回行数，避免执行器的行日志绕过治理结果脱敏；即时调试走原治理返回通道。
        taskParams.set("sql", lastIsQuery ? steps.getLast().sql()
                : cn.iocoder.yudao.framework.dbswitch.core.service.ManagedSchemaScriptPlan.completionQuery(
                authorizedScript.product()));
        taskParams.set("sqlType", "0");
        taskParams.set("preStatements", preStatements);
        taskParams.set("postStatements", new JSONArray());
        taskParams.set("segmentSeparator", "");
        taskParams.set("displayRows", 1);
        taskParams.set("limit", task.getMaxRows() == null ? 1000 : task.getMaxRows());
        return taskParams;
    }

    /**
     * 构造原生参数。
     *
     * @param task 任务记录
     * @return JSON对象
     */
    private JSONObject buildNativeParams(DevTaskDO task) {
        return buildNativeParams(task, "dh_native_" + task.getId());
    }

    /**
     * 构造原生参数。
     *
     * @param task 任务记录
     * @param applicationName 应用名称
     * @return JSON对象
     */
    private JSONObject buildNativeParams(DevTaskDO task, String applicationName) {
        var profile = nativeRuntimeSupport.resolve(task);
        var config = DevTaskDefinition.readConfig(task.getProgramConfigJson());
        List<cn.iocoder.yudao.module.dataforge.framework.dev.DevNativeRuntimeProperties.Artifact> selected = new ArrayList<>();
        if (config.getArtifactFileId() != null) {
            selected.add(DevNativeRuntimeSupport.artifact(profile, config.getArtifactFileId(), false));
        }
        if (config.getDependencyFileIds() != null) {
            config.getDependencyFileIds()
                    .forEach(id -> selected.add(DevNativeRuntimeSupport.artifact(profile, id, true)));
        }
        if (selected.stream().mapToLong(resource -> resource.getMaxBytes()).sum() > 536_870_912L) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "一次执行的制品校验预算不能超过 512 MB");
        }
        for (var resource : selected) {
            String actual;
            try {
                actual = dsClient.resource().resourceSha256(resource.getResourceName(), resource.getMaxBytes());
            } catch (RuntimeException unavailable) {
                throw exception(DEV_PROGRAM_CONFIG_INVALID, "受管制品无法完成有界摘要校验");
            }
            if (!resource.getSha256().equalsIgnoreCase(actual)) {
                throw exception(DEV_PROGRAM_CONFIG_INVALID, "受管制品内容已变更，请重新登记并保存任务");
            }
        }
        return new JSONObject(DevNativeParameters.build(task, profile, applicationName).taskParams());
    }

    /**
     * 生成任务编码。
     *
     * @param projectCode 项目编码
     * @return 生成任务编码数值
     */
    private long genTaskCode(Long projectCode) {
        JSONObject response = dsClient.taskDefinition().genTaskCodeList(projectCode, 1);
        JSONArray codes = response.getJSONArray("data");
        return codes.getLong(0);
    }

    /**
     * 构造任务定义JSON。
     *
     * @param task 任务记录
     * @param taskCode 任务编码
     * @param taskParams 任务参数
     * @param workerGroup 工作线程分组
     * @return 构造任务定义JSON文本
     */
    private String buildTaskDefinitionJson(DevTaskDO task, long taskCode, JSONObject taskParams, String workerGroup) {
        return buildTaskDefinitionJson(task, taskCode, taskParams, workerGroup, configuredEnvironmentCode());
    }

    private String buildTaskDefinitionJson(DevTaskDO task, long taskCode, JSONObject taskParams, String workerGroup, Long frozenEnvironment) {
        boolean shell = DevTaskDO.TYPE_SHELL.equals(task.getTaskType());
        var config = DevTaskDefinition.readExecutionConfig(task.getExecutionConfigJson());
        JSONObject definition = new JSONObject();
        definition.set("code", taskCode);
        definition.set("name", StrUtil.sub(task.getName(), 0, 100));
        definition.set("description", StrUtil.nullToEmpty(task.getDescription()));
        definition.set("flag", "YES");
        boolean nativeTask = DevNativeRuntimeSupport.isNative(task);
        var profile = nativeTask ? nativeRuntimeSupport.resolve(task) : null;
        definition.set("taskType", nativeTask ? profile.getEngine() : shell ? "SHELL" : "SQL");
        definition.set("taskParams", taskParams);
        definition.set("taskPriority", config.getPriority());
        definition.set("workerGroup", StrUtil.blankToDefault(workerGroup, "default"));
        definition.set("environmentCode", nativeTask ? profile.getEnvironmentCode() : frozenEnvironment);
        definition.set("failRetryTimes", config.getRetryTimes());
        definition.set("failRetryInterval", config.getRetryIntervalMinutes());
        boolean timeoutEnabled = task.getTimeoutSeconds() != null && task.getTimeoutSeconds() > 0;
        definition.set("timeoutFlag", timeoutEnabled ? "OPEN" : "CLOSE");
        definition.set("timeoutNotifyStrategy", "FAILED");
        // DS 3.4.2 的超时监视器只检查分钟数，关闭标志还必须同时传零。
        definition.set("timeout", timeoutEnabled
                ? (task.getTimeoutSeconds().longValue() + 59) / 60 : 0);
        definition.set("delayTime", config.getDelayMinutes());
        definition.set("cpuQuota", -1);
        definition.set("memoryMax", -1);
        definition.set("isCache", "NO");
        definition.set("taskExecuteType", "BATCH");
        JSONArray array = new JSONArray();
        array.add(definition);
        return array.toString();
    }

    /**
     * 构造任务关系JSON。
     *
     * @param taskCode 任务编码
     * @return 构造任务关系JSON文本
     */
    private String buildTaskRelationJson(long taskCode) {
        JSONObject relation = new JSONObject();
        relation.set("name", "");
        relation.set("preTaskCode", 0);
        relation.set("preTaskVersion", 0);
        relation.set("postTaskCode", taskCode);
        relation.set("postTaskVersion", 0);
        relation.set("conditionType", "NONE");
        relation.set("conditionParams", new JSONObject());
        JSONArray array = new JSONArray();
        array.add(relation);
        return array.toString();
    }

    // ==================== DS 数据源同步 ====================

    record DsDatasource(int id, String type) {

    }

    /**
     * 解析 DS 数据源：显式指定优先；否则按平台数据源在 DS 查找/创建同名数据源
     */
    private DsDatasource resolveDsDatasource(DevTaskDO task, StringBuilder logBuffer, String expectedRevision) {
        if (task.getDsDatasourceId() != null) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID,
                    "执行快照包含未经项目绑定解析的调度器数据源编号，请重新保存任务");
        }
        if (task.getDatabaseId() == null) {
            throw exception(DEV_TASK_DATABASE_EMPTY);
        }
        cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO database = databaseMapper.selectById(
                task.getDatabaseId());
        if (database == null) {
            throw exception(DATABASE_NOT_EXISTS);
        }
        AccessDatabaseResolver.ConnectionInfo conn = databaseResolver.resolve(database);
        if (!Objects.equals(expectedRevision, conn.getConfigurationRevision())) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "数据源配置在脚本授权后发生变化，请重试");
        }
        String dsType = mapDsType(conn.getProductType());
        // 目标端修改或凭据轮换时创建新的 DS 数据源，保留正在运行的快照。
        String revision = cn.hutool.crypto.digest.DigestUtil.sha256Hex(String.valueOf(database.getUpdateTime()))
                .substring(0, 16);
        String dsName = "dh_ds_" + task.getDatabaseId() + "_" + revision;
        // 查找既有数据源
        JSONObject page = dsClient.dataSource().queryDataSourceListPaging(dsName, 1, 10);
        JSONArray list = page.getJSONObject("data") == null
                ? null : page.getJSONObject("data").getJSONArray("totalList");
        if (list != null) {
            for (Object item : list) {
                JSONObject datasource = (JSONObject) item;
                if (dsName.equals(datasource.getStr("name"))) {
                    return new DsDatasource(datasource.getInt("id"), datasource.getStr("type", dsType));
                }
            }
        }
        // 创建数据源（拆解 JDBC URL）
        Matcher matcher = JDBC_URL_PATTERN.matcher(StrUtil.nullToEmpty(conn.getJdbcUrl()));
        if (!matcher.matches()) {
            throw exception(DEV_DS_SUBMIT_FAILED,
                    "当前调度器适配不能可靠解析此连接地址，请由管理员完善该产品的数据源适配");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", dsName);
        body.put("note", "数据中台自动同步（数据源编号 " + task.getDatabaseId() + "）");
        body.put("type", dsType);
        body.put("host", matcher.group(1));
        body.put("port", matcher.group(2) == null ? defaultPort(dsType) : Integer.parseInt(matcher.group(2)));
        String databaseName = matcher.group(3);
        Map<String, String> properties = new LinkedHashMap<>();
        String jdbcUrl = conn.getJdbcUrl();
        if (jdbcUrl.contains("?")) {
            for (String pair : jdbcUrl.substring(jdbcUrl.indexOf('?') + 1).split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    properties.put(java.net.URLDecoder.decode(pair.substring(0, equals),
                                    java.nio.charset.StandardCharsets.UTF_8),
                            java.net.URLDecoder.decode(pair.substring(equals + 1),
                                    java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } else if ("SQLSERVER".equals(dsType) && jdbcUrl.contains(";")) {
            for (String pair : jdbcUrl.substring(jdbcUrl.indexOf(';') + 1).split(";")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    String key = pair.substring(0, equals).trim();
                    if ("databaseName".equalsIgnoreCase(key)) {
                        databaseName = pair.substring(equals + 1);
                    } else {
                        properties.put(key, pair.substring(equals + 1));
                    }
                }
            }
        }
        body.put("database", StrUtil.blankToDefault(databaseName, conn.getSchema()));
        body.put("userName", conn.getUsername());
        body.put("password", conn.getPassword());
        if ("POSTGRESQL".equals(dsType) && StrUtil.isNotBlank(conn.getSchema())) {
            properties.putIfAbsent("currentSchema", conn.getSchema());
        }
        body.put("other", properties);
        JSONObject response = dsClient.dataSource().createDataSource(body);
        appendLog(logBuffer, "已在 DS 同步创建数据源 " + dsName + "（类型 " + dsType + "）");
        Integer id = response.getJSONObject("data") == null
                ? null : response.getJSONObject("data").getInt("id");
        if (id == null) {
            // 部分版本创建接口不回传 id，重查一次
            JSONObject query = dsClient.dataSource().queryDataSourceListPaging(dsName, 1, 10);
            JSONArray created = query.getJSONObject("data").getJSONArray("totalList");
            if (created != null) {
                for (int i = 0; i < created.size(); i++) {
                    JSONObject found = created.getJSONObject(i);
                    if (dsName.equals(found.getStr("name"))) {
                        id = found.getInt("id");
                    }
                }
            }
            if (id == null) {
                throw new IllegalStateException("调度器未返回精确匹配的数据源编号");
            }
        }
        return new DsDatasource(id, dsType);
    }

    /**
     * dbswitch 产品类型 → DS 数据源类型
     */
    private String mapDsType(ProductTypeEnum productType) {
        if (productType == null) {
            throw exception(DEV_DS_DATASOURCE_EMPTY);
        }
        return switch (productType) {
            case MYSQL, MARIADB, TIDB -> "MYSQL";
            case POSTGRESQL, KINGBASE, OPENGAUSS, GREENPLUM -> "POSTGRESQL";
            case ORACLE -> "ORACLE";
            case SQLSERVER -> "SQLSERVER";
            case HIVE -> "HIVE";
            case DM -> "DAMENG";
            case OCEANBASE -> "OCEANBASE";
            case DORIS, STARROCKS -> "STARROCKS";
            case CLICKHOUSE -> "CLICKHOUSE";
            case DB2 -> "DB2";
            default -> throw exception(DEV_DS_SUBMIT_FAILED,
                    "数据源类型 " + productType + " 暂不支持自动同步到 DolphinScheduler，请由管理员完善该产品的数据源适配");
        };
    }

    /**
     * 获取开发调度平台提交器的默认端口。
     *
     * @param dsType 调度平台类型
     * @return 默认端口数值
     */
    private int defaultPort(String dsType) {
        return switch (dsType) {
            case "MYSQL", "OCEANBASE", "STARROCKS" -> 3306;
            case "POSTGRESQL" -> 5432;
            case "ORACLE" -> 1521;
            case "SQLSERVER" -> 1433;
            case "HIVE", "SPARK" -> 10000;
            case "DAMENG" -> 5236;
            case "CLICKHOUSE" -> 8123;
            case "DB2" -> 50000;
            default -> 0;
        };
    }

    /**
     * 追加开发调度平台提交器的日志。
     *
     * @param buffer 缓冲区
     * @param message 消息
     */
    private void appendLog(StringBuilder buffer, String message) {
        buffer.append(LocalDateTime.now().format(LOG_TIME)).append(" [INFO] ").append(message).append('\n');
    }

}
