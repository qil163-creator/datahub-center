package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.api.quality.dto.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.etl.InlineQualityReportDO;
import cn.iocoder.yudao.module.governance.dal.mysql.etl.InlineQualityReportMapper;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.transaction.annotation.Transactional;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 以稳定运行键提交质量检查；按实例返回结果，禁止用最近结果替代本次成果验收。 */
@Service
public class QualityRunService {

    @Resource
    private QualityTaskMapper taskMapper;

    @Resource
    private QualityTaskInstanceMapper instanceMapper;

    @Resource
    private QualityAuditLogMapper auditMapper;

    @Resource
    private QualityTaskExecutor executor;

    @Resource
    private QualityRunAuthorizationService authorization;

    @Resource
    private QualityEtlBatchRunMapper etlBatchRunMapper;

    @Resource
    private InlineQualityReportMapper inlineQualityReportMapper;

    @Resource
    private QualityTaskObjectMapper taskObjectMapper;

    @Resource
    private QualityCheckObjectMapper checkObjectMapper;

    @Resource
    private cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver databaseResolver;

    /**
     * 校验质量运行配置满足当前业务约束。
     */
    public void validate(Long taskId, Long userId) {
        QualityTaskDO task = authorization.require(taskId, userId);
        executor.validateGateDefinition(task);
    }

    /**
     * 提交幂等。
     */
    public Long submitIdempotent(Long taskId, String requestKey, Long executionUserId, String runType) {
        QualityTaskInstanceDO existing = instanceMapper.selectOne(new LambdaQueryWrapperX<QualityTaskInstanceDO>()
                .eq(QualityTaskInstanceDO::getRequestKey, requestKey));
        if (existing != null) {
            return verifyIdempotentRun(existing, taskId, executionUserId, runType);
        }
        QualityTaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            throw exception(QUALITY_TASK_NOT_EXISTS);
        }
        QualityTaskInstanceDO instance = QualityTaskInstanceDO.builder()
                .taskId(task.getId())
                .instanceName(task.getTaskName() + "-" + StrUtil.blankToDefault(runType, "RECHECK"))
                .runType(StrUtil.blankToDefault(runType, "MANUAL"))
                .instanceStatus(QualityTaskInstanceDO.STATUS_QUEUED)
                .requestKey(requestKey)
                .executionUserId(executionUserId)
                .definitionSnapshot(executor.snapshotTaskDefinition(task))
                .runCount(1).totalCount(0L).failCount(0L).passCount(0L).build();
        try {
            instanceMapper.insert(instance);
        } catch (DuplicateKeyException duplicate) {
            // PostgreSQL 的事务已经失败，禁止在同一事务中查询并伪造成功；由调用方整笔重试。
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw duplicate;
            }
            existing = instanceMapper.selectOne(new LambdaQueryWrapperX<QualityTaskInstanceDO>()
                    .eq(QualityTaskInstanceDO::getRequestKey, requestKey));
            if (existing == null) {
                throw duplicate;
            }
            return verifyIdempotentRun(existing, taskId, executionUserId, runType);
        }
        dispatchAfterCommit(instance.getId());
        return instance.getId();
    }

    /**
     * 针对一个已提交 ETL 批次，固定并排队执行真实质量实例。
     * 仅接受来自可信 DataForge RPC 的请求，且其中不包含 SQL 表达式。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long submitEtlBatch(QualityEtlBatchRunReqDTO request) {
        QualityEtlBatchRunReqDTO.Manifest manifest = request.getManifest();
        QualityTaskDO task = authorization.require(request.getQualityTaskId(), request.getExecutionUserId());
        if (manifest.getSchemaVersion() == null || manifest.getSchemaVersion() != 1
                || manifest.getRowCount() > 1_000_000 || manifest.getAdmittedRowCount() > manifest.getRowCount()
                || manifest.getIdentityFields().stream().distinct().count() != manifest.getIdentityFields().size()
                || manifest.getIdentityFields().stream()
                .anyMatch(field -> Objects.equals(field, manifest.getBatchColumn())
                        || Objects.equals(field, manifest.getRowColumn()) || Objects.equals(field,
                        manifest.getStatusColumn()))) {
            throw exception(QUALITY_RUN_NOT_READY);
        }
        InlineQualityReportDO report = inlineQualityReportMapper.selectById(request.getInlineReportId());
        long expectedAdmitted = report == null ? -1L : nullToZero(report.getPassCount())
                + (Boolean.TRUE.equals(manifest.getAllowWarnings()) ? nullToZero(report.getWarnCount()) : 0L);
        long classified = report == null ? -1L : nullToZero(report.getPassCount()) + nullToZero(report.getWarnCount())
                + nullToZero(report.getRejectCount());
        if (report == null || !"ETL_INLINE".equals(report.getSourceType())
                || !Objects.equals(report.getExecutionUserId(), request.getExecutionUserId())
                || !Objects.equals(report.getRunId(), manifest.getRunId())
                || !Objects.equals(report.getNodeId(), request.getQualityNodeId())
                || !Objects.equals(report.getBatchId(), request.getQualityBatchId())
                || !Objects.equals(classified, report.getTotalCount())
                || StrUtil.isBlank(report.getIdentityFieldsJson()) || StrUtil.isBlank(report.getIdentityHash())
                || !Objects.equals(JsonUtils.parseArray(report.getIdentityFieldsJson(), String.class),
                manifest.getIdentityFields())
                || !Objects.equals(report.getIdentityHash(), manifest.getIdentityHash())
                || !Objects.equals(expectedAdmitted, manifest.getAdmittedRowCount())) {
            throw exception(QUALITY_RUN_NOT_READY);
        }
        String requestKey = "ETL_TARGET:" + task.getId() + ":" + manifest.getTargetBatchId();
        String manifestJson = canonicalManifest(request);
        String manifestHash = cn.hutool.crypto.digest.DigestUtil.sha256Hex(manifestJson);
        QualityEtlBatchRunDO existingBatch = etlBatchRunMapper.selectByTaskAndTargetBatch(task.getId(),
                manifest.getTargetBatchId());
        if (existingBatch != null) {
            if (!Objects.equals(existingBatch.getManifestHash(), manifestHash)
                    || !Objects.equals(existingBatch.getInlineReportId(), request.getInlineReportId())) {
                throw exception(QUALITY_RUN_KEY_CONFLICT);
            }
            QualityTaskInstanceDO existingInstance = instanceMapper.selectById(existingBatch.getInstanceId());
            if (existingInstance == null || !Objects.equals(existingInstance.getExecutionUserId(),
                    request.getExecutionUserId())) {
                throw exception(QUALITY_RUN_KEY_CONFLICT);
            }
            if (QualityTaskInstanceDO.STATUS_QUEUED.equals(existingInstance.getInstanceStatus())) {
                dispatchAfterCommit(existingInstance.getId());
            }
            return existingInstance.getId();
        }
        List<Long> objectIds = taskObjectMapper.selectListByTaskId(task.getId()).stream()
                .map(QualityTaskObjectDO::getObjectId).toList();
        List<QualityCheckObjectDO> bound = objectIds.isEmpty() ? List.of() : checkObjectMapper.selectBatchIds(objectIds)
                .stream()
                .filter(object -> Objects.equals(object.getDatasourceConnId(), manifest.getDatabaseId())
                        && Objects.equals(object.getTableName(), manifest.getTableName()))
                .toList();
        if (bound.size() != 1) {
            throw exception(QUALITY_RUN_NOT_READY);
        }
        var conn = databaseResolver.resolve(manifest.getDatabaseId(), QUALITY_CHECK_OBJECT_NOT_EXISTS);
        if (!Objects.equals(conn.getDatabaseId(), manifest.getDatabaseId())
                || !Objects.equals(normalizeSchema(conn.getSchema()), normalizeSchema(manifest.getSchemaName()))) {
            throw exception(QUALITY_RUN_NOT_READY);
        }
        QualityTaskInstanceDO existing = instanceMapper.selectOne(new LambdaQueryWrapperX<QualityTaskInstanceDO>()
                .eq(QualityTaskInstanceDO::getRequestKey, requestKey));
        if (existing != null) {
            throw exception(QUALITY_RUN_KEY_CONFLICT);
        }
        List<String> statuses = Boolean.TRUE.equals(manifest.getAllowWarnings()) ? List.of("PASS", "WARN") : List.of(
                "PASS");
        String snapshot = executor.snapshotEtlBatchDefinition(task, bound.get(0), manifest.getBatchColumn(),
                manifest.getBatchValue(), manifest.getStatusColumn(), statuses, manifest.getAdmittedRowCount());
        QualityTaskInstanceDO instance = QualityTaskInstanceDO.builder().taskId(task.getId())
                .instanceName(task.getTaskName() + "-ETL批次质检").runType("ETL_TARGET_BATCH")
                .instanceStatus(QualityTaskInstanceDO.STATUS_QUEUED).requestKey(requestKey)
                .executionUserId(request.getExecutionUserId()).execUser(String.valueOf(request.getExecutionUserId()))
                .definitionSnapshot(snapshot).runCount(1).totalCount(0L).failCount(0L).passCount(0L).build();
        // 唯一约束冲突会中止当前 PostgreSQL 事务；调用方重试整个 RPC。
        instanceMapper.insert(instance);
        QualityEtlBatchRunDO batchRun = QualityEtlBatchRunDO.builder().qualityTaskId(task.getId())
                .checkObjectId(bound.get(0).getId()).inlineReportId(report.getId())
                .qualityNodeId(request.getQualityNodeId()).qualityBatchId(request.getQualityBatchId())
                .targetBatchId(manifest.getTargetBatchId()).runId(manifest.getRunId()).sinkNodeId(manifest.getNodeId())
                .recordId(manifest.getRecordId()).replayOfBatchId(manifest.getReplayOfBatchId())
                .replayOfRecordId(manifest.getReplayOfRecordId())
                .workOrderSubmissionId(manifest.getWorkOrderSubmissionId())
                .batchKey(cn.hutool.crypto.digest.DigestUtil.sha256Hex("etl-target:" + manifest.getTargetBatchId()))
                .physicalBatchValue(manifest.getBatchValue()).manifestHash(manifestHash).manifestJson(manifestJson)
                .rowCount(manifest.getRowCount()).admittedRowCount(manifest.getAdmittedRowCount())
                .allowWarnings(manifest.getAllowWarnings())
                .identityFieldsJson(JsonUtils.toJsonString(manifest.getIdentityFields()))
                .identityHash(manifest.getIdentityHash()).instanceId(instance.getId()).state("QUEUED").build();
        if (etlBatchRunMapper.insert(batchRun) != 1 || batchRun.getId() == null) {
            throw exception(QUALITY_RUN_NOT_READY);
        }
        dispatchAfterCommit(instance.getId());
        return instance.getId();
    }

    /**
     * 将空数值转换为零。
     */
    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 规范化质量运行的模式。
     */
    private static String normalizeSchema(String schema) {
        return schema == null ? "" : schema.trim();
    }

    /**
     * 生成质量执行清单的稳定序列化内容。
     */
    private String canonicalManifest(QualityEtlBatchRunReqDTO request) {
        QualityEtlBatchRunReqDTO.Manifest m = request.getManifest();
        Map<String, Object> stable = new LinkedHashMap<>();
        stable.put("qualityTaskId", request.getQualityTaskId());
        stable.put("executionUserId", request.getExecutionUserId());
        stable.put("inlineReportId", request.getInlineReportId());
        stable.put("qualityNodeId", request.getQualityNodeId());
        stable.put("qualityBatchId", request.getQualityBatchId());
        stable.put("schemaVersion", m.getSchemaVersion());
        stable.put("targetBatchId", m.getTargetBatchId());
        stable.put("recordId", m.getRecordId());
        stable.put("replayOfBatchId", m.getReplayOfBatchId());
        stable.put("replayOfRecordId", m.getReplayOfRecordId());
        stable.put("workOrderSubmissionId", m.getWorkOrderSubmissionId());
        stable.put("runId", m.getRunId());
        stable.put("nodeId", m.getNodeId());
        stable.put("databaseId", m.getDatabaseId());
        stable.put("schemaName", m.getSchemaName());
        stable.put("tableName", m.getTableName());
        stable.put("batchColumn", m.getBatchColumn());
        stable.put("batchValue", m.getBatchValue());
        stable.put("rowColumn", m.getRowColumn());
        stable.put("statusColumn", m.getStatusColumn());
        stable.put("payloadHash", m.getPayloadHash());
        stable.put("rowCount", m.getRowCount());
        stable.put("admittedRowCount", m.getAdmittedRowCount());
        stable.put("allowWarnings", m.getAllowWarnings());
        stable.put("identityFields", m.getIdentityFields());
        stable.put("identityHash", m.getIdentityHash());
        return JsonUtils.toJsonString(stable);
    }

    /**
     * 校验并提交质量运行请求。
     */
    public Long submit(QualityRunReqDTO request) {
        QualityTaskInstanceDO existing = instanceMapper.selectOne(
                new LambdaQueryWrapperX<QualityTaskInstanceDO>().eq(QualityTaskInstanceDO::getRequestKey,
                        request.getRequestKey()));
        if (existing != null) {
            return verifyRequest(existing, request);
        }
        validate(request.getQualityTaskId(), request.getExecutionUserId());
        QualityTaskDO task = taskMapper.selectById(request.getQualityTaskId());
        QualityTaskInstanceDO instance = QualityTaskInstanceDO.builder()
                .taskId(task.getId()).instanceName(task.getTaskName() + "-研发质检")
                .runType("DATAFORGE").instanceStatus("QUEUED").requestKey(request.getRequestKey())
                .executionUserId(request.getExecutionUserId()).execUser(String.valueOf(request.getExecutionUserId()))
                .definitionSnapshot(executor.snapshotGateDefinition(task))
                .runCount(1).totalCount(0L).failCount(0L).passCount(0L).build();
        try {
            instanceMapper.insert(instance);
        } catch (DuplicateKeyException duplicate) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw duplicate;
            }
            existing = instanceMapper.selectOne(
                    new LambdaQueryWrapperX<QualityTaskInstanceDO>().eq(QualityTaskInstanceDO::getRequestKey,
                            request.getRequestKey()));
            if (existing == null) {
                throw duplicate;
            }
            return verifyRequest(existing, request);
        }
        dispatchAfterCommit(instance.getId());
        return instance.getId();
    }

    /**
     * 核验质量运行的请求，不满足约束时拒绝当前操作。
     */
    private Long verifyRequest(QualityTaskInstanceDO instance, QualityRunReqDTO request) {
        if (!Objects.equals(instance.getTaskId(), request.getQualityTaskId())
                || !Objects.equals(instance.getExecutionUserId(), request.getExecutionUserId())) {
            throw exception(QUALITY_RUN_KEY_CONFLICT);
        }
        if ("QUEUED".equals(instance.getInstanceStatus())) {
            dispatchAfterCommit(instance.getId());
        }
        return instance.getId();
    }

    /**
     * 核验质量运行的幂等运行，不满足约束时拒绝当前操作。
     */
    private Long verifyIdempotentRun(QualityTaskInstanceDO existing, Long taskId, Long userId, String runType) {
        if (!Objects.equals(existing.getTaskId(), taskId)
                || !Objects.equals(existing.getExecutionUserId(), userId)
                || !Objects.equals(existing.getRunType(), StrUtil.blankToDefault(runType, "MANUAL"))) {
            throw exception(QUALITY_RUN_KEY_CONFLICT);
        }
        if (QualityTaskInstanceDO.STATUS_QUEUED.equals(existing.getInstanceStatus())) {
            dispatchAfterCommit(existing.getId());
        }
        return existing.getId();
    }

    /**
     * 派发变更后提交。
     */
    private void dispatchAfterCommit(Long instanceId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 在当前事务成功提交后触发质量运行后续处理。
                 */
                @Override
                public void afterCommit() {
                    executor.dispatchGate(instanceId);
                }
            });
        } else {
            executor.dispatchGate(instanceId);
        }
    }

    /**
     * 组装质量运行处理结果。
     */
    public QualityTaskResultRespDTO result(Long instanceId, Long userId) {
        QualityTaskInstanceDO instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            throw exception(QUALITY_TASK_INSTANCE_NOT_EXISTS);
        }
        if (!Objects.equals(instance.getExecutionUserId(), userId)) {
            throw exception(QUALITY_RUN_FORBIDDEN);
        }
        QualityTaskResultRespDTO result = new QualityTaskResultRespDTO();
        result.setInstanceId(instance.getId());
        result.setTaskId(instance.getTaskId());
        result.setInstanceStatus(instance.getInstanceStatus());
        result.setStartTime(instance.getStartTime());
        result.setEndTime(instance.getEndTime());
        result.setTotalCount(instance.getTotalCount());
        result.setFailCount(instance.getFailCount());
        result.setScore(instance.getScore());
        result.setErrorRuleCount(auditMapper.selectCount(new LambdaQueryWrapperX<QualityAuditLogDO>()
                .eq(QualityAuditLogDO::getInstanceId, instanceId)
                .eq(QualityAuditLogDO::getResultType, QualityAuditLogDO.RESULT_ERROR)));
        result.setFailedRuleCount(auditMapper.selectCount(new LambdaQueryWrapperX<QualityAuditLogDO>()
                .eq(QualityAuditLogDO::getInstanceId, instanceId)
                .eq(QualityAuditLogDO::getResultType, QualityAuditLogDO.RESULT_FAIL)));
        return result;
    }

    /** 全局扫描；已运行但租约过期的检查标为未知，保留现场，绝不伪造通过或自动重复写审计。 */
    public void recover() {
        instanceMapper.update(null, Wrappers.lambdaUpdate(QualityTaskInstanceDO.class)
                .set(QualityTaskInstanceDO::getInstanceStatus, QualityTaskInstanceDO.STATUS_UNKNOWN)
                .set(QualityTaskInstanceDO::getErrorFlag, "执行租约失效，保留现场，禁止自动重放写操作")
                .eq(QualityTaskInstanceDO::getInstanceStatus, QualityTaskInstanceDO.STATUS_RUNNING)
                .lt(QualityTaskInstanceDO::getLeaseUntil, LocalDateTime.now()));
        instanceMapper.selectList(new LambdaQueryWrapperX<QualityTaskInstanceDO>()
                        .eq(QualityTaskInstanceDO::getInstanceStatus, QualityTaskInstanceDO.STATUS_QUEUED)
                        .orderByAsc(QualityTaskInstanceDO::getId).last("LIMIT 20"))
                .forEach(instance -> executor.dispatchGate(instance.getId()));
    }
}
