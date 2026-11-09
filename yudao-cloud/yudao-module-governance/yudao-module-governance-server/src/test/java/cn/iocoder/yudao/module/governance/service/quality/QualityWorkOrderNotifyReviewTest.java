package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyReviewReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyRetryReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderNotifyDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderNotifyReviewDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderNotifyMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderNotifyReviewMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionDefinition;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QualityWorkOrderNotifyReviewTest {

    private final QualityWorkOrderServiceImpl service = new QualityWorkOrderServiceImpl();
    private final QualityWorkOrderNotifyMapper notifyMapper = mock(QualityWorkOrderNotifyMapper.class);
    private final QualityWorkOrderNotifyReviewMapper reviewMapper = mock(QualityWorkOrderNotifyReviewMapper.class);
    private final QualityWorkOrderMapper workOrderMapper = mock(QualityWorkOrderMapper.class);
    private final QualityWorkOrderGuard guard = mock(QualityWorkOrderGuard.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private final QualityWorkOrderNotifyDO notify = QualityWorkOrderNotifyDO.builder()
            .id(51L).workOrderId(8L).sendStatus(QualityWorkOrderNotifyDO.STATUS_UNKNOWN)
            .resolutionStatus(QualityWorkOrderNotifyDO.RESOLUTION_UNRESOLVED).resolutionVersion(0).build();
    private final List<QualityWorkOrderNotifyReviewDO> audits = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        when(guard.requireUserId()).thenReturn(4L);
        var order = QualityWorkOrderDO.builder().id(8L).ownerUserId(4L).build();
        when(workOrderMapper.selectById(8L)).thenReturn(order);
        when(workOrderMapper.selectByIdForUpdate(8L)).thenReturn(order);
        when(notifyMapper.selectForUpdate( 51L)).thenReturn(notify);
        when(notifyMapper.selectById(51L)).thenReturn(notify);
        when(reviewMapper.selectLatestByNotifyId(anyLong(), anyInt())).thenAnswer(invocation ->
                new ArrayList<>(audits.subList(Math.max(0, audits.size() - invocation.<Integer>getArgument(1)), audits.size())));
        when(reviewMapper.selectByRequestId( anyString())).thenAnswer(invocation -> audits.stream()
                .filter(row -> invocation.<String>getArgument(0).equals(row.getRequestId())).findFirst().orElse(null));
        when(reviewMapper.insert(any(QualityWorkOrderNotifyReviewDO.class))).thenAnswer(invocation -> {
            QualityWorkOrderNotifyReviewDO row = invocation.getArgument(0);
            row.setId(100L + audits.size());
            audits.add(row);
            return 1;
        });
        when(reviewMapper.selectById(anyLong())).thenAnswer(invocation -> audits.stream()
                .filter(row -> invocation.<Long>getArgument(0).equals(row.getId())).findFirst().orElse(null));
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        when(notifyMapper.resolveUnknown( anyLong(), anyInt(), anyInt(), anyString(), anyString(),
                nullable(Long.class), anyLong())).thenAnswer(invocation -> {
            notify.setResolutionStatus(invocation.getArgument(3));
            notify.setResolutionVersion(invocation.getArgument(2));
            notify.setResolutionReason(invocation.getArgument(4));
            notify.setOperatorMessageId(invocation.getArgument(5));
            notify.setResolvedBy(invocation.getArgument(6));
            return 1;
        });
        when(notifyMapper.authorizeRetry( anyLong(), anyInt(), anyInt(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    notify.setResolutionStatus(QualityWorkOrderNotifyDO.RESOLUTION_RETRY_AUTHORIZED);
                    notify.setResolutionVersion(invocation.getArgument(2));
                    notify.setResolutionReason(invocation.getArgument(3));
                    notify.setSendStatus(QualityWorkOrderNotifyDO.STATUS_FAILED);
                    return 1;
                });
        ReflectionTestUtils.setField(service, "notifyMapper", notifyMapper);
        ReflectionTestUtils.setField(service, "notifyReviewMapper", reviewMapper);
        ReflectionTestUtils.setField(service, "workOrderMapper", workOrderMapper);
        ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
        assertThat(ReflectionTestUtils.getField(service, "transactionManager")).isSameAs(transactionManager);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void notDeliveredMustBeReviewedThenSeparatelyAuthorizedWithSameDecisionVersion() {
        QualityWorkOrderNotifyReviewReqVO review = new QualityWorkOrderNotifyReviewReqVO();
        review.setId(51L); review.setExpectedResolutionVersion(0); review.setRequestId("manual-check-1");
        review.setOutcome("NOT_DELIVERED"); review.setReason("已核对收件人与站内信列表");

        var unresolved = service.reviewNotify(review);
        assertThat(unresolved.getSendStatus()).isEqualTo("UNKNOWN");
        assertThat(unresolved.getResolutionStatus()).isEqualTo("MANUAL_NOT_DELIVERED");
        assertThat(unresolved.getResolutionVersion()).isEqualTo(1);
        Long decisionId = unresolved.getReviews().get(0).getId();

        QualityWorkOrderNotifyRetryReqVO retry = new QualityWorkOrderNotifyRetryReqVO();
        retry.setId(51L); retry.setDecisionId(decisionId); retry.setExpectedResolutionVersion(1);
        retry.setRequestId("manual-retry-1"); retry.setReason("确认未送达后授权重试");
        var authorized = service.authorizeNotifyRetry(retry);
        assertThat(authorized.getSendStatus()).isEqualTo("FAILED");
        assertThat(authorized.getResolutionStatus()).isEqualTo("RETRY_AUTHORIZED");
        assertThat(authorized.getResolutionVersion()).isEqualTo(2);
        assertThat(authorized.getReviews()).hasSize(2);
        verify(notifyMapper, never()).claimRetryable( anyInt(), anyString(), anyLong());
    }

    @Test
    void exhaustedNotifyEntersTheSameManualReviewAndRetryFlow() {
        // 自动投递 8 次耗尽：自动计数已满，但人工核验未送达后仍可授权重试（授权时自动计数清零）
        notify.setSendStatus(QualityWorkOrderNotifyDO.STATUS_EXHAUSTED);
        notify.setRetryCount(QualityWorkOrderNotifyDO.MAX_AUTO_ATTEMPTS);
        QualityWorkOrderNotifyReviewReqVO review = new QualityWorkOrderNotifyReviewReqVO();
        review.setId(51L); review.setExpectedResolutionVersion(0); review.setRequestId("exhausted-check");
        review.setOutcome("NOT_DELIVERED"); review.setReason("模板已修复，确认之前未送达");
        var reviewed = service.reviewNotify(review);
        assertThat(reviewed.getResolutionStatus()).isEqualTo("MANUAL_NOT_DELIVERED");

        QualityWorkOrderNotifyRetryReqVO retry = new QualityWorkOrderNotifyRetryReqVO();
        retry.setId(51L); retry.setDecisionId(reviewed.getReviews().get(0).getId());
        retry.setExpectedResolutionVersion(1); retry.setRequestId("exhausted-retry");
        retry.setReason("模板修复后授权重投");
        assertThat(service.authorizeNotifyRetry(retry).getResolutionStatus()).isEqualTo("RETRY_AUTHORIZED");
        verify(notifyMapper).authorizeRetry(eq(51L), eq(1), eq(2), anyString(), eq(4L));
    }

    @Test
    void manualDeliveredCannotAuthorizeRetryAndStaleVersionCannotOverride() {
        QualityWorkOrderNotifyReviewReqVO review = new QualityWorkOrderNotifyReviewReqVO();
        review.setId(51L); review.setExpectedResolutionVersion(0); review.setRequestId("manual-check-2");
        review.setOutcome("DELIVERED"); review.setReason("接收人反馈已收到"); review.setOperatorMessageId(77L);
        assertThat(service.reviewNotify(review).getResolutionStatus()).isEqualTo("MANUAL_DELIVERED");

        QualityWorkOrderNotifyRetryReqVO retry = new QualityWorkOrderNotifyRetryReqVO();
        retry.setId(51L); retry.setDecisionId(100L); retry.setExpectedResolutionVersion(1);
        retry.setRequestId("manual-retry-2"); retry.setReason("禁止对人工确认送达记录重发");
        assertThatThrownBy(() -> service.authorizeNotifyRetry(retry)).isInstanceOf(RuntimeException.class);

        var stale = new QualityWorkOrderNotifyReviewReqVO();
        stale.setId(51L); stale.setExpectedResolutionVersion(0); stale.setRequestId("manual-check-stale");
        stale.setOutcome("NOT_DELIVERED"); stale.setReason("旧页面提交");
        assertThatThrownBy(() -> service.reviewNotify(stale)).isInstanceOf(RuntimeException.class);
    }
}
