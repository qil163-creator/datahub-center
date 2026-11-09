package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderCommandReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderStatisticsRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyReviewReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyRetryReqVO;
import cn.iocoder.yudao.module.governance.api.etl.dto.InlineQualityReportDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderReplayDescribeReqDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderReplayDescribeRespDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderRecheckAttachReqDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.etl.InlineQualityReportDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.etl.InlineQualityReportMapper;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

@Service
@Validated
public class QualityWorkOrderServiceImpl implements QualityWorkOrderService {

    private static final long NOTIFY_CLAIM_LEASE_SECONDS = 300;

    private static final ZoneId NOTIFY_CLOCK_ZONE = ZoneId.of("Asia/Shanghai");

    @Resource
    private QualityWorkOrderMapper workOrderMapper;

    @Resource
    private QualityWorkOrderIssueMapper workOrderIssueMapper;

    @Resource
    private QualityWorkOrderEtlReportMapper workOrderEtlReportMapper;

    @Resource
    private InlineQualityReportMapper inlineQualityReportMapper;

    @Resource
    private QualityEtlBatchRunMapper etlBatchRunMapper;

    @Resource
    private QualityWorkOrderSubmissionMapper submissionMapper;

    @Resource
    private QualityWorkOrderRecheckMapper recheckMapper;

    @Resource
    private QualityWorkOrderActionMapper actionMapper;

    @Resource
    private QualityWorkOrderNotifyMapper notifyMapper;

    @Resource
    private cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityExceptionExpiryFailureMapper expiryFailureMapper;

    @Resource
    private QualityWorkOrderNotifyReviewMapper notifyReviewMapper;

    @Resource
    private QualityTaskMapper taskMapper;

    @Resource
    private QualityIssueDataMapper issueDataMapper;

    @Resource
    private QualityAuditLogMapper auditLogMapper;

    @Resource
    private QualityRepairOrderMapper repairOrderMapper;

    @Resource
    private QualityTaskInstanceMapper instanceMapper;

    @Resource
    private QualityRunService qualityRunService;

    @Resource
    private QualityWorkOrderGuard guard;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private NotifyMessageSendApi notifyMessageSendApi;

    @Resource
    private PlatformTransactionManager transactionManager;

    @Resource
    private QualityExceptionExpiryScanService exceptionExpiryScanService;

    @Resource
    private ConfiguredSourceGenerations configuredSourceGenerations = ConfiguredSourceGenerations.empty();

    /**
     * 创建问题工单。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createWorkOrder(QualityWorkOrderSaveReqVO createReqVO) {
        QualityWorkOrderDO order = BeanUtils.toBean(createReqVO, QualityWorkOrderDO.class);
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        order.setOrderStatus(QualityWorkOrderDO.STATUS_PENDING);
        order.setCreatorUserId(userId);
        order.setRequestDeptId(guard.deptId(userId));
        order.setLockVersion(0);
        order.setEverOverdue(0);
        order.setClosedOverdue(0);
        if (order.getOwnerUserId() == null) {
            order.setResponsibilityStatus(QualityWorkOrderDO.RESP_UNASSIGNED);
        } else {
            order.setResponsibilityStatus(QualityWorkOrderDO.RESP_ASSIGNED);
            if (order.getHandlingDeptId() == null) {
                order.setHandlingDeptId(guard.deptId(order.getOwnerUserId()));
            }
        }
        if (order.getTaskId() != null) {
            QualityTaskDO task = taskMapper.selectById(order.getTaskId());
            if (task != null) {
                order.setTaskName(task.getTaskName());
            }
        }
        assertIssuesExist(createReqVO.getIssueIds());
        order.setIssueCount(countIssues(createReqVO.getIssueIds()));
        workOrderMapper.insert(order);
        workOrderMapper.update(null, Wrappers.lambdaUpdate(QualityWorkOrderDO.class)
                .set(QualityWorkOrderDO::getOrderCode, "WO-" + order.getId())
                .set(QualityWorkOrderDO::getRootOrderId, order.getId())
                .eq(QualityWorkOrderDO::getId, order.getId()));
        saveIssueRelations(order.getId(), createReqVO.getIssueIds());
        return order.getId();
    }

    /**
     * 创建内联ETL工作工单。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createInlineEtlWorkOrder(Long actorUserId, Long reportId, String orderName,
            String description, LocalDateTime deadline) {

        // 获取报告行锁之前，先解析执行者元数据；此操作是外部系统 RPC。
        Long requestDeptId = guard.deptId(actorUserId);
        InlineQualityReportDO report = inlineQualityReportMapper.selectByIdForUpdate(reportId);
        if (report == null || !"ETL_INLINE".equals(report.getSourceType())) {
            throw exception(QUALITY_ETL_REPORT_NOT_EXISTS);
        }
        if (workOrderEtlReportMapper.selectByReportId(reportId) != null) {
            throw exception(QUALITY_WORK_ORDER_ETL_REPORT_ALREADY_LINKED);
        }
        long actionableCount;
        try {
            actionableCount = Math.addExact(Objects.requireNonNullElse(report.getRejectCount(), 0L),
                    Objects.requireNonNullElse(report.getWarnCount(), 0L));
        } catch (ArithmeticException overflow) {
            throw exception(QUALITY_ETL_RESULT_INVALID);
        }
        if (actionableCount <= 0) {
            throw exception(QUALITY_ETL_RESULT_INVALID);
        }

        QualityWorkOrderDO order = QualityWorkOrderDO.builder()
                .orderName(orderName)
                .description(description)
                .sourceType("ETL_INLINE")
                .topicRef("ETL_INLINE_REPORT:" + report.getId())
                .issueCount(actionableCount)
                .requestDeptId(requestDeptId)
                .handlingDeptId(requestDeptId)
                .ownerUserId(actorUserId)
                .creatorUserId(actorUserId)
                .deadline(deadline)
                .orderStatus(QualityWorkOrderDO.STATUS_PENDING)
                .responsibilityStatus(QualityWorkOrderDO.RESP_ASSIGNED)
                .lockVersion(0)
                .everOverdue(0)
                .closedOverdue(0)
                .build();
        if (workOrderMapper.insert(order) != 1 || order.getId() == null || order.getId() <= 0) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        if (workOrderMapper.update(null, Wrappers.lambdaUpdate(QualityWorkOrderDO.class)
                .set(QualityWorkOrderDO::getOrderCode, "WO-" + order.getId())
                .set(QualityWorkOrderDO::getRootOrderId, order.getId())
                .eq(QualityWorkOrderDO::getId, order.getId())) != 1) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        QualityWorkOrderEtlReportDO link = QualityWorkOrderEtlReportDO.builder()
                .workOrderId(order.getId()).reportId(report.getId()).build();
        if (workOrderEtlReportMapper.insert(link) != 1) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        return order.getId();
    }

    /**
     * 读取可见内联报告工作工单编号。
     */
    @Override
    public Long getVisibleInlineReportWorkOrderId(Long reportId, Long userId) {
        QualityWorkOrderEtlReportDO link = workOrderEtlReportMapper.selectByReportId(reportId);
        if (link == null) {
            return null;
        }
        QualityWorkOrderDO order = workOrderMapper.selectById(link.getWorkOrderId());
        if (order == null) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        guard.assertVisible(order, userId);
        return order.getId();
    }

    /**
     * 描述ETL工作工单回放。
     */
    @Override
    public QualityEtlWorkOrderReplayDescribeRespDTO describeEtlWorkOrderReplay(
            QualityEtlWorkOrderReplayDescribeReqDTO request) {
        QualityWorkOrderDO order = validateExists(request.getWorkOrderId());
        Long actor = request.getExecutionUserId();
        guard.assertHandle(order, actor);
        if (!QualityWorkOrderDO.STATUS_PENDING_RECHECK.equals(order.getOrderStatus())
                || !Objects.equals(order.getCurrentSubmissionId(), request.getSubmissionId())) {
            throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
        }
        QualityWorkOrderSubmissionDO submission = requireEtlSourceFix(order, request.getSubmissionId(), actor);
        InlineQualityReportDO sourceReport = requireEtlSourceReport(order);
        List<EtlTargetLineage> targetHeads = resolveEtlTargetLineages(order, sourceReport);
        QualityEtlWorkOrderReplayDescribeRespDTO response = new QualityEtlWorkOrderReplayDescribeRespDTO();
        response.setWorkOrderId(order.getId());
        response.setSubmissionId(submission.getId());
        response.setSourceReportId(sourceReport.getId());
        response.setRunId(sourceReport.getRunId());
        response.setQualityNodeId(sourceReport.getNodeId());
        response.setQualityBatchId(sourceReport.getBatchId());
        response.setTotalCount(sourceReport.getTotalCount());
        response.setPassCount(sourceReport.getPassCount());
        response.setWarnCount(sourceReport.getWarnCount());
        response.setRejectCount(sourceReport.getRejectCount());
        response.setIdentityFields(JsonUtils.parseArray(sourceReport.getIdentityFieldsJson(), String.class));
        response.setIdentityHash(sourceReport.getIdentityHash());
        response.setOriginalTargets(targetHeads.stream().map(lineage -> {
            QualityEtlBatchRunDO run = lineage.head();
            var target = new QualityEtlWorkOrderReplayDescribeRespDTO.OriginalTarget();
            target.setTargetBatchId(run.getTargetBatchId());
            target.setRecordId(run.getRecordId());
            target.setRootTargetBatchId(lineage.root().getTargetBatchId());
            target.setRootRecordId(lineage.root().getRecordId());
            target.setSinkNodeId(run.getSinkNodeId());
            target.setQualityTaskId(run.getQualityTaskId());
            return target;
        }).toList());
        return response;
    }

    /** 仅关联已持久化的 Governance 批次运行编号，绝不接受调用方编造的凭据或目标端条件。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long attachEtlWorkOrderRecheck(QualityEtlWorkOrderRecheckAttachReqDTO request) {
        Long actor = request.getExecutionUserId();
        QualityWorkOrderDO authorized = validateExists(request.getWorkOrderId());
        guard.assertHandle(authorized, actor);
        if (!"ETL_INLINE".equals(authorized.getSourceType())) {
            throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
        }
        // 执行者部门通过外部用户服务 RPC 查询，须在获取工单行锁之前解析。
        Long actorDept = guard.deptId(actor);

        QualityWorkOrderDO order = workOrderMapper.selectByIdForUpdate(request.getWorkOrderId());
        if (order == null) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        assertAuthorizationUnchanged(order, authorized);
        QualityWorkOrderSubmissionDO submission = requireEtlSourceFix(order, request.getSubmissionId(), actor);
        List<Long> targetBatchIds = request.getTargetBatchIds().stream().distinct().sorted().toList();
        if (targetBatchIds.size() != request.getTargetBatchIds().size() || targetBatchIds.size() > 32) {
            throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
        }
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("workOrderId", order.getId());
        command.put("submissionId", submission.getId());
        command.put("executionUserId", actor);
        command.put("targetBatchIds", targetBatchIds);
        String requestHash = DigestUtil.sha256Hex(JsonUtils.toJsonString(command));
        QualityWorkOrderRecheckDO replay = recheckMapper.selectByRequest(order.getId(), request.getRequestId());
        if (replay != null) {
            Map<?, ?> plan = parseReplayPlan(replay.getEvidenceJson());
            if (!Objects.equals(replay.getSubmissionId(), submission.getId())
                    || !Objects.equals(plan.get("requestHash"), requestHash)) {
                throw exception(QUALITY_WORK_ORDER_REQUEST_CONFLICT);
            }
            return replay.getId();
        }
        if (!QualityWorkOrderDO.STATUS_PENDING_RECHECK.equals(order.getOrderStatus())
                || !Objects.equals(order.getCurrentSubmissionId(), submission.getId())) {
            throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
        }
        InlineQualityReportDO sourceReport = requireEtlSourceReport(order);
        List<EtlTargetLineage> targetHeads = resolveEtlTargetLineages(order, sourceReport);
        if (targetBatchIds.size() != targetHeads.size()) {
            throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
        }
        Map<Long, EtlTargetLineage> headById = targetHeads.stream().collect(Collectors.toMap(
                lineage -> lineage.head().getTargetBatchId(), item -> item));
        List<QualityEtlBatchRunDO> replacements = targetBatchIds.stream()
                .map(etlBatchRunMapper::selectByTargetBatchId).toList();
        if (replacements.stream().anyMatch(Objects::isNull)) {
            throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
        }
        Long replacementReportId = null;
        List<Map<String, Object>> pairs = new ArrayList<>();
        List<QualityTaskInstanceDO> replacementInstances = new ArrayList<>();
        Set<Long> coveredParents = new HashSet<>();
        for (QualityEtlBatchRunDO replacement : replacements) {
            EtlTargetLineage lineage = headById.get(replacement.getReplayOfBatchId());
            QualityEtlBatchRunDO head = lineage == null ? null : lineage.head();
            QualityEtlBatchRunDO root = lineage == null ? null : lineage.root();
            if (head == null || !Objects.equals(replacement.getReplayOfRecordId(), head.getRecordId())
                    || !Objects.equals(replacement.getWorkOrderSubmissionId(), submission.getId())
                    || !Objects.equals(replacement.getPhysicalBatchValue(), root.getPhysicalBatchValue())
                    || !Objects.equals(replacement.getQualityTaskId(), root.getQualityTaskId())
                    || !Objects.equals(replacement.getIdentityHash(), sourceReport.getIdentityHash())
                    || !Objects.equals(replacement.getRowCount(), sourceReport.getTotalCount())
                    || !Objects.equals(replacement.getAdmittedRowCount(), sourceReport.getTotalCount())
                    || !coveredParents.add(head.getTargetBatchId())) {
                throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
            }
            InlineQualityReportDO replayReport = inlineQualityReportMapper.selectById(replacement.getInlineReportId());
            validateFullPopulationReplay(sourceReport, replayReport, replacement, actor);
            if (replacementReportId != null && !Objects.equals(replacementReportId, replayReport.getId())) {
                throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
            }
            replacementReportId = replayReport.getId();
            QualityTaskInstanceDO instance = instanceMapper.selectById(replacement.getInstanceId());
            if (instance == null || !Objects.equals(instance.getExecutionUserId(), actor)
                    || !Objects.equals(instance.getTaskId(), replacement.getQualityTaskId())
                    || !"ETL_TARGET_BATCH".equals(instance.getRunType())) {
                throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
            }
            replacementInstances.add(instance);
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("rootTargetBatchId", root.getTargetBatchId());
            pair.put("rootRecordId", root.getRecordId());
            pair.put("parentTargetBatchId", head.getTargetBatchId());
            pair.put("parentRecordId", head.getRecordId());
            pair.put("targetBatchRunId", replacement.getId());
            pair.put("targetBatchId", replacement.getTargetBatchId());
            pair.put("instanceId", instance.getId());
            pairs.add(pair);
        }
        if (coveredParents.size() != targetHeads.size() || replacementInstances.isEmpty()) {
            throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
        }
        QualityTaskInstanceDO leadInstance = replacementInstances.getFirst();
        String pendingEvidence = JsonUtils.toJsonString(Map.of("schemaVersion", 1,
                "evidenceType", "ETL_TARGET_REPLAY", "requestHash", requestHash,
                "sourceReportId", sourceReport.getId(), "replayReportId", replacementReportId,
                "submissionId", submission.getId(), "targetRuns", pairs));
        int attemptNo = recheckMapper.nextAttemptNo(submission.getId());
        QualityWorkOrderRecheckDO attempt = QualityWorkOrderRecheckDO.builder()
                .workOrderId(order.getId()).submissionId(submission.getId()).attemptNo(attemptNo)
                .requestId(request.getRequestId()).requestKey("wo:" + order.getId() + ":sub:" + submission.getId()
                        + ":etl-replay:" + request.getRequestId())
                .instanceId(leadInstance.getId()).requestedBy(actor).requestedAt(LocalDateTime.now())
                .status(QualityWorkOrderSubmissionDO.RECHECK_RUNNING).comparableFlag(0)
                .evidenceJson(pendingEvidence).build();
        if (recheckMapper.insert(attempt) != 1 || attempt.getId() == null) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        if (submissionMapper.update(null, Wrappers.lambdaUpdate(QualityWorkOrderSubmissionDO.class)
                .eq(QualityWorkOrderSubmissionDO::getId, submission.getId())
                .eq(QualityWorkOrderSubmissionDO::getStatus, QualityWorkOrderSubmissionDO.STATUS_ACTIVE)
                .set(QualityWorkOrderSubmissionDO::getInstanceId, leadInstance.getId())
                .set(QualityWorkOrderSubmissionDO::getRecheckStatus, QualityWorkOrderSubmissionDO.RECHECK_RUNNING)
                .set(QualityWorkOrderSubmissionDO::getComparableFlag, 0)
                .set(QualityWorkOrderSubmissionDO::getLastError, null)) != 1) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        int changed = workOrderMapper.update(null, Wrappers.lambdaUpdate(QualityWorkOrderDO.class)
                .eq(QualityWorkOrderDO::getId, order.getId())
                .eq(QualityWorkOrderDO::getLockVersion, order.getLockVersion())
                .eq(QualityWorkOrderDO::getOrderStatus, QualityWorkOrderDO.STATUS_PENDING_RECHECK)
                .set(QualityWorkOrderDO::getLockVersion, order.getLockVersion() + 1)
                .set(QualityWorkOrderDO::getOrderStatus, QualityWorkOrderDO.STATUS_RECHECKING)
                .set(QualityWorkOrderDO::getConfirmationPendingType, null)
                .set(QualityWorkOrderDO::getLastError, null));
        if (changed != 1) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        try {
            if (actionMapper.insert(QualityWorkOrderActionDO.builder().workOrderId(order.getId())
                    .requestId(request.getRequestId()).actionType("ETL_RECHECK_ATTACH")
                    .fromStatus(order.getOrderStatus()).toStatus(QualityWorkOrderDO.STATUS_RECHECKING)
                    .reason("已绑定全目标ETL回放与治理批次质检实例")
                    .payloadHash(requestHash).actorUserId(actor).actorDeptId(actorDept)
                    .submissionId(submission.getId()).build()) != 1) {
                throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
            }
        } catch (DuplicateKeyException duplicate) {
            throw exception(QUALITY_WORK_ORDER_REQUEST_CONFLICT);
        }
        return attempt.getId();
    }

    /**
     * 校验质量工单的ETL来源修复，不满足约束时拒绝当前操作。
     */
    private QualityWorkOrderSubmissionDO requireEtlSourceFix(QualityWorkOrderDO order, Long submissionId, Long actor) {
        QualityWorkOrderSubmissionDO submission = requireActiveSubmission(order, submissionId);
        if (!"ETL_INLINE".equals(order.getSourceType())
                || !QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX.equals(submission.getSubmissionType())
                || !Objects.equals(submission.getSubmittedBy(), actor)) {
            throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
        }
        return submission;
    }

    /**
     * 校验质量工单的ETL来源报告，不满足约束时拒绝当前操作。
     */
    private InlineQualityReportDO requireEtlSourceReport(QualityWorkOrderDO order) {
        QualityWorkOrderEtlReportDO link = workOrderEtlReportMapper.selectByWorkOrderId(order.getId());
        InlineQualityReportDO report = link == null ? null : inlineQualityReportMapper.selectById(link.getReportId());
        if (report == null || !"ETL_INLINE".equals(report.getSourceType()) || StrUtil.isBlank(
                report.getIdentityFieldsJson())
                || StrUtil.isBlank(
                report.getIdentityHash()) || report.getTotalCount() == null || report.getTotalCount() <= 0) {
            throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
        }
        return report;
    }

    /**
     * 校验质量工单的ETL原始目标覆盖率，不满足约束时拒绝当前操作。
     */
    private List<QualityEtlBatchRunDO> requireEtlOriginalTargetCoverage(InlineQualityReportDO sourceReport) {
        List<QualityEtlBatchRunDO> runs = etlBatchRunMapper.selectByInlineReportId(sourceReport.getId());
        if (runs.isEmpty() || runs.size() > 32 || runs.stream().anyMatch(run -> run.getTargetBatchId() == null
                || run.getRecordId() == null || run.getQualityTaskId() == null
                || !Objects.equals(run.getRunId(), sourceReport.getRunId())
                || !Objects.equals(run.getIdentityHash(), sourceReport.getIdentityHash())
                || !Objects.equals(JsonUtils.parseArray(run.getIdentityFieldsJson(), String.class),
                JsonUtils.parseArray(sourceReport.getIdentityFieldsJson(), String.class)))) {
            throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
        }
        return runs;
    }

    /** 将每个不可变原始目标解析为其唯一的当前最新重放记录。持久化父引用
     * 是 DataForge replacedBy 链在 Governance 侧的对应关系，最多追踪 64 跳。 */
    private List<EtlTargetLineage> resolveEtlTargetLineages(QualityWorkOrderDO order,
            InlineQualityReportDO sourceReport) {
        List<QualityEtlBatchRunDO> roots = requireEtlOriginalTargetCoverage(sourceReport);
        List<EtlTargetLineage> result = new ArrayList<>(roots.size());
        Set<Long> seenTargets = new HashSet<>();
        for (QualityEtlBatchRunDO root : roots) {
            QualityEtlBatchRunDO head = root;
            Set<Long> path = new HashSet<>();
            if (head.getTargetBatchId() == null || head.getRecordId() == null
                    || !seenTargets.add(head.getTargetBatchId())) {
                throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
            }
            path.add(head.getTargetBatchId());
            for (int depth = 0; depth < 64; depth++) {
                List<QualityEtlBatchRunDO> children = etlBatchRunMapper.selectByReplayOfBatchId(
                        head.getTargetBatchId());
                if (children == null || children.isEmpty()) {
                    break;
                }
                if (children.size() != 1) {
                    throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
                }
                QualityEtlBatchRunDO child = children.getFirst();
                if (child.getTargetBatchId() == null || child.getRecordId() == null
                        || !path.add(child.getTargetBatchId()) || !seenTargets.add(child.getTargetBatchId())
                        || !Objects.equals(child.getReplayOfBatchId(), head.getTargetBatchId())
                        || !Objects.equals(child.getReplayOfRecordId(), head.getRecordId())
                        || !Objects.equals(child.getQualityTaskId(), root.getQualityTaskId())
                        || !Objects.equals(child.getPhysicalBatchValue(), root.getPhysicalBatchValue())
                        || !Objects.equals(child.getIdentityHash(), sourceReport.getIdentityHash())
                        || !Objects.equals(JsonUtils.parseArray(child.getIdentityFieldsJson(), String.class),
                        JsonUtils.parseArray(sourceReport.getIdentityFieldsJson(), String.class))
                        || !Objects.equals(child.getRowCount(), sourceReport.getTotalCount())
                        || !Objects.equals(child.getAdmittedRowCount(), sourceReport.getTotalCount())
                        || child.getWorkOrderSubmissionId() == null) {
                    throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
                }
                QualityWorkOrderSubmissionDO childSubmission = submissionMapper.selectById(
                        child.getWorkOrderSubmissionId());
                if (childSubmission == null || !Objects.equals(childSubmission.getWorkOrderId(), order.getId())
                        || !QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX.equals(childSubmission.getSubmissionType())) {
                    throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
                }
                InlineQualityReportDO childReport = inlineQualityReportMapper.selectById(child.getInlineReportId());
                if (childReport == null || !"ETL_INLINE".equals(childReport.getSourceType())
                        || !Objects.equals(childReport.getIdentityHash(), sourceReport.getIdentityHash())
                        || !Objects.equals(childReport.getTotalCount(), sourceReport.getTotalCount())
                        || !Objects.equals(childReport.getRunId(), child.getRunId())
                        || !Objects.equals(childReport.getNodeId(), child.getQualityNodeId())
                        || !Objects.equals(childReport.getBatchId(), child.getQualityBatchId())) {
                    throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
                }
                head = child;
                if (depth == 63) {
                    List<QualityEtlBatchRunDO> overflow = etlBatchRunMapper.selectByReplayOfBatchId(
                            head.getTargetBatchId());
                    if (overflow != null && !overflow.isEmpty()) {
                        throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
                    }
                }
            }
            result.add(new EtlTargetLineage(root, head));
        }
        return result;
    }

    private record EtlTargetLineage(QualityEtlBatchRunDO root, QualityEtlBatchRunDO head) {

    }

    /**
     * 校验质量工单的全量数据全集回放，不满足约束时拒绝当前操作。
     */
    private void validateFullPopulationReplay(InlineQualityReportDO source, InlineQualityReportDO replay,
            QualityEtlBatchRunDO batchRun, Long actor) {
        long replayClassified = replay == null ? -1L : nullToZero(replay.getPassCount())
                + nullToZero(replay.getWarnCount()) + nullToZero(replay.getRejectCount());
        long admitted = replay == null ? -1L : nullToZero(replay.getPassCount())
                + (Boolean.TRUE.equals(batchRun.getAllowWarnings()) ? nullToZero(replay.getWarnCount()) : 0L);
        if (replay == null || !"ETL_INLINE".equals(replay.getSourceType())
                || !Objects.equals(replay.getRunId(), batchRun.getRunId())
                || !Objects.equals(replay.getNodeId(), batchRun.getQualityNodeId())
                || !Objects.equals(replay.getBatchId(), batchRun.getQualityBatchId())
                || !Objects.equals(replay.getExecutionUserId(), actor)
                || !Objects.equals(replay.getTotalCount(), source.getTotalCount())
                || replay.getRejectCount() == null || replay.getRejectCount() != 0
                || !Objects.equals(replayClassified, replay.getTotalCount())
                || !Objects.equals(admitted, source.getTotalCount())
                || !Objects.equals(batchRun.getRowCount(), source.getTotalCount())
                || !Objects.equals(batchRun.getAdmittedRowCount(), source.getTotalCount())
                || !Objects.equals(replay.getIdentityHash(), source.getIdentityHash())
                || !Objects.equals(replay.getIdentityHash(), batchRun.getIdentityHash())
                || !Objects.equals(JsonUtils.parseArray(replay.getIdentityFieldsJson(), String.class),
                JsonUtils.parseArray(source.getIdentityFieldsJson(), String.class))
                || !Objects.equals(JsonUtils.parseArray(replay.getIdentityFieldsJson(), String.class),
                JsonUtils.parseArray(batchRun.getIdentityFieldsJson(), String.class))) {
            throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
        }
    }

    /**
     * 将空数值转换为零。
     */
    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 解析质量工单的回放计划。
     */
    @SuppressWarnings("unchecked")
    private Map<?, ?> parseReplayPlan(String json) {
        if (StrUtil.isBlank(json)) {
            return Map.of();
        }
        Map<String, Object> parsed = JsonUtils.parseObject(json, Map.class);
        return parsed == null ? Map.of() : parsed;
    }

    /**
     * 更新问题工单。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateWorkOrder(QualityWorkOrderSaveReqVO updateReqVO) {
        QualityWorkOrderDO authorized = validateExists(updateReqVO.getId());
        // 权限 RPC、问题和任务读取在工单行锁前完成；锁内只校验快照并写本域数据。
        guard.assertHandle(authorized, guard.requireUserId());
        if ("ETL_INLINE".equals(authorized.getSourceType())) {
            throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
        }
        Integer expectedVersion = updateReqVO.getExpectedVersion();
        if (expectedVersion == null || expectedVersion < 0 || expectedVersion == Integer.MAX_VALUE) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        assertIssuesExist(updateReqVO.getIssueIds());
        QualityWorkOrderDO update = new QualityWorkOrderDO();
        update.setId(updateReqVO.getId());
        update.setOrderName(updateReqVO.getOrderName());
        update.setDescription(updateReqVO.getDescription());
        update.setDeadline(updateReqVO.getDeadline());
        update.setProblemType(updateReqVO.getProblemType());
        update.setSeverity(updateReqVO.getSeverity());
        if (updateReqVO.getTaskId() != null) {
            QualityTaskDO task = taskMapper.selectById(updateReqVO.getTaskId());
            if (task != null) {
                update.setTaskId(updateReqVO.getTaskId());
                update.setTaskName(task.getTaskName());
            }
        }
        update.setIssueCount(countIssues(updateReqVO.getIssueIds()));
        QualityWorkOrderDO order = workOrderMapper.selectByIdForUpdate(updateReqVO.getId());
        if (order == null) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        assertAuthorizationUnchanged(order, authorized);
        if ("ETL_INLINE".equals(order.getSourceType())) {
            throw exception(QUALITY_WORK_ORDER_HISTORY_READONLY);
        }
        // 既防请求执行期间竞争，也防长期打开的客户端在另一人已保存后顺序覆盖。
        // 不得用本请求重新读取的 authorized 版本代替客户端打开时的版本。
        if (!Objects.equals(order.getLockVersion(), authorized.getLockVersion())
                || !Objects.equals(order.getLockVersion(), expectedVersion)) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        if ((!QualityWorkOrderDO.STATUS_PENDING.equals(order.getOrderStatus())
                && !QualityWorkOrderDO.STATUS_IN_PROGRESS.equals(order.getOrderStatus()))
                || order.getCurrentSubmissionId() != null) {
            throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
        }
        update.setLockVersion(order.getLockVersion() + 1);
        int written = workOrderMapper.update(update, Wrappers.lambdaUpdate(QualityWorkOrderDO.class)
                .eq(QualityWorkOrderDO::getId, order.getId())
                .eq(QualityWorkOrderDO::getLockVersion, order.getLockVersion())
                .eq(QualityWorkOrderDO::getOrderStatus, order.getOrderStatus())
                .isNull(QualityWorkOrderDO::getCurrentSubmissionId));
        if (written != 1) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        workOrderIssueMapper.deleteByWorkOrderId(updateReqVO.getId());
        saveIssueRelations(updateReqVO.getId(), updateReqVO.getIssueIds());
    }

    /**
     * 删除问题工单。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteWorkOrder(Long id) {
        Long userId = guard.requireUserId();
        if (!guard.has(userId, QualityWorkOrderGuard.PERM_DELETE)) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        QualityWorkOrderDO authorized = validateExists(id);
        // 删除权限与处理权限独立；采用已有可见对象范围，不禁止指派人删除无历史的未分派单。
        // 权限/部门 RPC 在行锁前完成，提交和确认共用下面的工单锁。
        guard.assertVisible(authorized, userId);
        QualityWorkOrderDO order = workOrderMapper.selectByIdForUpdate(id);
        if (order == null) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        assertAuthorizationUnchanged(order, authorized);
        if ("ETL_INLINE".equals(order.getSourceType())) {
            throw exception(QUALITY_WORK_ORDER_HISTORY_READONLY);
        }
        if (!Objects.equals(order.getLockVersion(), authorized.getLockVersion())) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        if (actionMapper.countByWorkOrderId(id) > 0
                || order.getCurrentSubmissionId() != null
                || !submissionMapper.selectListByWorkOrderId(id).isEmpty()
                || QualityWorkOrderDO.STATUS_PROCESSED.equals(order.getOrderStatus())) {
            throw exception(QUALITY_WORK_ORDER_HISTORY_READONLY);
        }
        int deleted = workOrderMapper.delete(new LambdaQueryWrapperX<QualityWorkOrderDO>()
                .eq(QualityWorkOrderDO::getId, id)
                .eq(QualityWorkOrderDO::getLockVersion, order.getLockVersion())
                .eq(QualityWorkOrderDO::getOrderStatus, order.getOrderStatus())
                .isNull(QualityWorkOrderDO::getCurrentSubmissionId));
        if (deleted != 1) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        workOrderIssueMapper.deleteByWorkOrderId(id);
        // Mapper 的批量删除没有返回行数，必须复验有效关联为零；静默跳过也整笔回滚。
        if (workOrderIssueMapper.selectCount(new LambdaQueryWrapperX<QualityWorkOrderIssueDO>()
                .eq(QualityWorkOrderIssueDO::getWorkOrderId, id)) != 0) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
    }

    /**
     * 获得问题工单。
     */
    @Override
    public QualityWorkOrderRespVO getWorkOrder(Long id) {
        QualityWorkOrderDO order = validateExists(id);
        guard.assertVisible(order, guard.requireUserId());
        return toResp(order, true);
    }

    /**
     * 获得问题工单分页。
     */
    @Override
    public PageResult<QualityWorkOrderRespVO> getWorkOrderPage(QualityWorkOrderPageReqVO pageReqVO) {
        var scope = new LambdaQueryWrapperX<QualityWorkOrderDO>();
        guard.applyVisibleScope(scope, guard.requireUserId());
        PageResult<QualityWorkOrderDO> pageResult = workOrderMapper.selectVisiblePage(pageReqVO, scope);
        PageResult<QualityWorkOrderRespVO> result = new PageResult<>(
                pageResult.getList().stream().map(order -> toResp(order, false)).toList(), pageResult.getTotal());
        fillUserNames(result.getList());
        return result;
    }

    /**
     * 获得我的待办工单列表。
     */
    @Override
    public List<QualityWorkOrderRespVO> getMyTodoList() {
        Long userId = guard.requireUserId();
        return convertList(workOrderMapper.selectTodoListByOwner(userId), order -> toResp(order, false));
    }

    /**
     * 将质量工单分派给指定处理人，并记录分派信息。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityWorkOrderRespVO assign(QualityWorkOrderCommandReqVO reqVO) {
        return mutate(reqVO, "ASSIGN", (order, userId) -> {
            guard.assertAssignable(order, userId);
            if (!QualityWorkOrderDO.STATUS_PENDING.equals(order.getOrderStatus())
                    || !guard.isUnassigned(order)) {
                throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
            }
            if (reqVO.getOwnerUserId() == null && reqVO.getHandlingDeptId() == null) {
                throw exception(QUALITY_WORK_ORDER_ASSIGN_TARGET_REQUIRED);
            }
            order.setOwnerUserId(reqVO.getOwnerUserId());
            order.setHandlingDeptId(reqVO.getHandlingDeptId() != null
                    ? reqVO.getHandlingDeptId() : guard.deptId(reqVO.getOwnerUserId()));
            order.setResponsibilityStatus(QualityWorkOrderDO.RESP_ASSIGNED);
            order.setOrderStatus(QualityWorkOrderDO.STATUS_PENDING);
            enqueueNotify(order, "ASSIGN", order.getOwnerUserId());
        });
    }

    /**
     * 领取待处理的质量工单任务或记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityWorkOrderRespVO claim(QualityWorkOrderCommandReqVO reqVO) {
        return mutate(reqVO, "CLAIM", (order, userId) -> {
            guard.assertHandle(order, userId);
            if (!QualityWorkOrderDO.STATUS_PENDING.equals(order.getOrderStatus())
                    || !QualityWorkOrderDO.RESP_ASSIGNED.equals(order.getResponsibilityStatus())) {
                throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
            }
            if (order.getOwnerUserId() == null) {
                order.setOwnerUserId(userId);
            }
            order.setOrderStatus(QualityWorkOrderDO.STATUS_IN_PROGRESS);
        });
    }

    /**
     * 将质量工单转交给指定处理人，并保留操作轨迹。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityWorkOrderRespVO transfer(QualityWorkOrderCommandReqVO reqVO) {
        return mutate(reqVO, "TRANSFER", (order, userId) -> {
            guard.assertHandle(order, userId);
            if (!(QualityWorkOrderDO.STATUS_PENDING.equals(order.getOrderStatus())
                    || QualityWorkOrderDO.STATUS_IN_PROGRESS.equals(order.getOrderStatus()))) {
                throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
            }
            if (reqVO.getOwnerUserId() == null) {
                throw exception(QUALITY_WORK_ORDER_OWNER_REQUIRED);
            }
            order.setOwnerUserId(reqVO.getOwnerUserId());
            if (reqVO.getHandlingDeptId() != null) {
                order.setHandlingDeptId(reqVO.getHandlingDeptId());
            }
            order.setOrderStatus(QualityWorkOrderDO.STATUS_PENDING);
            order.setResponsibilityStatus(QualityWorkOrderDO.RESP_ASSIGNED);
            enqueueNotify(order, "TRANSFER", order.getOwnerUserId());
        });
    }

    /**
     * 提交处理结果。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityWorkOrderRespVO submitResolution(QualityWorkOrderCommandReqVO reqVO) {
        return mutate(reqVO, "SUBMIT", (order, userId) -> {
            guard.assertHandle(order, userId);
            if (!QualityWorkOrderDO.STATUS_IN_PROGRESS.equals(order.getOrderStatus())) {
                throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
            }
            String type = StrUtil.blankToDefault(reqVO.getSubmissionType(),
                    QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX);
            if (!Set.of(QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX,
                    QualityWorkOrderSubmissionDO.TYPE_PLATFORM_REPAIR,
                    QualityWorkOrderSubmissionDO.TYPE_EXPLAIN,
                    QualityWorkOrderSubmissionDO.TYPE_EXCEPTION).contains(type)) {
                throw exception(QUALITY_WORK_ORDER_SUBMISSION_INVALID);
            }
            boolean etlInline = "ETL_INLINE".equals(order.getSourceType());
            if (etlInline && !QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX.equals(type)) {
                throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
            }
            assertIssueScope(order.getId(), reqVO.getIssueIds());
            if (StrUtil.isBlank(StrUtil.blankToDefault(reqVO.getContent(), reqVO.getReason()))) {
                throw exception(QUALITY_WORK_ORDER_SUBMISSION_INVALID);
            }
            if (QualityWorkOrderSubmissionDO.TYPE_PLATFORM_REPAIR.equals(type)) {
                requireRepairDone(order.getId(), reqVO.getRepairOrderId(), reqVO.getIssueIds());
            }
            if (QualityWorkOrderSubmissionDO.TYPE_EXCEPTION.equals(type)) {
                requireValidException(reqVO.getExceptionScope(), reqVO.getExceptionExpireAt());
            }
            if (!etlInline && order.getTaskId() == null
                    && (QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX.equals(type)
                    || QualityWorkOrderSubmissionDO.TYPE_PLATFORM_REPAIR.equals(type))) {
                throw exception(QUALITY_WORK_ORDER_NO_BASELINE);
            }
            supersedeActive(order.getId());
            int revision = submissionMapper.selectListByWorkOrderId(order.getId()).size() + 1;
            String payload = canonicalSubmit(reqVO, type);
            QualityWorkOrderSubmissionDO submission = QualityWorkOrderSubmissionDO.builder()
                    .workOrderId(order.getId())
                    .revision(revision)
                    .submissionType(type)
                    .content(StrUtil.blankToDefault(reqVO.getContent(), reqVO.getReason()))
                    .evidenceRef(reqVO.getEvidenceRef())
                    .repairOrderId(reqVO.getRepairOrderId())
                    .issueIds(JsonUtils.toJsonString(reqVO.getIssueIds()))
                    .payloadHash(DigestUtil.sha256Hex(payload))
                    .submittedBy(userId)
                    .submittedAt(LocalDateTime.now())
                    .status(QualityWorkOrderSubmissionDO.STATUS_ACTIVE)
                    .exceptionScope(reqVO.getExceptionScope())
                    .exceptionExpireAt(reqVO.getExceptionExpireAt())
                    .build();
            if (QualityWorkOrderSubmissionDO.TYPE_EXPLAIN.equals(type)
                    || QualityWorkOrderSubmissionDO.TYPE_EXCEPTION.equals(type)) {
                order.setOrderStatus(QualityWorkOrderDO.STATUS_PENDING_CONFIRMATION);
                order.setConfirmationPendingType(QualityWorkOrderSubmissionDO.TYPE_EXCEPTION.equals(type)
                        ? QualityWorkOrderDO.CONFIRM_EXCEPTION : QualityWorkOrderDO.CONFIRM_EXPLAIN);
            } else {
                submission.setRequestKey("wo:" + order.getId() + ":sub:" + revision);
                submission.setRecheckStatus(QualityWorkOrderSubmissionDO.RECHECK_QUEUED);
                order.setOrderStatus(QualityWorkOrderDO.STATUS_PENDING_RECHECK);
                order.setConfirmationPendingType(null);
            }
            submissionMapper.insert(submission);
            order.setCurrentSubmissionId(submission.getId());
            order.setLastError(null);
        });
    }

    /**
     * 受理质量工单复检请求并关联本次提交记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityWorkOrderRespVO recheck(QualityWorkOrderCommandReqVO reqVO) {
        QualityWorkOrderRespVO resp = mutate(reqVO, "RECHECK", (order, userId) -> {
            if ("ETL_INLINE".equals(order.getSourceType())) {
                throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
            }
            guard.assertHandle(order, userId);
            // 同一请求由命令回执返回，另一请求不能在本轮运行时重复创建检查。
            if (!QualityWorkOrderDO.STATUS_PENDING_RECHECK.equals(order.getOrderStatus())) {
                throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
            }
            QualityWorkOrderSubmissionDO submission = requireActiveSubmission(order, reqVO.getSubmissionId());
            if (order.getTaskId() == null) {
                throw exception(QUALITY_WORK_ORDER_NO_BASELINE);
            }
            int attemptNo = recheckMapper.nextAttemptNo(submission.getId());
            String runKey = "wo:" + order.getId() + ":sub:" + submission.getId() + ":attempt:" + attemptNo;
            Long instanceId = qualityRunService.submitIdempotent(order.getTaskId(), runKey,
                    userId, "WORK_ORDER_RECHECK");
            if (recheckMapper.insert(QualityWorkOrderRecheckDO.builder()
                    .workOrderId(order.getId()).submissionId(submission.getId()).attemptNo(attemptNo)
                    .requestId(reqVO.getRequestId()).requestKey(runKey).instanceId(instanceId)
                    .requestedBy(userId).requestedAt(LocalDateTime.now())
                    .status(QualityWorkOrderSubmissionDO.RECHECK_RUNNING).comparableFlag(0).build()) != 1) {
                throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
            }
            submission.setInstanceId(instanceId);
            submission.setRecheckStatus(QualityWorkOrderSubmissionDO.RECHECK_RUNNING);
            if (submissionMapper.update(null, Wrappers.lambdaUpdate(QualityWorkOrderSubmissionDO.class)
                    .eq(QualityWorkOrderSubmissionDO::getId, submission.getId())
                    .set(QualityWorkOrderSubmissionDO::getInstanceId, instanceId)
                    .set(QualityWorkOrderSubmissionDO::getRecheckStatus, submission.getRecheckStatus())
                    .set(QualityWorkOrderSubmissionDO::getComparableFlag, 0)
                    .set(QualityWorkOrderSubmissionDO::getLastError, null)) != 1) {
                throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
            }
            order.setOrderStatus(QualityWorkOrderDO.STATUS_RECHECKING);
            order.setConfirmationPendingType(null);
            order.setLastError(null);
        });
        // 工单详情可刷新为当前状态，但旧命令的运行回执必须仍指向它自己的轮次。
        resp.setRecheckAttempt(BeanUtils.toBean(recheckMapper.selectByRequest(reqVO.getId(), reqVO.getRequestId()),
                QualityWorkOrderRespVO.QualityWorkOrderRecheckRespVO.class));
        return resp;
    }

    /**
     * 校验质量工单处理证据后记录确认结论。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityWorkOrderRespVO confirm(QualityWorkOrderCommandReqVO reqVO) {
        return mutate(reqVO, "CONFIRM", (order, userId) -> {
            if ("ETL_INLINE".equals(order.getSourceType())
                    && (!QualityWorkOrderDO.STATUS_PENDING_CONFIRMATION.equals(order.getOrderStatus())
                    || !QualityWorkOrderDO.CONFIRM_RECHECK.equals(order.getConfirmationPendingType()))) {
                throw exception(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE);
            }
            guard.assertConfirm(order, userId);
            if (!QualityWorkOrderDO.STATUS_PENDING_CONFIRMATION.equals(order.getOrderStatus())) {
                throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
            }
            QualityWorkOrderSubmissionDO submission = requireActiveSubmission(order, reqVO.getSubmissionId());
            if (Objects.equals(userId, submission.getSubmittedBy())) {
                throw exception(QUALITY_WORK_ORDER_SELF_CONFIRM);
            }
            if (order.getConfirmerUserId() != null && !Objects.equals(userId, order.getConfirmerUserId())) {
                throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
            }
            String action = StrUtil.blankToDefault(reqVO.getConfirmAction(), "VERIFY_PASS");
            if ("RETURN".equals(action)) {
                if (StrUtil.isBlank(reqVO.getReason())) {
                    throw exception(QUALITY_WORK_ORDER_CONFIRM_ACTION_INVALID);
                }
                order.setOrderStatus(QualityWorkOrderDO.STATUS_IN_PROGRESS);
                order.setConfirmationPendingType(null);
                enqueueNotify(order, "RETURN", order.getOwnerUserId());
                return;
            }
            if ("VERIFY_PASS".equals(action)) {
                QualityWorkOrderRecheckDO attempt = recheckMapper.selectByInstance(submission.getInstanceId());
                boolean etlInline = "ETL_INLINE".equals(order.getSourceType());
                if (!QualityWorkOrderDO.CONFIRM_RECHECK.equals(order.getConfirmationPendingType())
                        || !QualityWorkOrderSubmissionDO.RECHECK_PASS.equals(submission.getRecheckStatus())
                        || !Integer.valueOf(1).equals(submission.getComparableFlag())
                        || (etlInline ? !validEtlAttempt(attempt, order, submission)
                        : !QualityRecheckEvidence.qualifiesCurrentIssues(attempt,
                        order, submission, configuredSourceGenerations,
                        currentWorkOrderIssues(order.getId())))) {
                    throw exception(QUALITY_WORK_ORDER_RECHECK_NOT_PASS);
                }
                QualityTaskInstanceDO instance = instanceMapper.selectById(attempt.getInstanceId());
                if (instance == null) {
                    throw exception(QUALITY_WORK_ORDER_RECHECK_NOT_PASS);
                }
                // 只重验平台内的冻结定义与审计，不重新执行外部数据库查询或覆写原决定。
                RecheckJudge current = "ETL_INLINE".equals(order.getSourceType())
                        ? judgeEtlReplay(order, instance, submission, attempt)
                        : judgeRecheck(order, instance, submission);
                boolean evidenceMatches = "ETL_INLINE".equals(order.getSourceType())
                        ? matchesEtlCurrentChecks(attempt, current)
                        : QualityRecheckEvidence.matchesCurrentChecks(attempt, current.recheckStatus(),
                        current.comparable(), current.checks());
                if (!evidenceMatches) {
                    throw exception(QUALITY_WORK_ORDER_RECHECK_NOT_PASS);
                }
                close(order, userId, QualityWorkOrderDO.CLOSE_VERIFIED_PASS, reqVO.getReason());
                return;
            }
            if ("ACCEPT_EXPLAIN".equals(action)) {
                if ("ETL_INLINE".equals(order.getSourceType())) {
                    throw exception(QUALITY_WORK_ORDER_CONFIRM_ACTION_INVALID);
                }
                if (!QualityWorkOrderDO.CONFIRM_EXPLAIN.equals(order.getConfirmationPendingType())
                        || !QualityWorkOrderSubmissionDO.TYPE_EXPLAIN.equals(submission.getSubmissionType())
                        || StrUtil.isBlank(submission.getContent())) {
                    throw exception(QUALITY_WORK_ORDER_CONFIRM_ACTION_INVALID);
                }
                close(order, userId, QualityWorkOrderDO.CLOSE_EXPLAINED, reqVO.getReason());
                return;
            }
            if ("ACCEPT_EXCEPTION".equals(action)) {
                if ("ETL_INLINE".equals(order.getSourceType())) {
                    throw exception(QUALITY_WORK_ORDER_CONFIRM_ACTION_INVALID);
                }
                if (!QualityWorkOrderDO.CONFIRM_EXCEPTION.equals(order.getConfirmationPendingType())
                        || !QualityWorkOrderSubmissionDO.TYPE_EXCEPTION.equals(submission.getSubmissionType())
                        || StrUtil.isBlank(submission.getContent())) {
                    throw exception(QUALITY_WORK_ORDER_CONFIRM_ACTION_INVALID);
                }
                requireValidException(submission.getExceptionScope(), submission.getExceptionExpireAt());
                close(order, userId, QualityWorkOrderDO.CLOSE_EXCEPTION, reqVO.getReason());
                return;
            }
            throw exception(QUALITY_WORK_ORDER_CONFIRM_ACTION_INVALID);
        });
    }

    /**
     * 读取当前工单提交所绑定的问题集合。
     */
    private List<QualityIssueDataDO> currentWorkOrderIssues(Long workOrderId) {
        var relations = workOrderIssueMapper.selectListByWorkOrderId(workOrderId);
        if (relations == null) {
            return null;
        }
        // 不能使用会过滤 null 的 convertList：关联仍在但问题缺失时必须拒绝确认，
        // 不能把它从待核验范围静默删除后复用旧 PASS。
        return relations.stream().map(relation -> relation == null ? null
                : issueDataMapper.selectById(relation.getIssueId())).toList();
    }

    /**
     * 撤销质量工单当前提交或处理状态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityWorkOrderRespVO withdraw(QualityWorkOrderCommandReqVO reqVO) {
        return mutate(reqVO, "WITHDRAW", (order, userId) -> {
            guard.assertHandle(order, userId);
            if (!(QualityWorkOrderDO.STATUS_PENDING_RECHECK.equals(order.getOrderStatus())
                    || QualityWorkOrderDO.STATUS_RECHECKING.equals(order.getOrderStatus())
                    || QualityWorkOrderDO.STATUS_PENDING_CONFIRMATION.equals(order.getOrderStatus()))) {
                throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
            }
            QualityWorkOrderSubmissionDO submission = requireActiveSubmission(order, reqVO.getSubmissionId());
            submission.setStatus(QualityWorkOrderSubmissionDO.STATUS_WITHDRAWN);
            submissionMapper.updateById(submission);
            order.setCurrentSubmissionId(null);
            order.setConfirmationPendingType(null);
            order.setOrderStatus(QualityWorkOrderDO.STATUS_IN_PROGRESS);
        });
    }

    /**
     * 延长截止时间。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityWorkOrderRespVO extendDeadline(QualityWorkOrderCommandReqVO reqVO) {
        return mutate(reqVO, "EXTEND", (order, userId) -> {
            guard.assertHandle(order, userId);
            if (QualityWorkOrderDO.STATUS_PROCESSED.equals(order.getOrderStatus())) {
                throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
            }
            if (reqVO.getNewDeadline() == null) {
                throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
            }
            if (order.getDeadline() != null && LocalDateTime.now().isAfter(order.getDeadline())) {
                order.setEverOverdue(1);
            }
            order.setDeadlineBasis(reqVO.getDeadlineBasis());
            order.setDeadline(reqVO.getNewDeadline());
        });
    }

    /**
     * 重新打开已结束的质量工单。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityWorkOrderRespVO reopen(QualityWorkOrderCommandReqVO reqVO) {
        return mutate(reqVO, "REOPEN", (order, userId) -> {
            if (!guard.has(userId, QualityWorkOrderGuard.PERM_REOPEN)) {
                throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
            }
            if (!QualityWorkOrderDO.STATUS_PROCESSED.equals(order.getOrderStatus())) {
                throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
            }
            order.setOrderStatus(QualityWorkOrderDO.STATUS_IN_PROGRESS);
            order.setCurrentSubmissionId(null);
            order.setConfirmationPendingType(null);
        });
    }

    /**
     * 记录质量工单协调信息并更新相关处理安排。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityWorkOrderRespVO coordinate(QualityWorkOrderCommandReqVO reqVO) {
        return mutate(reqVO, "COORDINATE", (order, userId) -> {
            guard.assertVisible(order, userId);
            if (!guard.has(userId, QualityWorkOrderGuard.PERM_COORDINATE)
                    && !guard.has(userId, QualityWorkOrderGuard.PERM_ASSIGN)) {
                throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
            }
            enqueueNotify(order, "COORDINATE", order.getOwnerUserId());
        });
    }

    /**
     * 读取质量工单历史。
     */
    @Override
    public List<QualityWorkOrderRespVO.QualityWorkOrderActionRespVO> getHistory(Long id) {
        QualityWorkOrderDO order = validateExists(id);
        guard.assertVisible(order, guard.requireUserId());
        return convertList(actionMapper.selectListByWorkOrderId(id), this::toAction);
    }

    /**
     * 读取通知分页。
     */
    @Override
    public PageResult<QualityWorkOrderNotifyRespVO> getNotifyPage(QualityWorkOrderNotifyPageReqVO reqVO) {
        QualityWorkOrderDO order = validateExists(reqVO.getWorkOrderId());
        guard.assertVisible(order, guard.requireUserId());
        PageResult<QualityWorkOrderNotifyDO> page = notifyMapper.selectPageByWorkOrder(reqVO);
        return new PageResult<>(page.getList().stream().map(n -> toNotifyResp(n, false)).toList(), page.getTotal());
    }

    /**
     * 读取通知详情。
     */
    @Override
    public QualityWorkOrderNotifyRespVO getNotifyDetail(Long id) {
        QualityWorkOrderNotifyDO notify = notifyMapper.selectById(id);
        if (notify == null) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        QualityWorkOrderDO order = validateExists(notify.getWorkOrderId());
        guard.assertVisible(order, guard.requireUserId());
        return toNotifyResp(notify, true);
    }

    /**
     * 人工核验结果未知的通知。
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public QualityWorkOrderNotifyRespVO reviewNotify(QualityWorkOrderNotifyReviewReqVO reqVO) {
        Long actor = guard.requireUserId();

        if ("DELIVERED".equals(reqVO.getOutcome()) && reqVO.getOperatorMessageId() == null) {
            throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
        }
        if ("NOT_DELIVERED".equals(reqVO.getOutcome()) && reqVO.getOperatorMessageId() != null) {
            throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
        }
        String actionType = "DELIVERED".equals(reqVO.getOutcome())
                ? QualityWorkOrderNotifyReviewDO.ACTION_MARK_DELIVERED
                : QualityWorkOrderNotifyReviewDO.ACTION_MARK_NOT_DELIVERED;
        String payloadHash = DigestUtil.sha256Hex(JsonUtils.toJsonString(Arrays.asList(
                actionType, reqVO.getId(), reqVO.getExpectedResolutionVersion(), reqVO.getOutcome(),
                reqVO.getReason(), reqVO.getOperatorMessageId(), actor)));
        QualityWorkOrderNotifyReviewDO prior = notifyReviewMapper.selectByRequestId(reqVO.getRequestId());
        if (prior != null) {
            assertSameReviewRequest(prior, actor, payloadHash, actionType);
            QualityWorkOrderNotifyDO replayNotify = notifyMapper.selectById(prior.getNotifyId());
            if (replayNotify == null) {
                throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
            }
            guard.assertHandle(validateExists(replayNotify.getWorkOrderId()), actor);
            return toNotifyResp(replayNotify, true);
        }
        QualityWorkOrderNotifyDO preflightNotify = notifyMapper.selectById(reqVO.getId());
        if (preflightNotify == null) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        QualityWorkOrderDO preflightOrder = validateExists(preflightNotify.getWorkOrderId());
        guard.assertHandle(preflightOrder, actor); // Permission/AdminUser RPC 在数据库事务之外调用。
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setTimeout(10);
        return tx.execute(status -> reviewNotifyLocked(reqVO, actor, actionType, payloadHash, preflightOrder));
    }

    /**
     * 复核通知已锁定。
     */
    private QualityWorkOrderNotifyRespVO reviewNotifyLocked(QualityWorkOrderNotifyReviewReqVO reqVO, Long actor,
            String actionType, String payloadHash, QualityWorkOrderDO preflightOrder) {
        QualityWorkOrderNotifyReviewDO prior;
        QualityWorkOrderDO lockedOrder = workOrderMapper.selectByIdForUpdate(preflightOrder.getId());
        if (lockedOrder == null || !sameNotifyAuthorizationState(preflightOrder, lockedOrder)) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        QualityWorkOrderNotifyDO notify = notifyMapper.selectForUpdate(reqVO.getId());
        if (notify == null) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        prior = notifyReviewMapper.selectByRequestId(reqVO.getRequestId());
        if (prior != null) {
            assertSameReviewRequest(prior, actor, payloadHash, actionType);
            return toNotifyResp(notifyMapper.selectById(prior.getNotifyId()), true);
        }
        if (!Objects.equals(notify.getWorkOrderId(), lockedOrder.getId())) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        int currentVersion = notify.getResolutionVersion() == null ? 0 : notify.getResolutionVersion();
        if (!isManuallyReviewable(notify.getSendStatus())
                || !QualityWorkOrderNotifyDO.RESOLUTION_UNRESOLVED.equals(
                notify.getResolutionStatus() == null ? QualityWorkOrderNotifyDO.RESOLUTION_UNRESOLVED : notify.getResolutionStatus())
                || currentVersion != reqVO.getExpectedResolutionVersion()) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        int nextVersion = currentVersion + 1;
        String resolution = "DELIVERED".equals(reqVO.getOutcome())
                ? QualityWorkOrderNotifyDO.RESOLUTION_MANUAL_DELIVERED
                : QualityWorkOrderNotifyDO.RESOLUTION_MANUAL_NOT_DELIVERED;
        int updated = notifyMapper.resolveUnknown(notify.getId(), currentVersion, nextVersion,
                resolution, reqVO.getReason(), reqVO.getOperatorMessageId(), actor);
        if (updated != 1) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        QualityWorkOrderNotifyReviewDO review = QualityWorkOrderNotifyReviewDO.builder()
                .workOrderId(notify.getWorkOrderId()).notifyId(notify.getId()).requestId(reqVO.getRequestId())
                .actionType(actionType).payloadHash(payloadHash)
                .expectedResolutionStatus(QualityWorkOrderNotifyDO.RESOLUTION_UNRESOLVED)
                .expectedResolutionVersion(currentVersion).outcome(reqVO.getOutcome()).reason(reqVO.getReason())
                .operatorMessageId(reqVO.getOperatorMessageId()).actorUserId(actor)
                .resultingResolutionVersion(nextVersion).build();

        if (notifyReviewMapper.insert(review) != 1 || review.getId() == null) {
            throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
        }
        return toNotifyResp(notifyMapper.selectById(notify.getId()), true);
    }

    /**
     * 基于人工未送达结论显式授权重试。
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public QualityWorkOrderNotifyRespVO authorizeNotifyRetry(QualityWorkOrderNotifyRetryReqVO reqVO) {
        Long actor = guard.requireUserId();

        String actionType = QualityWorkOrderNotifyReviewDO.ACTION_AUTHORIZE_RETRY;
        String payloadHash = DigestUtil.sha256Hex(JsonUtils.toJsonString(Arrays.asList(actionType, reqVO.getId(),
                reqVO.getDecisionId(), reqVO.getExpectedResolutionVersion(), reqVO.getReason(), actor)));
        QualityWorkOrderNotifyReviewDO prior = notifyReviewMapper.selectByRequestId(reqVO.getRequestId());
        if (prior != null) {
            assertSameReviewRequest(prior, actor, payloadHash, actionType);
            QualityWorkOrderNotifyDO replayNotify = notifyMapper.selectById(prior.getNotifyId());
            if (replayNotify == null) {
                throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
            }
            guard.assertHandle(validateExists(replayNotify.getWorkOrderId()), actor);
            return toNotifyResp(replayNotify, true);
        }
        QualityWorkOrderNotifyDO preflightNotify = notifyMapper.selectById(reqVO.getId());
        if (preflightNotify == null) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        QualityWorkOrderDO preflightOrder = validateExists(preflightNotify.getWorkOrderId());
        guard.assertHandle(preflightOrder, actor); // Permission/AdminUser RPC 在数据库事务之外调用。
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setTimeout(10);
        return tx.execute(status -> authorizeNotifyRetryLocked(reqVO, actor, actionType, payloadHash, preflightOrder));
    }

    /**
     * 校验授权通知重试已锁定。
     */
    private QualityWorkOrderNotifyRespVO authorizeNotifyRetryLocked(QualityWorkOrderNotifyRetryReqVO reqVO, Long actor,
            String actionType, String payloadHash, QualityWorkOrderDO preflightOrder) {
        QualityWorkOrderNotifyReviewDO prior;
        QualityWorkOrderDO lockedOrder = workOrderMapper.selectByIdForUpdate(preflightOrder.getId());
        if (lockedOrder == null || !sameNotifyAuthorizationState(preflightOrder, lockedOrder)) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        QualityWorkOrderNotifyDO notify = notifyMapper.selectForUpdate(reqVO.getId());
        if (notify == null) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        prior = notifyReviewMapper.selectByRequestId(reqVO.getRequestId());
        if (prior != null) {
            assertSameReviewRequest(prior, actor, payloadHash, actionType);
            return toNotifyResp(notifyMapper.selectById(prior.getNotifyId()), true);
        }
        if (!Objects.equals(notify.getWorkOrderId(), lockedOrder.getId())) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        QualityWorkOrderNotifyReviewDO decision = notifyReviewMapper.selectById(reqVO.getDecisionId());
        int currentVersion = notify.getResolutionVersion() == null ? 0 : notify.getResolutionVersion();
        if (decision == null || !Objects.equals(decision.getNotifyId(), notify.getId())
                || !Objects.equals(decision.getWorkOrderId(), notify.getWorkOrderId())
                || !QualityWorkOrderNotifyReviewDO.ACTION_MARK_NOT_DELIVERED.equals(decision.getActionType())
                || !Objects.equals(decision.getResultingResolutionVersion(), reqVO.getExpectedResolutionVersion())
                || currentVersion != reqVO.getExpectedResolutionVersion()
                || !isManuallyReviewable(notify.getSendStatus())
                || !QualityWorkOrderNotifyDO.RESOLUTION_MANUAL_NOT_DELIVERED.equals(notify.getResolutionStatus())) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        // 自动次数耗尽（EXHAUSTED）正是人工授权重试的场景；授权后自动计数清零，人工授权次数独立封顶
        if (notify.getManualRetryCount() != null
                && notify.getManualRetryCount() >= QualityWorkOrderNotifyDO.MAX_AUTO_ATTEMPTS) {
            throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
        }
        int nextVersion = currentVersion + 1;
        int updated = notifyMapper.authorizeRetry(notify.getId(), currentVersion, nextVersion,
                reqVO.getReason(), actor);
        if (updated != 1) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        QualityWorkOrderNotifyReviewDO review = QualityWorkOrderNotifyReviewDO.builder()
                .workOrderId(notify.getWorkOrderId()).notifyId(notify.getId()).requestId(reqVO.getRequestId())
                .actionType(actionType).payloadHash(payloadHash)
                .expectedResolutionStatus(QualityWorkOrderNotifyDO.RESOLUTION_MANUAL_NOT_DELIVERED)
                .expectedResolutionVersion(currentVersion).decisionId(reqVO.getDecisionId())
                .outcome("RETRY_AUTHORIZED").reason(reqVO.getReason())
                .actorUserId(actor).resultingResolutionVersion(nextVersion).build();

        if (notifyReviewMapper.insert(review) != 1 || review.getId() == null) {
            throw exception(QUALITY_WORK_ORDER_STATUS_INVALID);
        }
        return toNotifyResp(notifyMapper.selectById(notify.getId()), true);
    }

    /**
     * 校验质量工单的一致性复核请求，不满足约束时拒绝当前操作。
     */
    private void assertSameReviewRequest(QualityWorkOrderNotifyReviewDO prior, Long actor,
            String payloadHash, String actionType) {
        if (!Objects.equals(prior.getActorUserId(), actor) || !Objects.equals(prior.getPayloadHash(), payloadHash)
                || !Objects.equals(prior.getActionType(), actionType)) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
    }

    /**
     * 比较质量工单的通知授权状态是否一致。
     */
    private boolean sameNotifyAuthorizationState(QualityWorkOrderDO first, QualityWorkOrderDO second) {
        return Objects.equals(first.getOwnerUserId(), second.getOwnerUserId())
                && Objects.equals(first.getCreatorUserId(), second.getCreatorUserId())
                && Objects.equals(first.getConfirmerUserId(), second.getConfirmerUserId())
                && Objects.equals(first.getHandlingDeptId(), second.getHandlingDeptId())
                && Objects.equals(first.getResponsibilityStatus(), second.getResponsibilityStatus())
                && Objects.equals(first.getLockVersion(), second.getLockVersion());
    }

    /**
     * 转换通知响应。
     */
    private QualityWorkOrderNotifyRespVO toNotifyResp(QualityWorkOrderNotifyDO notify, boolean includeReviews) {
        QualityWorkOrderNotifyRespVO vo = BeanUtils.toBean(notify, QualityWorkOrderNotifyRespVO.class);
        if (includeReviews) {
            List<QualityWorkOrderNotifyReviewDO> history = notifyReviewMapper.selectLatestByNotifyId(notify.getId(),
                    51);
            vo.setReviewsTruncated(history.size() > 50);
            vo.setReviews(history.stream().limit(50).toList().stream().map(review -> {
                QualityWorkOrderNotifyRespVO.Review item = BeanUtils.toBean(review,
                        QualityWorkOrderNotifyRespVO.Review.class);
                return item;
            }).sorted(Comparator.comparing(QualityWorkOrderNotifyRespVO.Review::getId)).toList());
        }
        return vo;
    }

    /**
     * 读取原始问题。
     */
    @Override
    public Map<String, Object> getRawIssues(Long id) {
        QualityWorkOrderDO order = validateExists(id);
        guard.assertRaw(order, guard.requireUserId());
        List<QualityWorkOrderIssueDO> rels = workOrderIssueMapper.selectListByWorkOrderId(id);
        List<Map<String, Object>> items = new ArrayList<>();
        for (QualityWorkOrderIssueDO rel : rels) {
            QualityIssueDataDO issue = issueDataMapper.selectById(rel.getIssueId());
            if (issue == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("issueId", issue.getId());
            item.put("ruleId", issue.getRuleId());
            item.put("objectId", issue.getObjectId());
            item.put("tableName", issue.getTableName());
            item.put("checkField", issue.getCheckField());
            item.put("issueCount", issue.getIssueCount());
            item.put("statScope", issue.getStatScope());
            item.put("evidenceTime", issue.getCheckTime());
            item.put("locationSql", issue.getLocationSql());
            item.put("liveValueNote", "实时回源值不是原始检出证据，须单独授权查询");
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workOrderId", id);
        result.put("issues", items);
        return result;
    }

    /**
     * 获得当前用户可见工单的趋势、状态与责任人统计。
     */
    @Override
    public QualityWorkOrderStatisticsRespVO getWorkOrderStatistics(Integer days) {
        int rangeDays = days == null || days <= 0 ? 30 : Math.min(days, 90);
        LocalDateTime asOf = LocalDateTime.now();
        LocalDate today = asOf.toLocalDate();
        LocalDateTime rangeStart = today.minusDays(rangeDays - 1L).atStartOfDay();
        var scope = new LambdaQueryWrapperX<QualityWorkOrderDO>();
        guard.applyVisibleScope(scope, guard.requireUserId());
        List<QualityWorkOrderDO> orders = workOrderMapper.selectList(scope
                .ge(QualityWorkOrderDO::getCreateTime, rangeStart)
                .le(QualityWorkOrderDO::getCreateTime, asOf));
        Map<String, int[]> trendMap = new TreeMap<>();
        for (int i = 0; i < rangeDays; i++) {
            trendMap.put(today.minusDays(rangeDays - 1L - i).toString(), new int[]{0, 0});
        }
        int overdue = 0;
        int verifiedPass = 0;
        int explained = 0;
        int exception = 0;
        int legacy = 0;
        Map<Long, Integer> ownerCounts = new HashMap<>();
        for (QualityWorkOrderDO order : orders) {
            Long owner = guard.isUnassigned(order) ? null : order.getOwnerUserId();
            ownerCounts.merge(owner, 1, Integer::sum);
            String createDate = order.getCreateTime().toLocalDate().toString();
            if (trendMap.containsKey(createDate)) {
                trendMap.get(createDate)[0]++;
            }
            if (QualityWorkOrderDO.STATUS_PROCESSED.equals(order.getOrderStatus())
                    && order.getClosedAt() != null) {
                String processDate = order.getClosedAt().toLocalDate().toString();
                if (trendMap.containsKey(processDate)) {
                    trendMap.get(processDate)[1]++;
                }
            }
            if (!QualityWorkOrderDO.STATUS_PROCESSED.equals(order.getOrderStatus())
                    && order.getDeadline() != null && asOf.isAfter(order.getDeadline())) {
                overdue++;
            }
            if (QualityWorkOrderDO.CLOSE_VERIFIED_PASS.equals(order.getCloseType())) {
                verifiedPass++;
            } else if (QualityWorkOrderDO.CLOSE_EXPLAINED.equals(order.getCloseType())) {
                explained++;
            } else if (QualityWorkOrderDO.CLOSE_EXCEPTION.equals(order.getCloseType())) {
                exception++;
            } else if (QualityWorkOrderDO.CLOSE_LEGACY.equals(order.getCloseType())) {
                legacy++;
            }
        }
        Set<Long> ownerIds = new HashSet<>(ownerCounts.keySet());
        ownerIds.remove(null);
        Map<Long, AdminUserRespDTO> userMap = ownerIds.isEmpty() ? Map.of()
                : adminUserApi.getUserList(ownerIds).getCheckedData().stream()
                .collect(Collectors.toMap(AdminUserRespDTO::getId, user -> user));
        var owners = ownerCounts.entrySet().stream().sorted(Map.Entry.<Long, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.nullsLast(Long::compareTo))))
                .map(entry -> {
                    Long id = entry.getKey();
                    AdminUserRespDTO user = id == null ? null : userMap.get(id);
                    String name = id == null ? "未认责" : user == null || StrUtil.isBlank(user.getNickname())
                            ? "用户（" + id + "）" : user.getNickname();
                    return new QualityWorkOrderStatisticsRespVO.OwnerSummary(id, name, entry.getValue());
                }).toList();
        var trend = trendMap.entrySet().stream().map(entry -> new QualityWorkOrderStatisticsRespVO.Trend(
                entry.getKey(), entry.getValue()[0], entry.getValue()[1])).toList();
        Map<String, Integer> statusSummary = orders.stream().collect(Collectors.groupingBy(
                order -> order.getOrderStatus() == null ? "UNKNOWN" : order.getOrderStatus(),
                TreeMap::new, Collectors.summingInt(order -> 1)));
        var result = new QualityWorkOrderStatisticsRespVO();
        result.setWindow(rangeDays);
        result.setAsOf(asOf);
        result.setSampleStatus(orders.isEmpty() ? "NO_SAMPLE" : "SCORED");
        result.setTotalCount(orders.size());
        result.setTrend(trend);
        result.setStatusSummary(statusSummary);
        result.setOwnerSummary(owners);
        result.setCurrentOverdue(overdue);
        result.setCloseVerifiedPass(verifiedPass);
        result.setCloseExplained(explained);
        result.setCloseException(exception);
        result.setCloseLegacy(legacy);
        return result;
    }

    /**
     * 扫描待评估工单并核验复检结果，推进可确认的处理状态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void evaluateRechecks() {
        for (QualityWorkOrderRecheckDO candidate : recheckMapper.selectRunning(20)) {
            QualityWorkOrderDO order = workOrderMapper.selectByIdForUpdate(
                    candidate.getWorkOrderId());
            // 扫描不代表获得处理权；等待工单锁后必须重新读取轮次、提交和实例。
            QualityWorkOrderRecheckDO attempt = recheckMapper.selectById(candidate.getId());
            if (attempt == null || !QualityWorkOrderSubmissionDO.RECHECK_RUNNING.equals(attempt.getStatus())) {
                continue;
            }
            QualityWorkOrderSubmissionDO submission = submissionMapper.selectById(attempt.getSubmissionId());
            if (order == null || submission == null
                    || !Objects.equals(order.getId(), submission.getWorkOrderId())
                    || !Objects.equals(order.getCurrentSubmissionId(), submission.getId())
                    || !QualityWorkOrderSubmissionDO.STATUS_ACTIVE.equals(submission.getStatus())
                    || !QualityWorkOrderDO.STATUS_RECHECKING.equals(order.getOrderStatus())
                    || !Objects.equals(submission.getInstanceId(), attempt.getInstanceId())) {
                attempt.setStatus(QualityWorkOrderRecheckDO.STATUS_OBSOLETE);
                attempt.setComparableFlag(0);
                attempt.setFinishedAt(LocalDateTime.now());
                if (recheckMapper.updateById(attempt) != 1) {
                    throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
                }
                continue;
            }
            QualityTaskInstanceDO instance = instanceMapper.selectById(attempt.getInstanceId());
            if (instance != null && (QualityTaskInstanceDO.STATUS_QUEUED.equals(instance.getInstanceStatus())
                    || QualityTaskInstanceDO.STATUS_RUNNING.equals(instance.getInstanceStatus()))) {
                continue;
            }
            RecheckJudge judge = instance == null
                    ? new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false, "复检实例不存在，需重新发起")
                    : "ETL_INLINE".equals(order.getSourceType())
                    ? judgeEtlReplay(order, instance, submission, attempt)
                    : judgeRecheck(order, instance, submission);
            // 多目标回放的其他实例可能仍在运行；保留 RUNNING 轮次，让后续轮询再检查，
            // 避免将瞬时等待误记为终态并丢失自动汇总机会。
            if ("ETL_INLINE".equals(order.getSourceType())
                    && QualityWorkOrderSubmissionDO.RECHECK_WAITING.equals(judge.recheckStatus())
                    && "部分 ETL 目标质检仍在运行".equals(judge.error())) {
                continue;
            }
            attempt.setStatus(judge.recheckStatus());
            attempt.setComparableFlag(judge.comparable() ? 1 : 0);
            attempt.setLastError(judge.error());
            if (!"ETL_INLINE".equals(order.getSourceType())) {
                attempt.setEvidenceJson(JsonUtils.toJsonString(QualityRecheckEvidence.decision(
                        order, submission, attempt, judge.recheckStatus(), judge.comparable(), judge.checks())));
            } else {
                Map<Object, Object> etlEvidence = new LinkedHashMap<>(parseReplayPlan(attempt.getEvidenceJson()));
                etlEvidence.put("decision", Map.of("status", judge.recheckStatus(), "comparable", judge.comparable(),
                        "checks", judge.checks(), "evaluatedAt", LocalDateTime.now().toString()));
                attempt.setEvidenceJson(JsonUtils.toJsonString(etlEvidence));
            }
            attempt.setFinishedAt(LocalDateTime.now());
            if (recheckMapper.updateById(attempt) != 1) {
                throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
            }
            if (submissionMapper.update(null, Wrappers.lambdaUpdate(QualityWorkOrderSubmissionDO.class)
                    .eq(QualityWorkOrderSubmissionDO::getId, submission.getId())
                    .set(QualityWorkOrderSubmissionDO::getLastError, judge.error())
                    .set(QualityWorkOrderSubmissionDO::getComparableFlag, judge.comparable() ? 1 : 0)
                    .set(QualityWorkOrderSubmissionDO::getRecheckStatus, judge.recheckStatus())) != 1) {
                throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
            }
            if (QualityWorkOrderSubmissionDO.RECHECK_PASS.equals(judge.recheckStatus())) {
                order.setOrderStatus(QualityWorkOrderDO.STATUS_PENDING_CONFIRMATION);
                order.setConfirmationPendingType(QualityWorkOrderDO.CONFIRM_RECHECK);
                order.setLastError(null);
            } else if (QualityWorkOrderSubmissionDO.RECHECK_FAIL.equals(judge.recheckStatus())) {
                order.setOrderStatus(QualityWorkOrderDO.STATUS_IN_PROGRESS);
                order.setLastError(judge.error());
                enqueueNotify(order, "RECHECK_FAIL", order.getOwnerUserId());
            } else {
                order.setOrderStatus(QualityWorkOrderDO.STATUS_PENDING_RECHECK);
                order.setLastError(judge.error());
            }
            if (workOrderMapper.update(null, Wrappers.lambdaUpdate(QualityWorkOrderDO.class)
                    .eq(QualityWorkOrderDO::getId, order.getId())
                    .set(QualityWorkOrderDO::getLockVersion, order.getLockVersion() + 1)
                    .set(QualityWorkOrderDO::getOrderStatus, order.getOrderStatus())
                    .set(QualityWorkOrderDO::getConfirmationPendingType, order.getConfirmationPendingType())
                    .set(QualityWorkOrderDO::getLastError, order.getLastError())) != 1) {
                throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
            }
        }
    }

    /**
     * 派发到期通知。
     */
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void dispatchDueNotifies() {
        // 仅发送已提交台账。挂起外事务不会释放其行锁，因此事务内误调直接拒绝。

        LocalDateTime now = LocalDateTime.now();
        List<QualityWorkOrderDO> open = workOrderMapper.selectList(new LambdaQueryWrapperX<QualityWorkOrderDO>()
                .ne(QualityWorkOrderDO::getOrderStatus, QualityWorkOrderDO.STATUS_PROCESSED)
                .isNotNull(QualityWorkOrderDO::getDeadline)
                .last("LIMIT 100"));
        for (QualityWorkOrderDO order : open) {
            if (order.getOwnerUserId() == null || order.getDeadline() == null) {
                continue;
            }
            if (now.isAfter(order.getDeadline())) {
                order.setEverOverdue(1);
                workOrderMapper.updateById(QualityWorkOrderDO.builder()
                        .id(order.getId()).everOverdue(1).build());
                enqueueNotify(order, "OVERDUE", order.getOwnerUserId());
            } else if (now.plusDays(1).isAfter(order.getDeadline())) {
                enqueueNotify(order, "DEADLINE", order.getOwnerUserId());
            }
        }
        // 进程若在远端调用前后崩溃，租约过期只能说明结果未知；通知服务按幂等键去重，
        // 因此稍后用同一键重试不会重复创建站内信，次数耗尽后进入 EXHAUSTED 等待人工处置。
        notifyMapper.markExpiredClaimsUnknown();
        for (QualityWorkOrderNotifyDO notify : notifyMapper.claimRetryable(30,
                NOTIFY_CLAIM_LEASE_SECONDS)) {
            sendNotify(notify);
        }
        expireExceptions(now);
    }

    /**
     * 核验复检与当前工单提交的关联证据，评估是否满足处理确认条件。
     */
    RecheckJudge judgeRecheck(QualityWorkOrderDO order, QualityTaskInstanceDO instance,
            QualityWorkOrderSubmissionDO submission) {
        if (QualityTaskInstanceDO.STATUS_FAILED.equals(instance.getInstanceStatus())
                || QualityTaskInstanceDO.STATUS_UNKNOWN.equals(instance.getInstanceStatus())) {
            return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false,
                    StrUtil.blankToDefault(instance.getErrorFlag(), "复检执行失败或结果未知"));
        }
        if (!QualityTaskInstanceDO.STATUS_SUCCESS.equals(instance.getInstanceStatus())) {
            return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_WAITING, false, "复检尚未完成");
        }
        if (submission != null && submission.getSubmittedAt() != null
                && instance.getStartTime() != null
                && instance.getStartTime().isBefore(submission.getSubmittedAt())) {
            return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_WAITING, false,
                    "复检开始早于当前提交，结果不可用于本次关闭");
        }
        List<Long> issueIds = convertList(workOrderIssueMapper.selectListByWorkOrderId(order.getId()),
                QualityWorkOrderIssueDO::getIssueId);
        if (issueIds.isEmpty()) {
            return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_WAITING, false, "工单没有可复检的问题项");
        }
        List<QualityAuditLogDO> audits = auditLogMapper.selectList(new LambdaQueryWrapperX<QualityAuditLogDO>()
                .eq(QualityAuditLogDO::getInstanceId, instance.getId()));
        boolean anyFail = false;
        String missingEvidence = null;
        List<Map<String, Object>> checks = new ArrayList<>();
        for (Long issueId : issueIds) {
            QualityIssueDataDO issue = issueDataMapper.selectById(issueId);
            if (issue == null || issue.getRuleId() == null || issue.getObjectId() == null) {
                missingEvidence = "原问题项或规则/对象关联不存在";
                checks.add(Map.of("issueId", issueId, "reasonCode", "ISSUE_MISSING"));
                continue;
            }
            List<QualityAuditLogDO> matches = audits.stream()
                    .filter(log -> Objects.equals(log.getRuleId(), issue.getRuleId())
                            && Objects.equals(log.getCheckObjectId(), issue.getObjectId()))
                    .toList();
            QualityAuditLogDO match = matches.size() == 1 ? matches.getFirst() : null;
            var comparison = QualityRecheckEvidence.compare(order, issue,
                    issue.getInstanceId() == null ? null : instanceMapper.selectById(issue.getInstanceId()),
                    issue.getAuditLogId() == null ? null : auditLogMapper.selectById(issue.getAuditLogId()),
                    instance, match, submission == null ? null : submission.getSubmittedAt());
            checks.add(comparison.evidence());
            if (!comparison.comparable()) {
                missingEvidence = comparison.reason();
            } else {
                anyFail |= QualityAuditLogDO.RESULT_FAIL.equals(comparison.resultType());
            }
        }
        // 只要任一目标缺少证据，整单就不具备可比性；不能被另一个 FAIL/PASS 掩盖。
        if (missingEvidence != null) {
            return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_WAITING, false,
                    missingEvidence, checks);
        }
        if (anyFail) {
            return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_FAIL, true, "目标项复检未通过", checks);
        }
        return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_PASS, true, null, checks);
    }

    /** 依据持久化的固定规则快照及审计记录，重新评估每个替换目标。 */
    private RecheckJudge judgeEtlReplay(QualityWorkOrderDO order, QualityTaskInstanceDO leadInstance,
            QualityWorkOrderSubmissionDO submission, QualityWorkOrderRecheckDO attempt) {
        if (QualityTaskInstanceDO.STATUS_QUEUED.equals(leadInstance.getInstanceStatus())
                || QualityTaskInstanceDO.STATUS_RUNNING.equals(leadInstance.getInstanceStatus())) {
            return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_WAITING, false, "ETL 目标批次质检仍在运行");
        }
        if (!QualityTaskInstanceDO.STATUS_SUCCESS.equals(leadInstance.getInstanceStatus())) {
            return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false,
                    StrUtil.blankToDefault(leadInstance.getErrorFlag(), "ETL 目标批次质检失败或结果未知"));
        }
        Map<?, ?> plan = parseReplayPlan(attempt.getEvidenceJson());
        Object rawTargets = plan.get("targetRuns");
        if (!(rawTargets instanceof List<?> targetRuns) || targetRuns.isEmpty() || targetRuns.size() > 32) {
            return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false, "ETL 复检目标证据缺失");
        }
        InlineQualityReportDO sourceReport = inlineQualityReportMapper.selectById(asLong(plan.get("sourceReportId")));
        InlineQualityReportDO replayReport = inlineQualityReportMapper.selectById(asLong(plan.get("replayReportId")));
        if (sourceReport == null || replayReport == null || !Objects.equals(submission.getId(),
                asLong(plan.get("submissionId")))) {
            return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false,
                    "ETL 原始或回放质量报告关联丢失");
        }
        List<EtlTargetLineage> lineages;
        try {
            lineages = resolveEtlTargetLineages(order, sourceReport);
        } catch (RuntimeException invalidOriginal) {
            return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false,
                    "ETL 原始目标或替换谱系证据已变化或不完整");
        }
        if (lineages.size() != targetRuns.size()) {
            return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false, "ETL 回放未覆盖全部原始目标");
        }
        List<Map<String, Object>> checks = new ArrayList<>();
        boolean anyFail = false;
        long expectedPopulation = nullToZero(sourceReport.getTotalCount());
        Set<Long> seenInstances = new HashSet<>();
        Set<Long> seenRootTargets = new HashSet<>();
        for (Object raw : targetRuns) {
            if (!(raw instanceof Map<?, ?> pair)) {
                return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false, "ETL 目标证据格式无效");
            }
            Long batchRunId = asLong(pair.get("targetBatchRunId"));
            Long expectedInstanceId = asLong(pair.get("instanceId"));
            Long rootTargetId = asLong(pair.get("rootTargetBatchId"));
            EtlTargetLineage lineage = lineages.stream()
                    .filter(item -> Objects.equals(item.root().getTargetBatchId(), rootTargetId)).findFirst()
                    .orElse(null);
            QualityEtlBatchRunDO original = lineage == null ? null : lineage.root();
            QualityEtlBatchRunDO currentHead = lineage == null ? null : lineage.head();
            QualityEtlBatchRunDO batch = batchRunId == null ? null : etlBatchRunMapper.selectById(batchRunId);
            if (original == null || currentHead == null || !seenRootTargets.add(rootTargetId)
                    || batch == null || !Objects.equals(batch.getInstanceId(), expectedInstanceId)
                    || !Objects.equals(batch.getTargetBatchId(), asLong(pair.get("targetBatchId")))
                    || !Objects.equals(currentHead.getTargetBatchId(), batch.getTargetBatchId())
                    || !Objects.equals(batch.getInlineReportId(), replayReport.getId())
                    || !Objects.equals(batch.getWorkOrderSubmissionId(), submission.getId())
                    || !Objects.equals(batch.getReplayOfBatchId(), asLong(pair.get("parentTargetBatchId")))
                    || !Objects.equals(batch.getReplayOfRecordId(), asLong(pair.get("parentRecordId")))
                    || !Objects.equals(batch.getReplayOfBatchId(), currentHead.getReplayOfBatchId())
                    || !Objects.equals(batch.getReplayOfRecordId(), currentHead.getReplayOfRecordId())
                    || !Objects.equals(asLong(pair.get("rootRecordId")), original.getRecordId())
                    || batch.getRecordId() == null || Objects.equals(batch.getRecordId(), original.getRecordId())
                    || !Objects.equals(batch.getIdentityHash(), sourceReport.getIdentityHash())
                    || !Objects.equals(batch.getQualityNodeId(), replayReport.getNodeId())
                    || !Objects.equals(batch.getQualityBatchId(), replayReport.getBatchId())
                    || !Objects.equals(batch.getRunId(), replayReport.getRunId())
                    || !Objects.equals(batch.getQualityTaskId(), original.getQualityTaskId())
                    || !Objects.equals(batch.getPhysicalBatchValue(), original.getPhysicalBatchValue())
                    || !Objects.equals(JsonUtils.parseArray(batch.getIdentityFieldsJson(), String.class),
                    JsonUtils.parseArray(sourceReport.getIdentityFieldsJson(), String.class))
                    || !Objects.equals(batch.getRowCount(), expectedPopulation)
                    || !Objects.equals(batch.getAdmittedRowCount(), expectedPopulation)) {
                return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false,
                        "ETL 复检目标与原批次全量身份摘要不匹配", checks);
            }
            QualityTaskInstanceDO instance = instanceMapper.selectById(expectedInstanceId);
            if (instance == null || !seenInstances.add(instance.getId())
                    || (checks.isEmpty() && !Objects.equals(instance.getId(), leadInstance.getId()))
                    || !Objects.equals(instance.getTaskId(), batch.getQualityTaskId())
                    || !"ETL_TARGET_BATCH".equals(instance.getRunType())) {
                return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false,
                        "ETL 复检治理实例关联不匹配", checks);
            }
            if (QualityTaskInstanceDO.STATUS_QUEUED.equals(instance.getInstanceStatus())
                    || QualityTaskInstanceDO.STATUS_RUNNING.equals(instance.getInstanceStatus())) {
                return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_WAITING, false,
                        "部分 ETL 目标质检仍在运行", checks);
            }
            if (!QualityTaskInstanceDO.STATUS_SUCCESS.equals(instance.getInstanceStatus())) {
                return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false,
                        StrUtil.blankToDefault(instance.getErrorFlag(), "部分 ETL 目标质检失败或结果未知"), checks);
            }
            QualityTaskExecutor.GateDefinition definition = JsonUtils.parseObject(instance.getDefinitionSnapshot(),
                    QualityTaskExecutor.GateDefinition.class);
            if (definition == null || definition.getRules() == null || definition.getRules().isEmpty()
                    || definition.getObjects() == null || definition.getObjects().size() != 1
                    || !Objects.equals(definition.getObjects().getFirst().getId(), batch.getCheckObjectId())) {
                return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false,
                        "ETL 复检冻结规则/对象快照不完整", checks);
            }
            List<QualityAuditLogDO> audits = auditLogMapper.selectList(new LambdaQueryWrapperX<QualityAuditLogDO>()
                    .eq(QualityAuditLogDO::getInstanceId, instance.getId()));
            if (audits.size() != definition.getRules().size()) {
                return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false,
                        "ETL 目标质检审计行未覆盖冻结规则", checks);
            }
            if (!"ETL_INLINE".equals(replayReport.getSourceType())
                    || !Objects.equals(replayReport.getExecutionUserId(), leadInstance.getExecutionUserId())
                    || !Objects.equals(replayReport.getTotalCount(), expectedPopulation)
                    || nullToZero(replayReport.getRejectCount()) != 0
                    || nullToZero(replayReport.getPassCount()) + nullToZero(
                    replayReport.getWarnCount()) != expectedPopulation
                    || !Objects.equals(replayReport.getIdentityHash(), sourceReport.getIdentityHash())
                    || !Objects.equals(JsonUtils.parseArray(replayReport.getIdentityFieldsJson(), String.class),
                    JsonUtils.parseArray(sourceReport.getIdentityFieldsJson(), String.class))) {
                return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false,
                        "ETL 回放内联报告不再证明完整的原始人口", checks);
            }
            for (QualityRuleDO rule : definition.getRules()) {
                List<QualityAuditLogDO> matched = audits.stream().filter(audit ->
                        Objects.equals(audit.getRuleId(), rule.getId())
                                && Objects.equals(audit.getCheckObjectId(), batch.getCheckObjectId())
                                && Objects.equals(audit.getTaskId(), batch.getQualityTaskId())).toList();
                QualityAuditLogDO audit = matched.size() == 1 ? matched.getFirst() : null;
                if (audit == null || !Objects.equals(audit.getTotalCount(), expectedPopulation)) {
                    return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false,
                            "ETL 目标审计缺少完整人口计数或规则关联", checks);
                }
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("targetBatchId", batch.getTargetBatchId());
                evidence.put("instanceId", instance.getId());
                evidence.put("ruleId", rule.getId());
                evidence.put("auditLogId", audit.getId());
                evidence.put("checkObjectId", audit.getCheckObjectId());
                evidence.put("totalCount", audit.getTotalCount());
                evidence.put("failCount", audit.getFailCount());
                evidence.put("resultType", audit.getResultType());
                checks.add(evidence);
                if (QualityAuditLogDO.RESULT_FAIL.equals(audit.getResultType())) {
                    anyFail = true;
                } else if (!QualityAuditLogDO.RESULT_PASS.equals(audit.getResultType())) {
                    return new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_ERROR, false,
                            "ETL 目标审计含错误、跳过或未执行结果", checks);
                }
            }
        }
        if (seenRootTargets.size() != lineages.size()) {
            return new RecheckJudge(
                    QualityWorkOrderSubmissionDO.RECHECK_ERROR, false, "ETL 回放遗漏原始目标", checks);
        }
        return anyFail ? new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_FAIL, true,
                "ETL 目标批次质检未全部通过", checks)
                : new RecheckJudge(QualityWorkOrderSubmissionDO.RECHECK_PASS, true, null, checks);
    }

    /**
     * 将证据中的编号值无损转换为长整数。
     */
    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.valueOf(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 判断质量工单的ETL尝试是否有效。
     */
    private boolean validEtlAttempt(QualityWorkOrderRecheckDO attempt, QualityWorkOrderDO order,
            QualityWorkOrderSubmissionDO submission) {
        if (attempt == null || !Objects.equals(attempt.getWorkOrderId(), order.getId())
                || !Objects.equals(attempt.getSubmissionId(), submission.getId())
                || !QualityWorkOrderSubmissionDO.RECHECK_PASS.equals(attempt.getStatus())
                || !Integer.valueOf(1).equals(attempt.getComparableFlag())
                || !QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX.equals(submission.getSubmissionType())
                || !Objects.equals(attempt.getInstanceId(), submission.getInstanceId())) {
            return false;
        }
        Map<?, ?> plan = parseReplayPlan(attempt.getEvidenceJson());
        return "ETL_TARGET_REPLAY".equals(plan.get("evidenceType"))
                && Objects.equals(asLong(plan.get("submissionId")), submission.getId())
                && plan.get("requestHash") instanceof String requestHash && !requestHash.isBlank();
    }

    /**
     * 比对ETL当前检查。
     */
    private boolean matchesEtlCurrentChecks(QualityWorkOrderRecheckDO attempt, RecheckJudge current) {
        if (!QualityWorkOrderSubmissionDO.RECHECK_PASS.equals(current.recheckStatus()) || !current.comparable()) {
            return false;
        }
        Map<?, ?> plan = parseReplayPlan(attempt.getEvidenceJson());
        Object rawDecision = plan.get("decision");
        if (!(rawDecision instanceof Map<?, ?> decision)
                || !QualityWorkOrderSubmissionDO.RECHECK_PASS.equals(decision.get("status"))
                || !Boolean.TRUE.equals(decision.get("comparable"))) {
            return false;
        }
        try {
            List<Map<String, Object>> expected = JsonUtils.parseObject(JsonUtils.toJsonString(decision.get("checks")),
                    new tools.jackson.core.type.TypeReference<>() {
                    });
            List<Map<String, Object>> actual = JsonUtils.parseObject(JsonUtils.toJsonString(current.checks()),
                    new tools.jackson.core.type.TypeReference<>() {
                    });
            return Objects.equals(expected, actual);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * 使其到期例外。
     */
    void expireExceptions(LocalDateTime now) {
        // 派发入口无事务；逐单显式开启事务，不能依赖同类调用 @Transactional。
        // 禁止挂起外事务，避免保留其锁并把远端通知夹在业务事务中。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("例外到期扫描必须在业务事务外执行");
        }

        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("例外到期扫描已中断");
        }

        boolean synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive();
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(10);
        List<QualityWorkOrderSubmissionDO> candidates = exceptionExpiryScanService.reserve(now);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive() != synchronizationActive) {
            // 必须先于首次写入，空候选也要检查；不能把查询后的污染状态当新基准。
            throw new IllegalStateException("例外到期候选查询上下文异常，停止本批");
        }
        // NEVER/SUPPORTS 可有非事务同步；候选查询合法绑定的会话也属于入批资源，不能误判泄漏。
        Map<Object, Object> resources = new HashMap<>(TransactionSynchronizationManager.getResourceMap());
        List<Long> failedSubmissions = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();
        for (QualityWorkOrderSubmissionDO candidate : candidates) {
            if (Thread.currentThread().isInterrupted()) {
                var cancelled = new CancellationException("例外到期扫描已中断");
                failures.forEach(cancelled::addSuppressed);
                throw cancelled;
            }
            // TransactionTemplate 的非 TransactionSystemException 回滚异常可能覆盖原应用异常。
            // 每项局部保存真实回调失败；只有同一应用异常经正常回滚返回，才可按普通失败隔离。
            var callbackFailure = new AtomicReference<Throwable>();
            try {
                // 仅在整个逐单事务退出后捕获，失败项不能带着半成品继续提交。
                transaction.executeWithoutResult(tx -> {
                    try {
                        expireException(candidate, now);
                        // 与到期动作同事务关闭失败账本；没有账本时不产生写入
                        expiryFailureMapper.markSucceeded(candidate.getId(), now);
                    } catch (RuntimeException | Error ex) {
                        callbackFailure.set(ex);
                        throw ex;
                    }
                });
            } catch (RuntimeException ex) {
                var contextual = new IllegalStateException("例外到期执行异常，workOrderId=" + candidate.getWorkOrderId()
                        + ", submissionId=" + candidate.getId(), ex);
                var transactionFailure = ExceptionUtils.getThrowableList(ex).stream()
                        .filter(TransactionSystemException.class::isInstance)
                        .map(TransactionSystemException.class::cast).findFirst().orElse(null);
                List<Throwable> applicationFailures = new ArrayList<>(2);
                if (callbackFailure.get() != null) {
                    applicationFailures.add(callbackFailure.get());
                }
                Throwable reportedFailure = transactionFailure == null ? null : transactionFailure.getApplicationException();
                if (reportedFailure != null && reportedFailure != callbackFailure.get()) {
                    applicationFailures.add(reportedFailure);
                }
                for (Throwable application : applicationFailures) {
                    if (application instanceof Error fatal) {
                        // 普通回滚异常也不能把直接致命错误降格为可重试业务失败。
                        failures.forEach(fatal::addSuppressed);
                        fatal.addSuppressed(contextual);
                        throw fatal;
                    }
                }
                for (Throwable application : applicationFailures) {
                    if (application != ex) {
                        contextual.addSuppressed(application);
                    }
                }
                failures.add(contextual);
                failedSubmissions.add(candidate.getId());
                boolean interrupted = ExceptionUtils.indexOfType(ex, InterruptedException.class) >= 0
                        || applicationFailures.stream().anyMatch(
                        application -> ExceptionUtils.indexOfType(application, InterruptedException.class) >= 0);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                if (Thread.currentThread().isInterrupted()
                        || ExceptionUtils.indexOfType(ex, CancellationException.class) >= 0
                        || applicationFailures.stream().anyMatch(
                        application -> ExceptionUtils.indexOfType(application, CancellationException.class) >= 0)) {
                    var cancelled = new CancellationException("例外到期扫描已取消或中断，异常提交=" + failedSubmissions);
                    failures.forEach(cancelled::addSuppressed);
                    throw cancelled;
                }
                // 回调未抛错却收到异常（开启/提交），或回滚覆盖了原异常，均不能继续下一项。
                if (transactionFailure != null || callbackFailure.get() != ex) {
                    // 事务完成异常：不再经同一事务管理器追加写入；停批并报“待核查”，下轮重新处理（到期动作幂等）。
                    var unsafe = new IllegalStateException(
                            "例外到期事务完成异常，停止本批，提交结果待核查，异常提交=" + failedSubmissions);
                    failures.forEach(unsafe::addSuppressed);
                    throw unsafe;
                }
                // 普通失败已完整回滚：持久化逐单失败，退避后自动重试，次数耗尽转人工；账本写入失败不掩盖原失败。
                recordExpiryFailure(transaction, candidate, ex, now, contextual);
            }
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isSynchronizationActive() != synchronizationActive
                    || !resources.equals(TransactionSynchronizationManager.getResourceMap())) {
                var unsafe = new IllegalStateException(
                        "例外到期上下文未清理，停止本批，提交结果待核查，submissionId=" + candidate.getId());
                failures.forEach(unsafe::addSuppressed);
                throw unsafe;
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            var cancelled = new CancellationException("例外到期扫描已中断");
            failures.forEach(cancelled::addSuppressed);
            throw cancelled;
        }
        if (!failures.isEmpty()) {
            // 无异常结束也可能是资格失效而跳过，不能称为成功创建。提交异常不得称已回滚。
            var summary = new IllegalStateException("例外到期批次存在执行异常，候选=" + candidates.size()
                    + "，无异常结束=" + (candidates.size() - failures.size()) + "，异常提交=" + failedSubmissions
                    + "，异常项提交结果待核查");
            // 不选择任意单项作为批次根因；保留所有原始异常及关联，Job 的根因摘要仍是安全批次信息。
            failures.forEach(summary::addSuppressed);
            throw summary;
        }
    }

    /**
     * 使其到期例外。
     */
    private void recordExpiryFailure(TransactionTemplate transaction, QualityWorkOrderSubmissionDO candidate,
            RuntimeException failure, LocalDateTime now, RuntimeException contextual) {
        // 在独立短事务中记录逐单失败；本方法自身失败只作为附加诊断，不改变原失败的处理路径。
        try {
            transaction.executeWithoutResult(tx -> expiryFailureMapper.recordFailure(candidate.getId(),
                    candidate.getWorkOrderId(), QualityExceptionExpiryFailureService.safeSummary(failure), now,
                    cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityExceptionExpiryFailureDO.MAX_ATTEMPTS));
        } catch (RuntimeException ledgerFailure) {
            // 账本写入失败随批次汇总异常一起上报（被抑制异常），不改变原失败的处理路径
            contextual.addSuppressed(ledgerFailure);
        }
    }

    private void expireException(QualityWorkOrderSubmissionDO candidate, LocalDateTime now) {
        QualityWorkOrderDO order = workOrderMapper.selectByIdForUpdate(candidate.getWorkOrderId());
        if (order == null || !QualityWorkOrderDO.CLOSE_EXCEPTION.equals(order.getCloseType())
                || !QualityWorkOrderDO.STATUS_PROCESSED.equals(order.getOrderStatus())) {
            return;
        }
        // 扫描结果只是候选；所有合法提交/撤回/重开同样锁所属工单。
        QualityWorkOrderSubmissionDO submission = submissionMapper.selectById(candidate.getId());
        if (submission == null || !Objects.equals(submission.getWorkOrderId(), order.getId())
                || !Objects.equals(order.getCurrentSubmissionId(), submission.getId())
                || !QualityWorkOrderSubmissionDO.TYPE_EXCEPTION.equals(submission.getSubmissionType())
                || !QualityWorkOrderSubmissionDO.STATUS_ACTIVE.equals(submission.getStatus())
                || submission.getExceptionExpireAt() == null || submission.getExceptionExpireAt().isAfter(now)) {
            return;
        }
        boolean already = actionMapper.selectListByWorkOrderId(order.getId()).stream()
                .anyMatch(item -> "EXCEPTION_EXPIRED".equals(item.getActionType()));
        if (already) {
            return;
        }
        QualityWorkOrderActionDO expiryAction = QualityWorkOrderActionDO.builder()
                .workOrderId(order.getId())
                .requestId("expire-" + submission.getId())
                .actionType("EXCEPTION_EXPIRED")
                .fromStatus(order.getOrderStatus())
                .toStatus(order.getOrderStatus())
                .reason("例外到期，原关闭记录保持不变")
                .payloadHash(DigestUtil.sha256Hex("expire:" + submission.getId()))
                .actorUserId(0L)
                .submissionId(submission.getId())
                .build();
        if (actionMapper.insert(expiryAction) != 1 || expiryAction.getId() == null || expiryAction.getId() <= 0) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        List<QualityWorkOrderIssueDO> issues = workOrderIssueMapper.selectListByWorkOrderId(order.getId());
        String suffix = "-例外到期复核";
        QualityWorkOrderDO followUp = QualityWorkOrderDO.builder()
                .orderName(StrUtil.sub(StrUtil.blankToDefault(order.getOrderName(), "工单"), 0,
                        256 - suffix.length()) + suffix)
                .rootOrderId(order.getRootOrderId() != null ? order.getRootOrderId() : order.getId())
                .sourceType("EXCEPTION_EXPIRED")
                .taskId(order.getTaskId())
                .taskName(order.getTaskName())
                .issueCount((long) issues.size())
                .handlingDeptId(order.getHandlingDeptId())
                .ownerUserId(order.getOwnerUserId())
                .orderStatus(QualityWorkOrderDO.STATUS_PENDING)
                .responsibilityStatus(order.getOwnerUserId() == null
                        ? QualityWorkOrderDO.RESP_UNASSIGNED : QualityWorkOrderDO.RESP_ASSIGNED)
                .lockVersion(0)
                .everOverdue(0)
                .closedOverdue(0)
                .description("例外到期自动发起复核，不改写原关闭类型")
                .build();
        if (workOrderMapper.insert(followUp) != 1 || followUp.getId() == null || followUp.getId() <= 0) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        if (CollUtil.isNotEmpty(issues) && !workOrderIssueMapper.insertBatch(issues.stream()
                .map(item -> QualityWorkOrderIssueDO.builder()
                        .workOrderId(followUp.getId()).issueId(item.getIssueId()).build())
                .toList())) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        // 批量 JDBC 可能报告成功但触发器静默跳过，复读验证完整关联（含重复数）。
        if (!convertList(issues, QualityWorkOrderIssueDO::getIssueId).stream().sorted().toList().equals(
                convertList(workOrderIssueMapper.selectListByWorkOrderId(followUp.getId()),
                        QualityWorkOrderIssueDO::getIssueId).stream().sorted().toList())) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        enqueueNotify(followUp, "EXCEPTION_EXPIRED", followUp.getOwnerUserId());
        if (followUp.getOwnerUserId() != null && notifyMapper.selectCount(
                new LambdaQueryWrapperX<QualityWorkOrderNotifyDO>()
                        .eq(QualityWorkOrderNotifyDO::getWorkOrderId, followUp.getId())
                        .eq(QualityWorkOrderNotifyDO::getEventType, "EXCEPTION_EXPIRED")
                        .eq(QualityWorkOrderNotifyDO::getReceiverUserId, followUp.getOwnerUserId())) != 1) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        // 仅绑定本事务新建结果，不将旧审计或同根候选补记为成功。
        String issueHash = DigestUtil.sha256Hex("expiry-issues-v1:" + issues.stream()
                .map(QualityWorkOrderIssueDO::getIssueId).sorted().map(String::valueOf)
                .collect(Collectors.joining(",")));
        int bound = actionMapper.update(null, Wrappers.lambdaUpdate(QualityWorkOrderActionDO.class)
                .eq(QualityWorkOrderActionDO::getId, expiryAction.getId())
                .eq(QualityWorkOrderActionDO::getWorkOrderId, order.getId())
                .eq(QualityWorkOrderActionDO::getSubmissionId, submission.getId())
                .eq(QualityWorkOrderActionDO::getActionType, expiryAction.getActionType())
                .eq(QualityWorkOrderActionDO::getRequestId, expiryAction.getRequestId())
                .eq(QualityWorkOrderActionDO::getPayloadHash, expiryAction.getPayloadHash())
                .isNull(QualityWorkOrderActionDO::getFollowUpWorkOrderId)
                .isNull(QualityWorkOrderActionDO::getExpiryResultVersion)
                .isNull(QualityWorkOrderActionDO::getExpiryIssueHash)
                .isNull(QualityWorkOrderActionDO::getExpiryIssueCount)
                .isNull(QualityWorkOrderActionDO::getExpiryReceiverUserId)
                .set(QualityWorkOrderActionDO::getFollowUpWorkOrderId, followUp.getId())
                .set(QualityWorkOrderActionDO::getExpiryResultVersion, 1)
                .set(QualityWorkOrderActionDO::getExpiryIssueHash, issueHash)
                .set(QualityWorkOrderActionDO::getExpiryIssueCount, (long) issues.size())
                .set(QualityWorkOrderActionDO::getExpiryReceiverUserId, followUp.getOwnerUserId()));
        if (bound != 1) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        QualityWorkOrderActionDO persisted = actionMapper.selectByRequest(order.getId(), expiryAction.getRequestId());
        // 影响行数不能证明触发器保存了正确结果；复读全部身份和创建时快照。
        if (persisted == null || !Objects.equals(persisted.getId(), expiryAction.getId())
                || !Objects.equals(persisted.getWorkOrderId(), order.getId())
                || !Objects.equals(persisted.getSubmissionId(), submission.getId())
                || !Objects.equals(persisted.getActionType(), expiryAction.getActionType())
                || !Objects.equals(persisted.getRequestId(), expiryAction.getRequestId())
                || !Objects.equals(persisted.getPayloadHash(), expiryAction.getPayloadHash())
                || !Objects.equals(persisted.getFollowUpWorkOrderId(), followUp.getId())
                || !Objects.equals(persisted.getExpiryResultVersion(), 1)
                || !Objects.equals(persisted.getExpiryIssueHash(), issueHash)
                || !Objects.equals(persisted.getExpiryIssueCount(), (long) issues.size())
                || !Objects.equals(persisted.getExpiryReceiverUserId(), followUp.getOwnerUserId())) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
    }

    record RecheckJudge(String recheckStatus, boolean comparable, String error, List<Map<String, Object>> checks) {

        /**
         * 使用给定复检状态、可比、错误初始化质量工单。
         */
        RecheckJudge(String recheckStatus, boolean comparable, String error) {
            this(recheckStatus, comparable, error, List.of());
        }
    }

    private interface OrderMutator {

        /**
         * 应用质量工单配置或已校验的变更。
         */
        void apply(QualityWorkOrderDO order, Long userId);
    }

    /**
     * 在锁定工单的事务中校验命令权限及预期状态，再应用状态变更。
     */
    private QualityWorkOrderRespVO mutate(QualityWorkOrderCommandReqVO reqVO, String action, OrderMutator mutator) {
        Long userId = guard.requireUserId();

        // 增补重放门禁，不替代原有命令校验。RPC 在行锁之前执行。
        QualityWorkOrderDO authorized = validateExists(reqVO.getId());
        assertCommandAccess(authorized, userId, action);
        QualityWorkOrderDO order = workOrderMapper.selectByIdForUpdate(reqVO.getId());
        if (order == null) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        assertAuthorizationUnchanged(order, authorized);
        String payloadHash = DigestUtil.sha256Hex(JsonUtils.toJsonString(reqVO));
        QualityWorkOrderActionDO existing = actionMapper.selectByRequest(reqVO.getId(), reqVO.getRequestId());
        if (existing != null) {
            if (!Objects.equals(existing.getPayloadHash(), payloadHash)
                    || !Objects.equals(existing.getActorUserId(), userId)
                    || !Objects.equals(existing.getActionType(), action)) {
                throw exception(QUALITY_WORK_ORDER_REQUEST_CONFLICT);
            }
            return toResp(order, true);
        }
        if (!Objects.equals(order.getLockVersion(), reqVO.getExpectedVersion())) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        String fromStatus = order.getOrderStatus();
        Long fromOwner = order.getOwnerUserId();
        Long fromDept = order.getHandlingDeptId();
        mutator.apply(order, userId);
        int cas = workOrderMapper.update(null, Wrappers.lambdaUpdate(QualityWorkOrderDO.class)
                .eq(QualityWorkOrderDO::getId, order.getId())
                .eq(QualityWorkOrderDO::getLockVersion, reqVO.getExpectedVersion())
                .set(QualityWorkOrderDO::getLockVersion, reqVO.getExpectedVersion() + 1)
                .set(QualityWorkOrderDO::getOrderStatus, order.getOrderStatus())
                .set(QualityWorkOrderDO::getResponsibilityStatus, order.getResponsibilityStatus())
                .set(QualityWorkOrderDO::getOwnerUserId, order.getOwnerUserId())
                .set(QualityWorkOrderDO::getHandlingDeptId, order.getHandlingDeptId())
                .set(QualityWorkOrderDO::getCurrentSubmissionId, order.getCurrentSubmissionId())
                .set(QualityWorkOrderDO::getConfirmationPendingType, order.getConfirmationPendingType())
                .set(QualityWorkOrderDO::getCloseType, order.getCloseType())
                .set(QualityWorkOrderDO::getCloseReason, order.getCloseReason())
                .set(QualityWorkOrderDO::getClosedBy, order.getClosedBy())
                .set(QualityWorkOrderDO::getClosedAt, order.getClosedAt())
                .set(QualityWorkOrderDO::getClosedOverdue, order.getClosedOverdue())
                .set(QualityWorkOrderDO::getEverOverdue, order.getEverOverdue())
                .set(QualityWorkOrderDO::getDeadline, order.getDeadline())
                .set(QualityWorkOrderDO::getDeadlineBasis, order.getDeadlineBasis())
                .set(QualityWorkOrderDO::getLastError, order.getLastError())
                .set(QualityWorkOrderDO::getAuditOpinion, order.getAuditOpinion()));
        if (cas != 1) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
        try {
            actionMapper.insert(QualityWorkOrderActionDO.builder()
                    .workOrderId(order.getId())
                    .requestId(reqVO.getRequestId())
                    .actionType(action)
                    .fromStatus(fromStatus)
                    .toStatus(order.getOrderStatus())
                    .fromOwnerUserId(fromOwner)
                    .toOwnerUserId(order.getOwnerUserId())
                    .fromDeptId(fromDept)
                    .toDeptId(order.getHandlingDeptId())
                    .reason(StrUtil.blankToDefault(reqVO.getReason(), reqVO.getContent()))
                    .payloadHash(payloadHash)
                    .actorUserId(userId)
                    .actorDeptId(guard.deptId(userId))
                    .submissionId(order.getCurrentSubmissionId())
                    .build());
        } catch (DuplicateKeyException duplicate) {
            // PostgreSQL 此时已将事务标记失败；整笔回滚，禁止继续查询后返回成功。
            throw exception(QUALITY_WORK_ORDER_REQUEST_CONFLICT);
        }
        return toResp(workOrderMapper.selectById(order.getId()), true);
    }

    /**
     * 复核准备阶段与写入阶段的操作人授权依据是否一致。
     */
    private void assertAuthorizationUnchanged(QualityWorkOrderDO order, QualityWorkOrderDO authorized) {
        if (!Objects.equals(order.getOwnerUserId(), authorized.getOwnerUserId())
                || !Objects.equals(order.getHandlingDeptId(), authorized.getHandlingDeptId())
                || !Objects.equals(order.getCreatorUserId(), authorized.getCreatorUserId())
                || !Objects.equals(order.getConfirmerUserId(), authorized.getConfirmerUserId())
                || !Objects.equals(order.getResponsibilityStatus(), authorized.getResponsibilityStatus())) {
            throw exception(QUALITY_WORK_ORDER_VERSION_CONFLICT);
        }
    }

    /**
     * 校验质量工单的命令访问，不满足约束时拒绝当前操作。
     */
    private void assertCommandAccess(QualityWorkOrderDO order, Long userId, String action) {
        String permission = switch (action) {
            case "ASSIGN" -> QualityWorkOrderGuard.PERM_ASSIGN;
            case "CLAIM" -> QualityWorkOrderGuard.PERM_CLAIM;
            case "CONFIRM" -> QualityWorkOrderGuard.PERM_CONFIRM;
            case "REOPEN" -> QualityWorkOrderGuard.PERM_REOPEN;
            case "COORDINATE" -> QualityWorkOrderGuard.PERM_COORDINATE;
            default -> QualityWorkOrderGuard.PERM_PROCESS;
        };
        if (!guard.has(userId, permission)) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        switch (action) {
            case "ASSIGN" -> guard.assertAssignable(order, userId);
            case "CONFIRM" -> guard.assertConfirm(order, userId);
            case "REOPEN", "COORDINATE" -> guard.assertVisible(order, userId);
            default -> guard.assertHandle(order, userId);
        }
    }

    /**
     * 关闭质量工单资源或结束当前处理。
     */
    private void close(QualityWorkOrderDO order, Long userId, String closeType, String reason) {
        if (order.getDeadline() != null && LocalDateTime.now().isAfter(order.getDeadline())) {
            order.setEverOverdue(1);
            order.setClosedOverdue(1);
        }
        order.setOrderStatus(QualityWorkOrderDO.STATUS_PROCESSED);
        order.setCloseType(closeType);
        order.setCloseReason(reason);
        order.setClosedBy(userId);
        order.setClosedAt(LocalDateTime.now());
        order.setAuditOpinion(reason);
        order.setConfirmationPendingType(null);
    }

    /**
     * 校验质量工单的有效提交记录，不满足约束时拒绝当前操作。
     */
    private QualityWorkOrderSubmissionDO requireActiveSubmission(QualityWorkOrderDO order, Long submissionId) {
        Long id = submissionId != null ? submissionId : order.getCurrentSubmissionId();
        if (id == null || !Objects.equals(id, order.getCurrentSubmissionId())) {
            throw exception(QUALITY_WORK_ORDER_SUBMISSION_INVALID);
        }
        QualityWorkOrderSubmissionDO submission = submissionMapper.selectById(id);
        if (submission == null
                || !Objects.equals(submission.getWorkOrderId(), order.getId())
                || !QualityWorkOrderSubmissionDO.STATUS_ACTIVE.equals(submission.getStatus())) {
            throw exception(QUALITY_WORK_ORDER_SUBMISSION_INVALID);
        }
        return submission;
    }

    /**
     * 替代有效。
     */
    private void supersedeActive(Long workOrderId) {
        for (QualityWorkOrderSubmissionDO item : submissionMapper.selectListByWorkOrderId(workOrderId)) {
            if (QualityWorkOrderSubmissionDO.STATUS_ACTIVE.equals(item.getStatus())) {
                item.setStatus(QualityWorkOrderSubmissionDO.STATUS_SUPERSEDED);
                submissionMapper.updateById(item);
            }
        }
    }

    /**
     * 校验质量工单的有效例外，不满足约束时拒绝当前操作。
     */
    private void requireValidException(String scope, LocalDateTime expireAt) {
        if (StrUtil.isBlank(scope) || expireAt == null || !expireAt.isAfter(LocalDateTime.now())) {
            throw exception(QUALITY_WORK_ORDER_EXCEPTION_REQUIRED);
        }
    }

    /**
     * 校验质量工单的修复完成，不满足约束时拒绝当前操作。
     */
    private void requireRepairDone(Long workOrderId, Long repairOrderId, List<Long> issueIds) {
        if (repairOrderId == null) {
            throw exception(QUALITY_WORK_ORDER_REPAIR_NOT_DONE);
        }
        QualityRepairOrderDO repair = repairOrderMapper.selectById(repairOrderId);
        if (repair == null || !QualityRepairOrderDO.EXEC_SUCCESS.equals(repair.getExecStatus())
                || !QualityRepairOrderDO.APPROVE_PASSED.equals(repair.getApproveStatus())
                || repair.getObjectId() == null) {
            throw exception(QUALITY_WORK_ORDER_REPAIR_NOT_DONE);
        }
        // 修复凭据只可关联本工单（或本次指定明细）的对象，不能借其他工单的成功记录交差。
        List<Long> selected = CollUtil.isEmpty(issueIds) ? convertList(
                workOrderIssueMapper.selectListByWorkOrderId(workOrderId), QualityWorkOrderIssueDO::getIssueId)
                : issueIds;
        boolean related = selected.stream().map(issueDataMapper::selectById).filter(Objects::nonNull)
                .anyMatch(issue -> Objects.equals(issue.getObjectId(), repair.getObjectId()));
        if (!related) {
            throw exception(QUALITY_WORK_ORDER_REPAIR_NOT_DONE);
        }
    }

    /**
     * 生成工单提交内容的规范化表示。
     */
    private String canonicalSubmit(QualityWorkOrderCommandReqVO reqVO, String type) {
        return type + "|" + StrUtil.nullToEmpty(reqVO.getContent()) + "|"
                + StrUtil.nullToEmpty(reqVO.getEvidenceRef()) + "|"
                + reqVO.getRepairOrderId() + "|" + reqVO.getIssueIds() + "|"
                + StrUtil.nullToEmpty(reqVO.getExceptionScope()) + "|" + reqVO.getExceptionExpireAt();
    }

    /**
     * 入队通知。
     */
    private void enqueueNotify(QualityWorkOrderDO order, String eventType, Long receiverUserId) {
        if (receiverUserId == null) {
            return;
        }
        String slice = LocalDate.now(NOTIFY_CLOCK_ZONE) + ":" + eventType;
        if (notifyMapper.selectDedup(order.getId(), eventType, receiverUserId, slice) != null) {
            return;
        }
        QualityWorkOrderNotifyDO notify = QualityWorkOrderNotifyDO.builder()
                .workOrderId(order.getId())
                .eventType(eventType)
                .receiverUserId(receiverUserId)
                .timeSlice(slice)
                .payloadSummary(order.getOrderName())
                .sendStatus(QualityWorkOrderNotifyDO.STATUS_PENDING)
                .retryCount(0)
                .nextRetryTime(LocalDateTime.now(NOTIFY_CLOCK_ZONE))
                .build();
        try {
            notifyMapper.insert(notify);
        } catch (DuplicateKeyException ignored) {
            return;
        }
        // 与工单事务一起提交持久意图；现有XXL-Job 投递并恢复待发送记录。
        // 这里不发 RPC，也不以进程内 afterCommit 回调作为唯一恢复依据。
    }

    /**
     * 认领持久化通知并发送，记录结果及后续重试信息。
     */
    /** 结果未知或自动投递次数耗尽的通知才进入人工核验 */
    private static boolean isManuallyReviewable(String sendStatus) {
        return QualityWorkOrderNotifyDO.STATUS_UNKNOWN.equals(sendStatus)
                || QualityWorkOrderNotifyDO.STATUS_EXHAUSTED.equals(sendStatus);
    }

    private void sendNotify(QualityWorkOrderNotifyDO notify) {
        String status;
        String error = null;
        try {
            NotifySendSingleToUserReqDTO reqDTO = new NotifySendSingleToUserReqDTO();
            reqDTO.setUserId(notify.getReceiverUserId());
            reqDTO.setTemplateCode("quality_work_order");
            // 同一投递记录的所有尝试共用幂等键：远端最多创建一条站内信，结果未知时可安全重试
            reqDTO.setIdempotencyKey(QualityWorkOrderNotifyDO.IDEMPOTENCY_KEY_PREFIX + notify.getId());
            reqDTO.setTemplateParams(Map.of(
                    "orderName", StrUtil.blankToDefault(notify.getPayloadSummary(), ""),
                    "eventType", notify.getEventType()));
            CommonResult<Long> response = notifyMessageSendApi.sendSingleMessageToAdmin(reqDTO);
            if (response == null) {
                status = QualityWorkOrderNotifyDO.STATUS_UNKNOWN;
                error = "通知服务未返回响应，投递结果未知";
            } else if (response.isError()) {
                // 明确发生在 insert 前的拒绝码记为 FAILED（确定未创建）；其余错误可能晚于远端提交，记为 UNKNOWN。
                // 两者都按幂等键自动重试：远端已创建时重试只返回原消息编号，不会重复通知。
                if (Objects.equals(response.getCode(),
                        cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.NOTICE_NOT_FOUND.getCode())) {
                    status = QualityWorkOrderNotifyDO.STATUS_FAILED;
                    error = "通知模板不存在，未创建站内信";
                } else if (Objects.equals(response.getCode(),
                        cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.NOTIFY_SEND_TEMPLATE_PARAM_MISS.getCode())) {
                    status = QualityWorkOrderNotifyDO.STATUS_FAILED;
                    error = "通知模板参数校验失败，未创建站内信";
                } else {
                    status = QualityWorkOrderNotifyDO.STATUS_UNKNOWN;
                    error = "通知服务返回非白名单错误，投递结果未知";
                }
            } else if (response.getData() == null) {
                // system NotifySendService 明确以 success(null) 表示模板已停用，未创建消息。
                status = QualityWorkOrderNotifyDO.STATUS_FAILED;
                error = "通知模板已停用，未创建站内信";
            } else if (response.getData() <= 0) {
                status = QualityWorkOrderNotifyDO.STATUS_UNKNOWN;
                error = "通知服务返回无效消息编号，投递结果未知";
            } else {
                status = QualityWorkOrderNotifyDO.STATUS_SENT;
            }
        } catch (Exception ex) {
            // RPC 异常可能发生在远端提交之后：记为 UNKNOWN，稍后用同一幂等键重试（远端去重，不会重复创建）。
            status = QualityWorkOrderNotifyDO.STATUS_UNKNOWN;
            error = "远端通知调用异常，投递结果未知";
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        long retryDelaySeconds = Math.min(60,
                Math.max(1, notify.getRetryCount() == null ? 1 : notify.getRetryCount() + 1) * 5L) * 60;
        if (notifyMapper.finishClaim(notify.getId(), notify.getClaimToken(), status,
                retryDelaySeconds, error) != 1) {
            // 不清除 claim 或退回 PENDING：租约恢复器之后会把该记录收敛到 UNKNOWN。
            throw new IllegalStateException("工单通知认领完成状态写入失败，保持认领等待过期恢复");
        }
    }

    /**
     * 将质量工单业务记录转换为接口响应。
     */
    private QualityWorkOrderRespVO toResp(QualityWorkOrderDO order, boolean detail) {
        QualityWorkOrderRespVO vo = BeanUtils.toBean(order, QualityWorkOrderRespVO.class);
        vo.setIssueIds(convertList(workOrderIssueMapper.selectListByWorkOrderId(order.getId()),
                QualityWorkOrderIssueDO::getIssueId));
        vo.setAllowedActions(allowedActions(order));
        if (detail && order.getCurrentSubmissionId() != null) {
            QualityWorkOrderSubmissionDO submission = submissionMapper.selectById(order.getCurrentSubmissionId());
            if (submission != null) {
                vo.setCurrentSubmission(BeanUtils.toBean(submission,
                        QualityWorkOrderRespVO.QualityWorkOrderSubmissionRespVO.class));
                vo.getCurrentSubmission().setRechecks(convertList(recheckMapper.selectBySubmission(submission.getId()),
                        attempt -> BeanUtils.toBean(attempt,
                                QualityWorkOrderRespVO.QualityWorkOrderRecheckRespVO.class)));
            }
        }
        if (detail && "ETL_INLINE".equals(order.getSourceType())) {
            QualityWorkOrderEtlReportDO link = workOrderEtlReportMapper.selectByWorkOrderId(order.getId());
            if (link != null) {
                InlineQualityReportDO report = inlineQualityReportMapper.selectById(link.getReportId());
                if (report != null) {
                    InlineQualityReportDTO reportVO = new InlineQualityReportDTO();
                    reportVO.setReportId(report.getId());
                    reportVO.setWorkOrderId(order.getId());
                    reportVO.setRunId(report.getRunId());
                    reportVO.setNodeId(report.getNodeId());
                    reportVO.setBatchId(report.getBatchId());
                    reportVO.setSourceType(report.getSourceType());
                    reportVO.setPlanHash(report.getPlanHash());
                    reportVO.setTotalCount(report.getTotalCount());
                    reportVO.setPassCount(report.getPassCount());
                    reportVO.setWarnCount(report.getWarnCount());
                    reportVO.setRejectCount(report.getRejectCount());
                    reportVO.setIssueReferences(JsonUtils.parseArray(report.getIssueReferencesJson(), String.class));
                    reportVO.setCreateTime(report.getCreateTime());
                    vo.setEtlReport(reportVO);
                }
            }
        }
        if (detail) {
            vo.setHistory(convertList(actionMapper.selectListByWorkOrderId(order.getId()), this::toAction));
        }
        fillUserNames(List.of(vo));
        return vo;
    }

    /**
     * 转换操作。
     */
    private QualityWorkOrderRespVO.QualityWorkOrderActionRespVO toAction(QualityWorkOrderActionDO action) {
        QualityWorkOrderRespVO.QualityWorkOrderActionRespVO vo =
                BeanUtils.toBean(action, QualityWorkOrderRespVO.QualityWorkOrderActionRespVO.class);
        vo.setCreateTime(action.getCreateTime());
        return vo;
    }

    /**
     * 根据当前用户权限与工单状态计算可执行操作。
     */
    private List<String> allowedActions(QualityWorkOrderDO order) {
        List<String> actions = new ArrayList<>();
        boolean etlInline = "ETL_INLINE".equals(order.getSourceType());
        if (QualityWorkOrderDO.STATUS_PENDING.equals(order.getOrderStatus())
                && guard.isUnassigned(order)) {
            actions.add("ASSIGN");
        }
        if (QualityWorkOrderDO.STATUS_PENDING.equals(order.getOrderStatus())
                && QualityWorkOrderDO.RESP_ASSIGNED.equals(order.getResponsibilityStatus())) {
            actions.add("CLAIM");
            actions.add("TRANSFER");
        }
        if (QualityWorkOrderDO.STATUS_IN_PROGRESS.equals(order.getOrderStatus())) {
            actions.add("SUBMIT");
            actions.add("TRANSFER");
            actions.add("EXTEND");
        }
        if (!etlInline && QualityWorkOrderDO.STATUS_PENDING_RECHECK.equals(order.getOrderStatus())) {
            actions.add("RECHECK");
            actions.add("WITHDRAW");
        }
        if (QualityWorkOrderDO.STATUS_RECHECKING.equals(order.getOrderStatus())) {
            actions.add("WITHDRAW");
        }
        if (QualityWorkOrderDO.STATUS_PENDING_CONFIRMATION.equals(order.getOrderStatus())) {
            if (!etlInline || QualityWorkOrderDO.CONFIRM_RECHECK.equals(order.getConfirmationPendingType())) {
                actions.add("CONFIRM");
            }
            if (!etlInline) {
                actions.add("WITHDRAW");
            }
        }
        if (!etlInline && QualityWorkOrderDO.STATUS_PROCESSED.equals(order.getOrderStatus())) {
            actions.add("REOPEN");
        }
        if (!QualityWorkOrderDO.STATUS_PROCESSED.equals(order.getOrderStatus())) {
            actions.add("COORDINATE");
        }
        return actions;
    }

    /**
     * 校验质量工单的问题存在，不满足约束时拒绝当前操作。
     */
    private void assertIssuesExist(List<Long> issueIds) {
        if (CollUtil.isEmpty(issueIds)) {
            return;
        }
        for (Long issueId : issueIds) {
            if (issueId == null || issueDataMapper.selectById(issueId) == null) {
                throw exception(QUALITY_ISSUE_NOT_EXISTS);
            }
        }
    }

    /**
     * 校验质量工单的问题范围，不满足约束时拒绝当前操作。
     */
    private void assertIssueScope(Long workOrderId, List<Long> issueIds) {
        if (CollUtil.isEmpty(issueIds)) {
            return;
        }
        assertIssuesExist(issueIds);
        Set<Long> bound = new HashSet<>(convertList(
                workOrderIssueMapper.selectListByWorkOrderId(workOrderId), QualityWorkOrderIssueDO::getIssueId));
        if (!bound.containsAll(issueIds)) {
            throw exception(QUALITY_WORK_ORDER_ISSUE_SCOPE_INVALID);
        }
    }

    /**
     * 统计问题。
     */
    private Long countIssues(List<Long> issueIds) {
        return issueIds == null ? 0L : (long) new HashSet<>(issueIds).size();
    }

    /**
     * 保存质量工单的问题关联。
     */
    private void saveIssueRelations(Long workOrderId, List<Long> issueIds) {
        if (CollUtil.isEmpty(issueIds)) {
            return;
        }
        workOrderIssueMapper.insertBatch(issueIds.stream().distinct()
                .map(issueId -> QualityWorkOrderIssueDO.builder()
                        .workOrderId(workOrderId).issueId(issueId).build())
                .toList());
    }

    /**
     * 补充质量工单的用户名称。
     */
    private void fillUserNames(List<QualityWorkOrderRespVO> list) {
        Set<Long> userIds = new HashSet<>();
        list.forEach(vo -> {
            if (vo.getOwnerUserId() != null) {
                userIds.add(vo.getOwnerUserId());
            }
            if (vo.getCreatorUserId() != null) {
                userIds.add(vo.getCreatorUserId());
            }
        });
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, AdminUserRespDTO> userMap = adminUserApi.getUserList(userIds).getCheckedData().stream()
                .collect(Collectors.toMap(AdminUserRespDTO::getId, user -> user));
        list.forEach(vo -> {
            AdminUserRespDTO owner = userMap.get(vo.getOwnerUserId());
            if (owner != null) {
                vo.setOwnerUserName(owner.getNickname());
            }
            AdminUserRespDTO creator = userMap.get(vo.getCreatorUserId());
            if (creator != null) {
                vo.setCreatorUserName(creator.getNickname());
            }
        });
    }

    /**
     * 读取并校验质量工单记录存在，不存在时抛出业务异常。
     */
    private QualityWorkOrderDO validateExists(Long id) {
        QualityWorkOrderDO order = id == null ? null : workOrderMapper.selectById(id);
        if (order == null) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        return order;
    }

}
