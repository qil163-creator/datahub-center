package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityExpiryFailureRetryReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityExceptionExpiryFailureDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityExceptionExpiryFailureMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderMapper;
import org.junit.jupiter.api.Test;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_STATUS_INVALID;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_VERSION_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityExceptionExpiryFailureServiceTest {

    private final QualityExceptionExpiryFailureMapper failures = mock(QualityExceptionExpiryFailureMapper.class);
    private final QualityWorkOrderMapper orders = mock(QualityWorkOrderMapper.class);
    private final QualityWorkOrderGuard guard = mock(QualityWorkOrderGuard.class);
    private final QualityExceptionExpiryFailureService service =
            new QualityExceptionExpiryFailureService(failures, orders, guard);
    private final QualityWorkOrderDO order = QualityWorkOrderDO.builder().id(8L).orderName("空值整改").build();

    QualityExceptionExpiryFailureServiceTest() {
        when(guard.requireUserId()).thenReturn(4L);
        when(orders.selectById(8L)).thenReturn(order);
        when(failures.selectById(1L)).thenReturn(QualityExceptionExpiryFailureDO.builder().id(1L).workOrderId(8L)
                .submissionId(11L).status(QualityExceptionExpiryFailureDO.STATUS_EXHAUSTED).attemptCount(5).version(3).build());
    }

    private static QualityExpiryFailureRetryReqVO request(int version) {
        var req = new QualityExpiryFailureRetryReqVO();
        req.setId(1L); req.setExpectedVersion(version); req.setReason("  已修复责任人配置  ");
        return req;
    }

    @Test
    void authorizedRetryUsesReadVersionAndTrimmedReason() {
        when(failures.authorizeRetry(eq(1L), eq(3), eq("已修复责任人配置"), eq(4L), any())).thenReturn(1);

        var result = service.authorizeRetry(request(3));

        assertThat(result.getWorkOrderName()).isEqualTo("空值整改");
        assertThat(result.getMaxAttempts()).isEqualTo(QualityExceptionExpiryFailureDO.MAX_ATTEMPTS);
        verify(guard).assertHandle(order, 4L);
    }

    @Test
    void staleVersionIsRejected() {
        when(failures.authorizeRetry(anyLong(), anyInt(), anyString(), anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.authorizeRetry(request(2)))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode()));
    }

    @Test
    void operatorWithoutHandlePermissionCannotAuthorize() {
        doThrow(exception(QUALITY_WORK_ORDER_STATUS_INVALID)).when(guard).assertHandle(order, 4L);

        assertThatThrownBy(() -> service.authorizeRetry(request(3))).isInstanceOf(ServiceException.class);
        verify(failures, never()).authorizeRetry(anyLong(), anyInt(), anyString(), anyLong(), any());
    }

    @Test
    void safeSummaryKeepsBusinessHintButNeverDriverText() {
        var business = new IllegalStateException("wrap", exception(QUALITY_WORK_ORDER_VERSION_CONFLICT));
        assertThat(QualityExceptionExpiryFailureService.safeSummary(business))
                .contains(String.valueOf(QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode()));
        var driver = new IllegalStateException("wrap", new java.sql.SQLException("ERROR: fixture-only-sensitive-detail"));
        assertThat(QualityExceptionExpiryFailureService.safeSummary(driver))
                .isEqualTo("执行异常：SQLException").doesNotContain("fixture-only");
    }
}
