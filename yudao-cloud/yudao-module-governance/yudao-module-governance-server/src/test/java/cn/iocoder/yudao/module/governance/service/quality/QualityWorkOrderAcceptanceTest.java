package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderCommandReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.etl.InlineQualityReportDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.etl.InlineQualityReportMapper;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderReplayDescribeReqDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_ISSUE_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_ISSUE_SCOPE_INVALID;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_RAW_FORBIDDEN;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_SELF_CONFIRM;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_UNASSIGNED;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_VERSION_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QualityWorkOrderAcceptanceTest {

    @Mock private QualityWorkOrderMapper workOrderMapper;
    @Mock private QualityWorkOrderIssueMapper workOrderIssueMapper;
    @Mock private QualityWorkOrderEtlReportMapper workOrderEtlReportMapper;
    @Mock private InlineQualityReportMapper inlineQualityReportMapper;
    @Mock private QualityWorkOrderSubmissionMapper submissionMapper;
    @Mock private QualityWorkOrderRecheckMapper recheckMapper;
    @Mock private QualityWorkOrderActionMapper actionMapper;
    @Mock private QualityWorkOrderNotifyMapper notifyMapper;
    @Mock private QualityIssueDataMapper issueDataMapper;
    @Mock private QualityAuditLogMapper auditLogMapper;
    @Mock private QualityTaskInstanceMapper instanceMapper;
    @Mock private QualityEtlBatchRunMapper etlBatchRunMapper;
    @Mock private QualityRepairOrderMapper repairOrderMapper;
    @Mock private QualityRunService qualityRunService;
    @Mock private QualityWorkOrderGuard guard;
    @Mock private AdminUserApi adminUserApi;
    @Mock private NotifyMessageSendApi notifyMessageSendApi;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Mock private QualityExceptionExpiryScanService exceptionExpiryScanService;
    @Mock private cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityExceptionExpiryFailureMapper expiryFailureMapper;
    @InjectMocks private QualityWorkOrderServiceImpl service;
    private static final LocalDateTime RECHECK_START = LocalDateTime.of(2026, 9, 23, 10, 0);
    private String fixtureDatasourceType = "THIRD_PARTY";

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        org.mockito.Mockito.lenient().when(guard.has(any(), any())).thenReturn(true);
        org.mockito.Mockito.lenient().when(recheckMapper.updateById(any(QualityWorkOrderRecheckDO.class))).thenReturn(1);
        org.mockito.Mockito.lenient().when(recheckMapper.insert(any(QualityWorkOrderRecheckDO.class))).thenReturn(1);
        org.mockito.Mockito.lenient().when(submissionMapper.update(any(), any())).thenReturn(1);
        org.mockito.Mockito.lenient().when(workOrderMapper.update(any(), any())).thenReturn(1);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "wo"),
                QualityWorkOrderDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "wo-sub"),
                QualityWorkOrderSubmissionDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "wo-act"),
                QualityWorkOrderActionDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "wo-n"),
                QualityWorkOrderNotifyDO.class);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void t16InstanceSuccessWithFailRuleCannotPass() {
        mockIssueAndAudit(QualityAuditLogDO.RESULT_FAIL, 1000L, 20L);
        QualityWorkOrderServiceImpl.RecheckJudge judge = service.judgeRecheck(
                order(8L), successInstance(99L, RECHECK_START),
                submission(RECHECK_START.minusMinutes(10)));
        assertThat(judge.recheckStatus()).isEqualTo(QualityWorkOrderSubmissionDO.RECHECK_FAIL);
        assertThat(judge.comparable()).isTrue();
    }

    @Test
    void t16ErrorResultIsNotPass() {
        mockIssueAndAudit(QualityAuditLogDO.RESULT_ERROR, 0L, 20L);
        QualityWorkOrderServiceImpl.RecheckJudge judge = service.judgeRecheck(
                order(8L), successInstance(99L, RECHECK_START),
                submission(RECHECK_START.minusMinutes(10)));
        assertThat(judge.recheckStatus()).isEqualTo(QualityWorkOrderSubmissionDO.RECHECK_WAITING);
        assertThat(judge.comparable()).isFalse();
    }

    @Test
    void t17PartialSamplePassDoesNotCloseAggregate() {
        mockIssueAndAudit(QualityAuditLogDO.RESULT_PASS, 50L, 0L);
        QualityWorkOrderServiceImpl.RecheckJudge judge = service.judgeRecheck(
                order(8L), successInstance(99L, RECHECK_START),
                submission(RECHECK_START.minusMinutes(10)));
        assertThat(judge.recheckStatus()).isEqualTo(QualityWorkOrderSubmissionDO.RECHECK_WAITING);
        assertThat(judge.comparable()).isFalse();
        assertThat(judge.checks().getFirst()).containsEntry("reasonCode", "COVERAGE_UNPROVEN");
    }

    @Test
    void t19StaleUpstreamWaitsInsteadOfAccepting() {
        fixtureDatasourceType = "ODS";
        mockIssueAndAudit(QualityAuditLogDO.RESULT_PASS, 1000L, 0L);
        QualityWorkOrderServiceImpl.RecheckJudge judge = service.judgeRecheck(
                order(8L), successInstance(99L, RECHECK_START), submission(RECHECK_START.minusHours(1)));
        assertThat(judge.recheckStatus()).isEqualTo(QualityWorkOrderSubmissionDO.RECHECK_WAITING);
        assertThat(judge.error()).contains("等待更新");
        assertThat(judge.checks().getFirst()).containsEntry("reasonCode", "DATA_ARRIVAL_UNPROVEN");
    }

    @Test
    void t20RecheckStartedBeforeSubmissionIsNotCloseable() {
        LocalDateTime submitted = LocalDateTime.now();
        QualityWorkOrderServiceImpl.RecheckJudge judge = service.judgeRecheck(
                order(8L), successInstance(99L, submitted.minusMinutes(5)), submission(submitted));
        assertThat(judge.recheckStatus()).isEqualTo(QualityWorkOrderSubmissionDO.RECHECK_WAITING);
        assertThat(judge.comparable()).isFalse();
        assertThat(judge.error()).contains("早于当前提交");
    }

    @Test
    void t21WithdrawnSubmissionIsIgnoredByEvaluate() {
        QualityWorkOrderSubmissionDO stale = submission(LocalDateTime.now().minusMinutes(1));
        stale.setId(11L);
        stale.setWorkOrderId(8L);
        stale.setInstanceId(99L);
        stale.setStatus(QualityWorkOrderSubmissionDO.STATUS_ACTIVE);
        QualityWorkOrderRecheckDO attempt = runningAttempt();
        when(recheckMapper.selectRunning(anyInt())).thenReturn(List.of(attempt));
        when(recheckMapper.selectById(71L)).thenReturn(attempt);
        when(submissionMapper.selectById(11L)).thenReturn(stale);
        QualityWorkOrderDO current = order(8L);
        current.setCurrentSubmissionId(12L);
        when(workOrderMapper.selectByIdForUpdate(8L)).thenReturn(current);
        service.evaluateRechecks();
        verify(submissionMapper, never()).updateById(any(QualityWorkOrderSubmissionDO.class));
        verify(submissionMapper, never()).update(any(), any());
        assertThat(attempt.getStatus()).isEqualTo(QualityWorkOrderRecheckDO.STATUS_OBSOLETE);
    }

    @Test
    void t23RawIssuesRequireIndependentPermission() {
        QualityWorkOrderDO order = order(8L);
        when(workOrderMapper.selectById(8L)).thenReturn(order);
        when(guard.requireUserId()).thenReturn(9L);
        org.mockito.Mockito.doThrow(new ServiceException(QUALITY_WORK_ORDER_RAW_FORBIDDEN))
                .when(guard).assertRaw(order, 9L);
        assertThatThrownBy(() -> service.getRawIssues(8L))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(QUALITY_WORK_ORDER_RAW_FORBIDDEN.getCode());
    }

    @Test
    void t25SelfConfirmIsRejected() {
        QualityWorkOrderDO order = order(8L);
        order.setOrderStatus(QualityWorkOrderDO.STATUS_PENDING_CONFIRMATION);
        order.setCurrentSubmissionId(11L);
        order.setLockVersion(1);
        when(guard.requireUserId()).thenReturn(2L);
        org.mockito.Mockito.doNothing().when(guard).assertConfirm(order, 2L);
        when(workOrderMapper.selectById(8L)).thenReturn(order);
        when(workOrderMapper.selectByIdForUpdate(8L)).thenReturn(order);
        when(actionMapper.selectByRequest(8L, "r1")).thenReturn(null);
        QualityWorkOrderSubmissionDO submission = submission(LocalDateTime.now());
        submission.setId(11L);
        submission.setWorkOrderId(8L);
        submission.setSubmittedBy(2L);
        submission.setStatus(QualityWorkOrderSubmissionDO.STATUS_ACTIVE);
        when(submissionMapper.selectById(11L)).thenReturn(submission);

        QualityWorkOrderCommandReqVO req = new QualityWorkOrderCommandReqVO();
        req.setId(8L);
        req.setExpectedVersion(1);
        req.setRequestId("r1");
        req.setConfirmAction("VERIFY_PASS");
        req.setSubmissionId(11L);
        assertThatThrownBy(() -> service.confirm(req))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(QUALITY_WORK_ORDER_SELF_CONFIRM.getCode());
    }

    @Test
    void t26ExpiredExceptionKeepsOriginalCloseAndOpensFollowUp() {
        QualityWorkOrderSubmissionDO submission = submission(LocalDateTime.now().minusDays(10));
        submission.setId(11L);
        submission.setWorkOrderId(8L);
        submission.setSubmissionType(QualityWorkOrderSubmissionDO.TYPE_EXCEPTION);
        submission.setStatus(QualityWorkOrderSubmissionDO.STATUS_ACTIVE);
        submission.setExceptionExpireAt(LocalDateTime.now().minusDays(1));
        QualityWorkOrderDO closed = order(8L);
        closed.setOrderStatus(QualityWorkOrderDO.STATUS_PROCESSED);
        closed.setCloseType(QualityWorkOrderDO.CLOSE_EXCEPTION);
        closed.setOwnerUserId(4L);
        closed.setCurrentSubmissionId(11L);
        when(transactionManager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        when(exceptionExpiryScanService.reserve(any())).thenReturn(List.of(submission));
        when(submissionMapper.selectById(11L)).thenReturn(submission);
        when(workOrderMapper.selectByIdForUpdate(8L)).thenReturn(closed);
        when(actionMapper.selectListByWorkOrderId(8L)).thenReturn(List.of());
        var expiryAudit = new java.util.concurrent.atomic.AtomicReference<QualityWorkOrderActionDO>();
        when(actionMapper.insert(any(QualityWorkOrderActionDO.class))).thenAnswer(call -> {
            QualityWorkOrderActionDO action = call.getArgument(0); action.setId(13L); expiryAudit.set(action); return 1;
        });
        when(actionMapper.update(any(), any())).thenAnswer(call -> {
            QualityWorkOrderActionDO action = expiryAudit.get();
            action.setFollowUpWorkOrderId(12L); action.setExpiryResultVersion(1);
            action.setExpiryIssueHash(DigestUtil.sha256Hex("expiry-issues-v1:1"));
            action.setExpiryIssueCount(1L); action.setExpiryReceiverUserId(4L); return 1;
        });
        when(actionMapper.selectByRequest(8L, "expire-11")).thenAnswer(call -> expiryAudit.get());
        when(workOrderMapper.insert(any(QualityWorkOrderDO.class))).thenAnswer(call -> {
            ((QualityWorkOrderDO) call.getArgument(0)).setId(12L); return 1;
        });
        when(workOrderIssueMapper.selectListByWorkOrderId(8L)).thenReturn(List.of(
                QualityWorkOrderIssueDO.builder().issueId(1L).build()));
        when(workOrderIssueMapper.selectListByWorkOrderId(12L)).thenReturn(List.of(
                QualityWorkOrderIssueDO.builder().workOrderId(12L).issueId(1L).build()));
        when(workOrderIssueMapper.insertBatch(any())).thenReturn(true);
        when(notifyMapper.selectCount(any())).thenReturn(1L);
        when(notifyMapper.selectDedup(any(), any(), any(), any())).thenReturn(null);

        service.expireExceptions(LocalDateTime.now());

        verify(actionMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (QualityWorkOrderActionDO action) -> "EXCEPTION_EXPIRED".equals(action.getActionType())));
        verify(workOrderMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (QualityWorkOrderDO created) -> "EXCEPTION_EXPIRED".equals(created.getSourceType())
                        && QualityWorkOrderDO.STATUS_PENDING.equals(created.getOrderStatus())));
        assertThat(closed.getCloseType()).isEqualTo(QualityWorkOrderDO.CLOSE_EXCEPTION);
        verify(actionMapper).update(org.mockito.ArgumentMatchers.isNull(), any());
        assertThat(expiryAudit.get().getFollowUpWorkOrderId()).isEqualTo(12L);
    }

    @Test
    void t27ExtendKeepsHistoricalOverdue() {
        QualityWorkOrderDO order = order(8L);
        order.setOrderStatus(QualityWorkOrderDO.STATUS_IN_PROGRESS);
        order.setLockVersion(3);
        order.setDeadline(LocalDateTime.now().minusDays(2));
        order.setEverOverdue(0);
        when(guard.requireUserId()).thenReturn(4L);
        org.mockito.Mockito.doNothing().when(guard).assertHandle(order, 4L);
        when(workOrderMapper.selectByIdForUpdate(8L)).thenReturn(order);
        when(actionMapper.selectByRequest(8L, "ext-1")).thenReturn(null);
        when(workOrderMapper.update(any(), any())).thenReturn(1);
        when(workOrderMapper.selectById(8L)).thenReturn(order);
        when(workOrderIssueMapper.selectListByWorkOrderId(8L)).thenReturn(List.of());

        QualityWorkOrderCommandReqVO req = new QualityWorkOrderCommandReqVO();
        req.setId(8L);
        req.setExpectedVersion(3);
        req.setRequestId("ext-1");
        req.setNewDeadline(LocalDateTime.now().plusDays(3));
        req.setDeadlineBasis("书面延期");
        service.extendDeadline(req);
        assertThat(order.getEverOverdue()).isEqualTo(1);
    }

    @Test
    void t28UnassignedCannotBeClaimed() {
        QualityWorkOrderDO order = order(8L);
        order.setOrderStatus(QualityWorkOrderDO.STATUS_PENDING);
        order.setResponsibilityStatus(QualityWorkOrderDO.RESP_UNASSIGNED);
        order.setOwnerUserId(null);
        order.setLockVersion(0);
        when(guard.requireUserId()).thenReturn(4L);
        when(workOrderMapper.selectById(8L)).thenReturn(order);
        org.mockito.Mockito.doThrow(new ServiceException(QUALITY_WORK_ORDER_UNASSIGNED))
                .when(guard).assertHandle(order, 4L);

        QualityWorkOrderCommandReqVO req = new QualityWorkOrderCommandReqVO();
        req.setId(8L);
        req.setExpectedVersion(0);
        req.setRequestId("c1");
        assertThatThrownBy(() -> service.claim(req))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(QUALITY_WORK_ORDER_UNASSIGNED.getCode());
    }

    @Test
    void t29RawIssuesKeepEvidenceTimeSeparateFromLiveNote() {
        QualityWorkOrderDO order = order(8L);
        when(workOrderMapper.selectById(8L)).thenReturn(order);
        when(guard.requireUserId()).thenReturn(4L);
        when(workOrderIssueMapper.selectListByWorkOrderId(8L)).thenReturn(List.of(
                QualityWorkOrderIssueDO.builder().issueId(1L).build()));
        when(issueDataMapper.selectById(1L)).thenReturn(QualityIssueDataDO.builder()
                .id(1L).checkTime(LocalDateTime.of(2026, 1, 2, 3, 4))
                .locationSql("SELECT 1").issueCount(3L).build());
        Map<String, Object> raw = service.getRawIssues(8L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) raw.get("issues");
        assertThat(issues.getFirst().get("evidenceTime")).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4));
        assertThat(issues.getFirst().get("liveValueNote").toString()).contains("实时回源");
    }

    @Test
    void t18SourceFixDoesNotRequireRepairOrder() {
        QualityWorkOrderDO current = inProgressOrder();
        stubMutate(current);
        when(submissionMapper.selectListByWorkOrderId(8L)).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            QualityWorkOrderSubmissionDO created = invocation.getArgument(0);
            created.setId(21L);
            return 1;
        }).when(submissionMapper).insert(any(QualityWorkOrderSubmissionDO.class));

        QualityWorkOrderCommandReqVO req = command("src-1");
        req.setSubmissionType(QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX);
        req.setContent("源端已按口径整改");
        service.submitResolution(req);

        verify(submissionMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (QualityWorkOrderSubmissionDO submission) ->
                        QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX.equals(submission.getSubmissionType())
                                && submission.getRepairOrderId() == null
                                && "wo:8:sub:1".equals(submission.getRequestKey())));
        verify(repairOrderMapper, never()).selectById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void etlInlineSourceFixSubmissionStopsAtPendingRecheckAndCannotClose() {
        QualityWorkOrderDO current = inProgressOrder();
        current.setSourceType("ETL_INLINE");
        current.setTaskId(null);
        stubMutate(current);
        when(submissionMapper.selectListByWorkOrderId(8L)).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            QualityWorkOrderSubmissionDO created = invocation.getArgument(0);
            created.setId(21L);
            return 1;
        }).when(submissionMapper).insert(any(QualityWorkOrderSubmissionDO.class));

        QualityWorkOrderCommandReqVO submit = command("etl-source-fix");
        submit.setSubmissionType(QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX);
        submit.setContent("已修复 ETL 输入数据");
        QualityWorkOrderRespVO response = service.submitResolution(submit);
        assertThat(response.getOrderStatus()).isEqualTo(QualityWorkOrderDO.STATUS_PENDING_RECHECK);
        verify(submissionMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (QualityWorkOrderSubmissionDO saved) -> "wo:8:sub:1".equals(saved.getRequestKey())
                        && QualityWorkOrderSubmissionDO.RECHECK_QUEUED.equals(saved.getRecheckStatus())));

        QualityWorkOrderCommandReqVO recheck = command("etl-recheck");
        assertThatThrownBy(() -> service.recheck(recheck)).extracting("code")
                .isEqualTo(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE.getCode());
        QualityWorkOrderCommandReqVO confirm = command("etl-confirm");
        assertThatThrownBy(() -> service.confirm(confirm)).extracting("code")
                .isEqualTo(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE.getCode());
        verify(qualityRunService, never()).submitIdempotent(any(), any(), any(), any());
    }

    @Test
    void etlReplayDescriptionRejectsDifferentSourceFixSubmitterBeforeReadingEvidence() {
        QualityWorkOrderDO current = inProgressOrder();
        current.setSourceType("ETL_INLINE");
        current.setOrderStatus(QualityWorkOrderDO.STATUS_PENDING_RECHECK);
        current.setCurrentSubmissionId(21L);
        when(workOrderMapper.selectById(8L)).thenReturn(current);
        when(submissionMapper.selectById(21L)).thenReturn(QualityWorkOrderSubmissionDO.builder()
                .id(21L).workOrderId(8L).status(QualityWorkOrderSubmissionDO.STATUS_ACTIVE)
                .submissionType(QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX).submittedBy(9L).build());
        QualityEtlWorkOrderReplayDescribeReqDTO request = new QualityEtlWorkOrderReplayDescribeReqDTO();
        request.setExecutionUserId(4L);
        request.setWorkOrderId(8L);
        request.setSubmissionId(21L);

        assertThatThrownBy(() -> service.describeEtlWorkOrderReplay(request))
                .extracting("code").isEqualTo(QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE.getCode());
        verify(workOrderEtlReportMapper, never()).selectByWorkOrderId(any());
        verify(etlBatchRunMapper, never()).selectByInlineReportId(any());
    }

    @Test
    void etlReplayJudgeRequiresEveryFullPopulationTargetAuditToPass() {
        InlineQualityReportDO source = InlineQualityReportDO.builder().id(11L).sourceType("ETL_INLINE")
                .runId("original-run").nodeId("quality").batchId("original-quality-batch")
                .totalCount(3L).passCount(2L).warnCount(0L).rejectCount(1L)
                .identityFieldsJson("[\"id\"]").identityHash("identity-hash").build();
        InlineQualityReportDO replay = InlineQualityReportDO.builder().id(12L).sourceType("ETL_INLINE")
                .runId("replay-run").nodeId("quality").batchId("replay-quality-batch").executionUserId(4L)
                .totalCount(3L).passCount(3L).warnCount(0L).rejectCount(0L)
                .identityFieldsJson("[\"id\"]").identityHash("identity-hash").build();
        QualityEtlBatchRunDO original = QualityEtlBatchRunDO.builder().id(50L).targetBatchId(500L)
                .recordId(60L).qualityTaskId(7L).runId("original-run").identityFieldsJson("[\"id\"]")
                .identityHash("identity-hash").physicalBatchValue("a".repeat(64)).build();
        QualityEtlBatchRunDO replacement = QualityEtlBatchRunDO.builder().id(70L).targetBatchId(700L)
                .recordId(61L).replayOfBatchId(500L).replayOfRecordId(60L).workOrderSubmissionId(22L)
                .inlineReportId(12L).qualityTaskId(7L).checkObjectId(4L).instanceId(101L)
                .qualityNodeId("quality").qualityBatchId("replay-quality-batch").runId("replay-run")
                .physicalBatchValue("a".repeat(64)).rowCount(3L).admittedRowCount(3L)
                .identityFieldsJson("[\"id\"]").identityHash("identity-hash").build();
        QualityTaskExecutor.GateDefinition definition = new QualityTaskExecutor.GateDefinition();
        definition.setObjects(List.of(QualityCheckObjectDO.builder().id(4L).build()));
        definition.setRules(List.of(QualityRuleDO.builder().id(8L).build()));
        QualityTaskInstanceDO instance = QualityTaskInstanceDO.builder().id(101L).taskId(7L)
                .executionUserId(4L).runType("ETL_TARGET_BATCH").instanceStatus(QualityTaskInstanceDO.STATUS_SUCCESS)
                .definitionSnapshot(JsonUtils.toJsonString(definition)).build();
        QualityAuditLogDO audit = QualityAuditLogDO.builder().id(201L).instanceId(101L).taskId(7L).ruleId(8L)
                .checkObjectId(4L).totalCount(3L).failCount(0L).resultType(QualityAuditLogDO.RESULT_PASS).build();
        Map<String, Object> pair = Map.of("rootTargetBatchId", 500L, "rootRecordId", 60L,
                "parentTargetBatchId", 500L, "parentRecordId", 60L,
                "targetBatchRunId", 70L, "targetBatchId", 700L, "instanceId", 101L);
        QualityWorkOrderRecheckDO attempt = QualityWorkOrderRecheckDO.builder().instanceId(101L)
                .evidenceJson(JsonUtils.toJsonString(Map.of("evidenceType", "ETL_TARGET_REPLAY",
                        "sourceReportId", 11L, "replayReportId", 12L, "submissionId", 22L,
                        "targetRuns", List.of(pair)))).build();
        when(inlineQualityReportMapper.selectById(11L)).thenReturn(source);
        when(inlineQualityReportMapper.selectById(12L)).thenReturn(replay);
        when(etlBatchRunMapper.selectByInlineReportId(11L)).thenReturn(List.of(original));
        when(etlBatchRunMapper.selectByReplayOfBatchId(500L)).thenReturn(List.of(replacement));
        when(etlBatchRunMapper.selectByReplayOfBatchId(700L)).thenReturn(List.of());
        when(submissionMapper.selectById(22L)).thenReturn(QualityWorkOrderSubmissionDO.builder().id(22L)
                .workOrderId(8L).submissionType(QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX).build());
        when(etlBatchRunMapper.selectById(70L)).thenReturn(replacement);
        when(instanceMapper.selectById(101L)).thenReturn(instance);
        when(auditLogMapper.selectList(any())).thenReturn(List.of(audit));
        QualityWorkOrderDO order = new QualityWorkOrderDO(); order.setId(8L); order.setSourceType("ETL_INLINE");
        QualityWorkOrderSubmissionDO submission = QualityWorkOrderSubmissionDO.builder().id(22L).build();

        QualityWorkOrderServiceImpl.RecheckJudge passing = ReflectionTestUtils.invokeMethod(service,
                "judgeEtlReplay", order, instance, submission, attempt);
        assertThat(passing.recheckStatus()).isEqualTo(QualityWorkOrderSubmissionDO.RECHECK_PASS);
        assertThat(passing.comparable()).isTrue();
        audit.setResultType(QualityAuditLogDO.RESULT_FAIL);
        QualityWorkOrderServiceImpl.RecheckJudge failing = ReflectionTestUtils.invokeMethod(service,
                "judgeEtlReplay", order, instance, submission, attempt);
        assertThat(failing.recheckStatus()).isEqualTo(QualityWorkOrderSubmissionDO.RECHECK_FAIL);
        assertThat(failing.comparable()).isTrue();
    }

    @Test
    void etlReplayDescriptionFollowsFailedReplacementToCurrentHead() {
        InlineQualityReportDO source = InlineQualityReportDO.builder().id(31L).sourceType("ETL_INLINE")
                .runId("root-run").totalCount(3L).identityFieldsJson("[\"id\"]")
                .identityHash("population").build();
        QualityEtlBatchRunDO root = QualityEtlBatchRunDO.builder().targetBatchId(500L).recordId(60L)
                .qualityTaskId(7L).runId("root-run").physicalBatchValue("a".repeat(64))
                .rowCount(3L).admittedRowCount(3L).identityFieldsJson("[\"id\"]")
                .identityHash("population").build();
        QualityEtlBatchRunDO failedAttempt = QualityEtlBatchRunDO.builder().targetBatchId(700L).recordId(61L)
                .replayOfBatchId(500L).replayOfRecordId(60L).workOrderSubmissionId(22L).inlineReportId(32L)
                .qualityTaskId(7L).runId("retry-one").qualityNodeId("q").qualityBatchId("qb1")
                .physicalBatchValue("a".repeat(64)).rowCount(3L).admittedRowCount(3L)
                .identityFieldsJson("[\"id\"]").identityHash("population").build();
        QualityEtlBatchRunDO retry = QualityEtlBatchRunDO.builder().targetBatchId(800L).recordId(62L)
                .replayOfBatchId(700L).replayOfRecordId(61L).workOrderSubmissionId(23L).inlineReportId(33L)
                .qualityTaskId(7L).runId("retry-two").qualityNodeId("q").qualityBatchId("qb2")
                .physicalBatchValue("a".repeat(64)).rowCount(3L).admittedRowCount(3L)
                .identityFieldsJson("[\"id\"]").identityHash("population").build();
        when(etlBatchRunMapper.selectByInlineReportId(31L)).thenReturn(List.of(root));
        when(etlBatchRunMapper.selectByReplayOfBatchId(500L)).thenReturn(List.of(failedAttempt));
        when(etlBatchRunMapper.selectByReplayOfBatchId(700L)).thenReturn(List.of(retry));
        when(etlBatchRunMapper.selectByReplayOfBatchId(800L)).thenReturn(List.of());
        when(submissionMapper.selectById(22L)).thenReturn(QualityWorkOrderSubmissionDO.builder().id(22L)
                .workOrderId(8L).submissionType(QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX).build());
        when(submissionMapper.selectById(23L)).thenReturn(QualityWorkOrderSubmissionDO.builder().id(23L)
                .workOrderId(8L).submissionType(QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX).build());
        when(inlineQualityReportMapper.selectById(32L)).thenReturn(InlineQualityReportDO.builder().id(32L)
                .sourceType("ETL_INLINE").runId("retry-one").nodeId("q").batchId("qb1")
                .totalCount(3L).identityFieldsJson("[\"id\"]").identityHash("population").build());
        when(inlineQualityReportMapper.selectById(33L)).thenReturn(InlineQualityReportDO.builder().id(33L)
                .sourceType("ETL_INLINE").runId("retry-two").nodeId("q").batchId("qb2")
                .totalCount(3L).identityFieldsJson("[\"id\"]").identityHash("population").build());
        QualityWorkOrderDO order = new QualityWorkOrderDO(); order.setId(8L);

        @SuppressWarnings("unchecked")
        List<?> resolved = ReflectionTestUtils.invokeMethod(service, "resolveEtlTargetLineages", order, source);
        assertThat(resolved).hasSize(1);
        Object lineage = resolved.getFirst();
        assertThat(ReflectionTestUtils.getField(lineage, "root")).isSameAs(root);
        assertThat(ReflectionTestUtils.getField(lineage, "head")).isSameAs(retry);
    }

    @Test
    void etlAllowedActionsExposeConfirmationOnlyForReplayPassState() {
        QualityWorkOrderDO order = new QualityWorkOrderDO();
        order.setSourceType("ETL_INLINE");
        order.setOrderStatus(QualityWorkOrderDO.STATUS_PENDING_CONFIRMATION);
        order.setConfirmationPendingType(QualityWorkOrderDO.CONFIRM_RECHECK);
        assertThat(ReflectionTestUtils.<List<String>>invokeMethod(service, "allowedActions", order))
                .contains("CONFIRM");
        order.setConfirmationPendingType(QualityWorkOrderDO.CONFIRM_EXPLAIN);
        assertThat(ReflectionTestUtils.<List<String>>invokeMethod(service, "allowedActions", order))
                .doesNotContain("CONFIRM");
    }

    @Test
    void t22StaleVersionIsRejected() {
        QualityWorkOrderDO current = inProgressOrder();
        current.setLockVersion(2);
        when(guard.requireUserId()).thenReturn(4L);
        when(workOrderMapper.selectById(8L)).thenReturn(current);
        when(workOrderMapper.selectByIdForUpdate(8L)).thenReturn(current);
        when(actionMapper.selectByRequest(8L, "stale-1")).thenReturn(null);
        QualityWorkOrderCommandReqVO req = command("stale-1");
        req.setExpectedVersion(1);
        req.setSubmissionType(QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX);
        assertThatThrownBy(() -> service.submitResolution(req))
                .extracting("code").isEqualTo(QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode());
        verify(workOrderMapper, never()).update(any(), any());
    }

    @Test
    void t22CasMissKeepsSameWorkOrderChain() {
        QualityWorkOrderDO current = inProgressOrder();
        stubMutate(current);
        when(workOrderMapper.update(any(), any())).thenReturn(0);
        QualityWorkOrderCommandReqVO req = command("cas-0");
        req.setSubmissionType(QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX);
        req.setContent("源端已改");
        assertThatThrownBy(() -> service.submitResolution(req))
                .extracting("code").isEqualTo(QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode());
        verify(workOrderMapper, never()).insert(any(QualityWorkOrderDO.class));
    }

    @Test
    void t22DuplicateRequestIsIdempotent() {
        QualityWorkOrderDO current = inProgressOrder();
        QualityWorkOrderCommandReqVO req = command("dup-1");
        req.setSubmissionType(QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX);
        req.setContent("同一请求");
        String hash = DigestUtil.sha256Hex(JsonUtils.toJsonString(req));
        when(guard.requireUserId()).thenReturn(4L);
        when(workOrderMapper.selectByIdForUpdate(8L)).thenReturn(current);
        when(actionMapper.selectByRequest(8L, "dup-1")).thenReturn(QualityWorkOrderActionDO.builder()
                .requestId("dup-1").payloadHash(hash).actorUserId(4L).actionType("SUBMIT").build());
        when(workOrderMapper.selectById(8L)).thenReturn(current);
        when(workOrderIssueMapper.selectListByWorkOrderId(8L)).thenReturn(List.of());
        when(adminUserApi.getUserList(any())).thenReturn(CommonResult.success(List.of()));
        service.submitResolution(req);
        verify(workOrderMapper, never()).update(any(), any());
        verify(submissionMapper, never()).insert(any(QualityWorkOrderSubmissionDO.class));
    }

    @Test
    void t22OldOwnerSubmitIsRejectedAfterTransfer() {
        QualityWorkOrderDO current = inProgressOrder();
        current.setOwnerUserId(9L);
        when(guard.requireUserId()).thenReturn(4L);
        when(workOrderMapper.selectById(8L)).thenReturn(current);
        org.mockito.Mockito.doThrow(new ServiceException(QUALITY_WORK_ORDER_NOT_EXISTS))
                .when(guard).assertHandle(current, 4L);
        QualityWorkOrderCommandReqVO req = command("old-owner");
        req.setSubmissionType(QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX);
        assertThatThrownBy(() -> service.submitResolution(req))
                .extracting("code").isEqualTo(QUALITY_WORK_ORDER_NOT_EXISTS.getCode());
        verify(submissionMapper, never()).insert(any(QualityWorkOrderSubmissionDO.class));
    }

    @Test
    void t24ForeignIssueIdsCannotBeBound() {
        QualityWorkOrderDO current = inProgressOrder();
        stubMutate(current);
        when(issueDataMapper.selectById(99L)).thenReturn(QualityIssueDataDO.builder().id(99L).build());
        when(workOrderIssueMapper.selectListByWorkOrderId(8L)).thenReturn(List.of(
                QualityWorkOrderIssueDO.builder().issueId(1L).build()));
        QualityWorkOrderCommandReqVO req = command("issue-99");
        req.setSubmissionType(QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX);
        req.setIssueIds(List.of(99L));
        assertThatThrownBy(() -> service.submitResolution(req))
                .extracting("code").isEqualTo(QUALITY_WORK_ORDER_ISSUE_SCOPE_INVALID.getCode());
    }

    @Test
    void t24MissingIssueCannotBeAttachedOnCreate() {
        when(issueDataMapper.selectById(99L)).thenReturn(null);
        cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderSaveReqVO req =
                new cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderSaveReqVO();
        req.setOrderName("越权问题");
        req.setIssueIds(List.of(99L));
        assertThatThrownBy(() -> service.createWorkOrder(req))
                .extracting("code").isEqualTo(QUALITY_ISSUE_NOT_EXISTS.getCode());
        verify(workOrderMapper, never()).insert(any(QualityWorkOrderDO.class));
    }

    @Test
    void t40FailedNotifyIsRetriedWithoutDroppingTodo() {
        QualityWorkOrderNotifyDO pending = QualityWorkOrderNotifyDO.builder()
                .id(1L).workOrderId(8L).eventType("OVERDUE").receiverUserId(4L)
                .timeSlice("2026-09-16:OVERDUE").payloadSummary("工单")
                .sendStatus(QualityWorkOrderNotifyDO.STATUS_FAILED)
                .retryCount(1).nextRetryTime(LocalDateTime.now().minusMinutes(1))
                .build();
        pending.setClaimToken("claim-token");
        when(workOrderMapper.selectList(any())).thenReturn(List.of());
        when(notifyMapper.claimRetryable( eq(30), anyLong())).thenReturn(List.of(pending));
        when(notifyMessageSendApi.sendSingleMessageToAdmin(any())).thenReturn(CommonResult.success(100L));
        when(notifyMapper.finishClaim( eq(1L), eq("claim-token"),
                eq(QualityWorkOrderNotifyDO.STATUS_SENT), anyLong(), isNull())).thenReturn(1);
        when(exceptionExpiryScanService.reserve(any())).thenReturn(List.of());
        service.dispatchDueNotifies();
        verify(notifyMapper).finishClaim( eq(1L), eq("claim-token"),
                eq(QualityWorkOrderNotifyDO.STATUS_SENT), anyLong(), isNull());
    }

    private void mockIssueAndAudit(String result, long total, long fail) {
        when(workOrderIssueMapper.selectListByWorkOrderId(8L)).thenReturn(List.of(
                QualityWorkOrderIssueDO.builder().issueId(1L).build()));
        when(issueDataMapper.selectById(1L)).thenReturn(QualityIssueDataDO.builder()
                .id(1L).taskId(3L).instanceId(98L).auditLogId(100L).ruleId(2L).objectId(3L)
                .tableName("orders").checkField("amount").statScope("ALL").issueCount(20L).build());
        when(instanceMapper.selectById(98L)).thenReturn(successInstance(98L, RECHECK_START.minusHours(2)));
        when(auditLogMapper.selectById(100L)).thenReturn(fixtureAudit(100L, 98L, "FAIL", 1000, 20, RECHECK_START.minusHours(2)));
        when(auditLogMapper.selectList(any())).thenReturn(List.of(fixtureAudit(101L, 99L, result, total, fail, RECHECK_START)));
    }

    private QualityAuditLogDO fixtureAudit(long id, long instance, String result, long total, long fail, LocalDateTime start) {
        var audit = QualityAuditLogDO.builder().id(id).instanceId(instance).taskId(3L).ruleId(2L).checkObjectId(3L)
                .tableName("orders").checkField("amount").resultType(result).totalCount(total).failCount(fail)
                .checkTime(start.plusSeconds(1)).resultSql("SELECT COUNT(*), COUNT(*) FILTER (WHERE amount < 10) FROM orders").build();
        var connection = new cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver.ConnectionInfo();
        connection.setDatabaseId(4L); connection.setConfigurationRevision("revision"); connection.setUsageType(1);
        connection.setSchema("public");
        connection.setProductType(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL);
        var read = QualityReadEvidence.capture(connection,
                QualityCheckObjectDO.builder().datasourceType(fixtureDatasourceType).build(), audit.getResultSql(),
                true, start.plusSeconds(2), start.plusSeconds(3));
        read.setSchemaVersion(QualityReadEvidence.RELATION_SCHEMA_VERSION);
        read.setRelationFingerprint("a".repeat(64));
        audit.setReadEvidence(JsonUtils.toJsonString(read));
        return audit;
    }

    private String fixtureDefinition() {
        var definition = new QualityTaskExecutor.GateDefinition();
        definition.setTask(QualityTaskDO.builder().id(3L).build());
        definition.setObjects(List.of(QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L)
                .datasourceType(fixtureDatasourceType).tableName("orders").build()));
        definition.setRules(List.of(QualityRuleDO.builder().id(2L).ruleType("RANGE").status(0).checkLevel("FIELD")
                .strategyKey("NUMERIC_RANGE_VALIDATION").paramSchema("{\"column\":\"amount\",\"minValue\":10}").build()));
        return JsonUtils.toJsonString(definition);
    }

    @Test
    void newRecheckMustNotReuseSubmissionRunKey() {
        QualityWorkOrderDO current = inProgressOrder();
        current.setOrderStatus(QualityWorkOrderDO.STATUS_PENDING_RECHECK);
        current.setCurrentSubmissionId(11L);
        stubMutate(current);
        QualityWorkOrderSubmissionDO submitted = submission(LocalDateTime.now().minusMinutes(10));
        submitted.setRequestKey("wo:8:sub:1");
        submitted.setInstanceId(90L);
        submitted.setRecheckStatus(QualityWorkOrderSubmissionDO.RECHECK_WAITING);
        when(submissionMapper.selectById(11L)).thenReturn(submitted);
        when(qualityRunService.submitIdempotent(any(), any(), any(), any())).thenReturn(99L);
        when(recheckMapper.nextAttemptNo(11L)).thenReturn(2);

        service.recheck(command("recheck-again"));

        verify(qualityRunService).submitIdempotent(org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq("wo:8:sub:11:attempt:2"),
                org.mockito.ArgumentMatchers.eq(4L), org.mockito.ArgumentMatchers.eq("WORK_ORDER_RECHECK"));
        verify(recheckMapper).insert(org.mockito.ArgumentMatchers.argThat((QualityWorkOrderRecheckDO receipt) ->
                receipt.getAttemptNo() == 2 && receipt.getInstanceId() == 99L
                        && receipt.getRequestId().equals("recheck-again")));
    }

    @Test
    void replayMustRejectDifferentActorEvenWithSamePayload() {
        QualityWorkOrderDO current = inProgressOrder();
        stubMutate(current);
        QualityWorkOrderCommandReqVO req = command("other-actor");
        when(actionMapper.selectByRequest(8L, "other-actor")).thenReturn(QualityWorkOrderActionDO.builder()
                .actorUserId(9L).actionType("SUBMIT")
                .payloadHash(DigestUtil.sha256Hex(JsonUtils.toJsonString(req))).build());

        assertThatThrownBy(() -> service.submitResolution(req)).extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants
                        .QUALITY_WORK_ORDER_REQUEST_CONFLICT.getCode());
        verify(workOrderMapper, never()).update(any(), any());
    }

    @Test
    void replayMustRevalidateCurrentHandlingScope() {
        QualityWorkOrderDO current = inProgressOrder();
        stubMutate(current);
        QualityWorkOrderCommandReqVO req = command("revoked");
        org.mockito.Mockito.lenient().when(actionMapper.selectByRequest(8L, "revoked"))
                .thenReturn(QualityWorkOrderActionDO.builder().actorUserId(4L).actionType("SUBMIT")
                        .payloadHash(DigestUtil.sha256Hex(JsonUtils.toJsonString(req))).build());
        org.mockito.Mockito.doThrow(new ServiceException(QUALITY_WORK_ORDER_NOT_EXISTS))
                .when(guard).assertHandle(current, 4L);

        assertThatThrownBy(() -> service.submitResolution(req)).extracting("code")
                .isEqualTo(QUALITY_WORK_ORDER_NOT_EXISTS.getCode());
    }

    private QualityWorkOrderDO order(Long id) {
        return QualityWorkOrderDO.builder().id(id).taskId(3L)
                .orderStatus(QualityWorkOrderDO.STATUS_RECHECKING)
                .lockVersion(0).build();
    }

    @Test
    void rejectsUnknownSubmissionTypeBeforeWriting() {
        stubMutate(inProgressOrder());
        var req = command("unknown-type");
        req.setSubmissionType("OTHER");
        req.setContent("说明");
        assertThatThrownBy(() -> service.submitResolution(req)).isInstanceOf(ServiceException.class);
        verify(submissionMapper, never()).insert(any(QualityWorkOrderSubmissionDO.class));
    }

    @Test
    void explanationRequiresContent() {
        stubMutate(inProgressOrder());
        var req = command("empty-explain");
        req.setSubmissionType("EXPLAIN");
        req.setContent("  ");
        assertThatThrownBy(() -> service.submitResolution(req)).isInstanceOf(ServiceException.class);
        verify(submissionMapper, never()).insert(any(QualityWorkOrderSubmissionDO.class));
    }

    @Test
    void exceptionRequiresScopeFutureExpiryAndBasis() {
        stubMutate(inProgressOrder());
        var req = command("exception-invalid");
        req.setSubmissionType("EXCEPTION");
        req.setContent("书面例外依据");
        req.setExceptionScope("仅测试机构");
        assertThatThrownBy(() -> service.submitResolution(req)).isInstanceOf(ServiceException.class);
        req.setExceptionExpireAt(LocalDateTime.now().minusDays(1));
        assertThatThrownBy(() -> service.submitResolution(req)).isInstanceOf(ServiceException.class);
        req.setExceptionExpireAt(LocalDateTime.now().plusDays(1));
        req.setExceptionScope(" ");
        assertThatThrownBy(() -> service.submitResolution(req)).isInstanceOf(ServiceException.class);
        req.setExceptionScope("仅测试机构");
        req.setContent(null);
        assertThatThrownBy(() -> service.submitResolution(req)).isInstanceOf(ServiceException.class);
        verify(submissionMapper, never()).insert(any(QualityWorkOrderSubmissionDO.class));
    }

    @Test
    void manualOrderAllowsExplanationAndExceptionWithoutPretendingQualityPassed() {
        for (String type : List.of("EXPLAIN", "EXCEPTION")) {
            var current = inProgressOrder();
            current.setTaskId(null);
            stubMutate(current);
            var req = command("manual-" + type);
            req.setSubmissionType(type);
            req.setContent("书面说明或例外依据");
            if ("EXCEPTION".equals(type)) {
                req.setExceptionScope("机构甲");
                req.setExceptionExpireAt(LocalDateTime.now().plusDays(1));
            }
            var result = service.submitResolution(req);
            assertThat(result.getOrderStatus()).isEqualTo("PENDING_CONFIRMATION");
            assertThat(result.getConfirmationPendingType()).isEqualTo(type);
            assertThat(result.getCloseType()).isNull();
        }
        verifyNoRun();
    }

    @Test
    void platformRepairRequiresApprovedSuccessOnAnIssueObjectInThisWorkOrder() {
        var current = inProgressOrder();
        stubMutate(current);
        var req = command("platform");
        req.setSubmissionType("PLATFORM_REPAIR");
        req.setContent("已执行修复");
        req.setRepairOrderId(31L);
        assertThatThrownBy(() -> service.submitResolution(req)).isInstanceOf(ServiceException.class);
        var repair = QualityRepairOrderDO.builder().id(31L).objectId(5L)
                .approveStatus(QualityRepairOrderDO.APPROVE_PASSED).execStatus("RUNNING").build();
        when(repairOrderMapper.selectById(31L)).thenReturn(repair);
        assertThatThrownBy(() -> service.submitResolution(req)).isInstanceOf(ServiceException.class);
        repair.setExecStatus(QualityRepairOrderDO.EXEC_SUCCESS);
        when(workOrderIssueMapper.selectListByWorkOrderId(8L)).thenReturn(List.of(
                QualityWorkOrderIssueDO.builder().issueId(91L).build()));
        var issue = QualityIssueDataDO.builder().id(91L).objectId(6L).build();
        when(issueDataMapper.selectById(91L)).thenReturn(issue);
        assertThatThrownBy(() -> service.submitResolution(req)).isInstanceOf(ServiceException.class);
        issue.setObjectId(5L);
        repair.setApproveStatus("REJECTED");
        assertThatThrownBy(() -> service.submitResolution(req)).isInstanceOf(ServiceException.class);
        repair.setApproveStatus(QualityRepairOrderDO.APPROVE_PASSED);
        service.submitResolution(req);
        assertThat(current.getOrderStatus()).isEqualTo("PENDING_RECHECK");
        verify(submissionMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (QualityWorkOrderSubmissionDO sub) -> sub.getRepairOrderId().equals(31L)));
    }

    @Test
    void returnRequiresReasonAndRecordsItWithoutClosing() {
        var current = pendingConfirmation("EXPLAIN");
        var req = command("return");
        req.setConfirmAction("RETURN");
        req.setSubmissionId(11L);
        req.setReason("  ");
        assertThatThrownBy(() -> service.confirm(req)).isInstanceOf(ServiceException.class);
        assertThat(current.getOrderStatus()).isEqualTo("PENDING_CONFIRMATION");
        req.setReason("请补充证明材料");
        service.confirm(req);
        assertThat(current.getOrderStatus()).isEqualTo("IN_PROGRESS");
        assertThat(current.getCloseType()).isNull();
        verify(actionMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (QualityWorkOrderActionDO action) -> "请补充证明材料".equals(action.getReason())));
    }

    @Test
    void staleActiveSubmissionCannotBeConfirmed() {
        var current = pendingConfirmation("EXPLAIN");
        current.setCurrentSubmissionId(12L);
        var req = command("stale-submission");
        req.setSubmissionId(11L);
        req.setConfirmAction("ACCEPT_EXPLAIN");
        assertThatThrownBy(() -> service.confirm(req)).isInstanceOf(ServiceException.class);
        verify(workOrderMapper, never()).update(any(), any());
    }

    @Test
    void exceptionThatExpiredWhileWaitingCannotBeAccepted() {
        pendingConfirmation("EXCEPTION");
        var sub = submission(LocalDateTime.now().minusDays(2));
        sub.setSubmittedBy(9L);
        sub.setContent("例外依据");
        sub.setSubmissionType("EXCEPTION");
        sub.setExceptionScope("机构甲");
        sub.setExceptionExpireAt(LocalDateTime.now().minusMinutes(1));
        when(submissionMapper.selectById(11L)).thenReturn(sub);
        var req = command("expired-confirm");
        req.setConfirmAction("ACCEPT_EXCEPTION");
        assertThatThrownBy(() -> service.confirm(req)).isInstanceOf(ServiceException.class);
        verify(workOrderMapper, never()).update(any(), any());
    }

    @Test
    void explainAndExceptionAreDifferentCloseResultsAndExposeTheirEvidence() {
        for (String type : List.of("EXPLAIN", "EXCEPTION")) {
            pendingConfirmation(type);
            var req = command("accept-" + type);
            req.setConfirmAction("ACCEPT_" + type);
            var result = service.confirm(req);
            assertThat(result.getCloseType()).isEqualTo("EXPLAIN".equals(type)
                    ? QualityWorkOrderDO.CLOSE_EXPLAINED : QualityWorkOrderDO.CLOSE_EXCEPTION);
            assertThat(result.getCurrentSubmission().getExceptionScope()).isEqualTo("机构甲");
            assertThat(result.getCurrentSubmission().getExceptionExpireAt()).isNotNull();
        }
        verifyNoRun();
    }

    @Test
    void returnRequiresBackendPermissionEvenWithAnOtherwiseValidCommand() {
        pendingConfirmation("EXPLAIN");
        when(guard.has(4L, QualityWorkOrderGuard.PERM_CONFIRM)).thenReturn(false);
        var req = command("unauthorized-return");
        req.setConfirmAction("RETURN");
        req.setReason("补充材料");
        assertThatThrownBy(() -> service.confirm(req)).isInstanceOf(ServiceException.class);
        verify(workOrderMapper, never()).selectByIdForUpdate(any());
        verify(workOrderMapper, never()).update(any(), any());
    }

    private QualityWorkOrderDO pendingConfirmation(String type) {
        var current = inProgressOrder();
        current.setOrderStatus("PENDING_CONFIRMATION");
        current.setConfirmationPendingType(type);
        current.setCurrentSubmissionId(11L);
        stubMutate(current);
        var sub = submission(LocalDateTime.now());
        sub.setSubmissionType(type);
        sub.setSubmittedBy(9L);
        sub.setContent("书面依据");
        sub.setExceptionScope("机构甲");
        sub.setExceptionExpireAt(LocalDateTime.now().plusDays(1));
        org.mockito.Mockito.lenient().when(submissionMapper.selectById(11L)).thenReturn(sub);
        return current;
    }

    private void verifyNoRun() {
        org.mockito.Mockito.verifyNoInteractions(qualityRunService);
    }

    @Test
    void resolutionWireContractPreservesLongIdsAndUsesEpochMilliseconds() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().addModule(
                new cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        var sub = new cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderRespVO.QualityWorkOrderSubmissionRespVO();
        sub.setId(Long.MAX_VALUE); sub.setRepairOrderId(Long.MAX_VALUE);
        sub.setExceptionScope("机构甲"); sub.setExceptionExpireAt(LocalDateTime.of(2099, 1, 1, 10, 0));
        var json = mapper.readTree(mapper.writeValueAsBytes(sub));
        assertThat(json.path("id").asString()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(json.path("repairOrderId").asString()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(json.path("exceptionExpireAt").isNumber()).isTrue();
        assertThat(json.path("exceptionScope").asString()).isEqualTo("机构甲");
        var command = mapper.readValue("{\"id\":\"9223372036854775807\",\"repairOrderId\":\"9223372036854775807\","
                + "\"exceptionExpireAt\":" + json.path("exceptionExpireAt").asLong() + "}", QualityWorkOrderCommandReqVO.class);
        assertThat(command.getId()).isEqualTo(Long.MAX_VALUE);
        assertThat(command.getRepairOrderId()).isEqualTo(Long.MAX_VALUE);
        assertThat(command.getExceptionExpireAt()).isEqualTo(sub.getExceptionExpireAt());
    }

    private QualityWorkOrderDO inProgressOrder() {
        QualityWorkOrderDO current = order(8L);
        current.setOrderStatus(QualityWorkOrderDO.STATUS_IN_PROGRESS);
        current.setLockVersion(1);
        current.setTaskId(3L);
        current.setOwnerUserId(4L);
        return current;
    }

    private QualityWorkOrderCommandReqVO command(String requestId) {
        QualityWorkOrderCommandReqVO req = new QualityWorkOrderCommandReqVO();
        req.setId(8L);
        req.setExpectedVersion(1);
        req.setRequestId(requestId);
        return req;
    }

    private void stubMutate(QualityWorkOrderDO current) {
        when(guard.requireUserId()).thenReturn(4L);
        org.mockito.Mockito.lenient().doNothing().when(guard).assertHandle(current, 4L);
        org.mockito.Mockito.lenient().when(workOrderMapper.selectByIdForUpdate(8L)).thenReturn(current);
        org.mockito.Mockito.lenient().when(actionMapper.selectByRequest(any(), any())).thenReturn(null);
        org.mockito.Mockito.lenient().when(workOrderMapper.update(any(), any())).thenReturn(1);
        org.mockito.Mockito.lenient().when(workOrderMapper.selectById(8L)).thenReturn(current);
        org.mockito.Mockito.lenient().when(workOrderIssueMapper.selectListByWorkOrderId(8L)).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(submissionMapper.selectListByWorkOrderId(8L)).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(adminUserApi.getUserList(any())).thenReturn(CommonResult.success(List.of()));
        org.mockito.Mockito.lenient().when(submissionMapper.selectById(any())).thenReturn(null);
    }

    private QualityTaskInstanceDO successInstance(Long id, LocalDateTime start) {
        return QualityTaskInstanceDO.builder().id(id).taskId(3L).definitionSnapshot(fixtureDefinition())
                .instanceStatus(QualityTaskInstanceDO.STATUS_SUCCESS)
                .startTime(start).endTime(start.plusMinutes(1)).build();
    }

    private QualityWorkOrderSubmissionDO submission(LocalDateTime at) {
        return QualityWorkOrderSubmissionDO.builder()
                .id(11L).workOrderId(8L).submittedAt(at)
                .status(QualityWorkOrderSubmissionDO.STATUS_ACTIVE).build();
    }

    private QualityWorkOrderRecheckDO runningAttempt() {
        return QualityWorkOrderRecheckDO.builder().id(71L).workOrderId(8L).submissionId(11L)
                .instanceId(99L).attemptNo(1).status(QualityWorkOrderSubmissionDO.RECHECK_RUNNING).build();
    }

    @Test
    void staleAttemptCannotOverwriteNewInstanceOnSameSubmission() {
        QualityWorkOrderDO current = order(8L);
        current.setCurrentSubmissionId(11L);
        when(workOrderMapper.selectByIdForUpdate(8L)).thenReturn(current);
        QualityWorkOrderRecheckDO old = runningAttempt();
        when(recheckMapper.selectRunning(20)).thenReturn(List.of(old));
        when(recheckMapper.selectById(71L)).thenReturn(old);
        QualityWorkOrderSubmissionDO submitted = submission(LocalDateTime.now());
        submitted.setInstanceId(100L);
        when(submissionMapper.selectById(11L)).thenReturn(submitted);

        service.evaluateRechecks();

        assertThat(old.getStatus()).isEqualTo(QualityWorkOrderRecheckDO.STATUS_OBSOLETE);
        verify(instanceMapper, never()).selectById(any());
        verify(workOrderMapper, never()).update(any(), any());
        verify(submissionMapper, never()).update(any(), any());
    }

    @Test
    void anotherCommandCannotLaunchWhileCurrentAttemptRuns() {
        QualityWorkOrderDO current = inProgressOrder();
        current.setOrderStatus(QualityWorkOrderDO.STATUS_RECHECKING);
        stubMutate(current);
        assertThatThrownBy(() -> service.recheck(command("concurrent"))).extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants
                        .QUALITY_WORK_ORDER_STATUS_INVALID.getCode());
        org.mockito.Mockito.verifyNoInteractions(qualityRunService, recheckMapper);
    }

    @Test
    void replayMustRejectDifferentAction() {
        QualityWorkOrderDO current = inProgressOrder();
        stubMutate(current);
        QualityWorkOrderCommandReqVO req = command("wrong-action");
        when(actionMapper.selectByRequest(8L, "wrong-action")).thenReturn(QualityWorkOrderActionDO.builder()
                .actorUserId(4L).actionType("WITHDRAW")
                .payloadHash(DigestUtil.sha256Hex(JsonUtils.toJsonString(req))).build());
        assertThatThrownBy(() -> service.submitResolution(req)).extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants
                        .QUALITY_WORK_ORDER_REQUEST_CONFLICT.getCode());
    }

    @Test
    void revokedCommandPermissionFailsBeforeLockAndReceiptRead() {
        QualityWorkOrderDO current = inProgressOrder();
        stubMutate(current);
        when(guard.has(4L, QualityWorkOrderGuard.PERM_PROCESS)).thenReturn(false);
        assertThatThrownBy(() -> service.submitResolution(command("revoked-permission"))).extracting("code")
                .isEqualTo(QUALITY_WORK_ORDER_NOT_EXISTS.getCode());
        verify(workOrderMapper, never()).selectByIdForUpdate(any());
        verify(actionMapper, never()).selectByRequest(any(), any());
    }

    @Test
    void transferBetweenPermissionCheckAndLockRequiresRetry() {
        QualityWorkOrderDO observed = inProgressOrder();
        stubMutate(observed);
        QualityWorkOrderDO locked = inProgressOrder();
        locked.setOwnerUserId(9L);
        when(workOrderMapper.selectByIdForUpdate(8L)).thenReturn(locked);
        assertThatThrownBy(() -> service.submitResolution(command("ownership-race"))).extracting("code")
                .isEqualTo(QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode());
        verify(actionMapper, never()).selectByRequest(any(), any());
    }

    @Test
    void missingInstanceFinishesAttemptAsErrorInsteadOfWaitingForever() {
        QualityWorkOrderDO current = order(8L);
        current.setCurrentSubmissionId(11L);
        when(workOrderMapper.selectByIdForUpdate(8L)).thenReturn(current);
        QualityWorkOrderRecheckDO attempt = runningAttempt();
        when(recheckMapper.selectRunning(20)).thenReturn(List.of(attempt));
        when(recheckMapper.selectById(71L)).thenReturn(attempt);
        QualityWorkOrderSubmissionDO submitted = submission(LocalDateTime.now());
        submitted.setInstanceId(99L);
        when(submissionMapper.selectById(11L)).thenReturn(submitted);

        service.evaluateRechecks();

        assertThat(attempt.getStatus()).isEqualTo(QualityWorkOrderSubmissionDO.RECHECK_ERROR);
        assertThat(current.getOrderStatus()).isEqualTo(QualityWorkOrderDO.STATUS_PENDING_RECHECK);
        assertThat(attempt.getFinishedAt()).isNotNull();
    }

}
