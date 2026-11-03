package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplySaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 复制的已通过申请即使状态字段被误开通，也不能创建本地授权、撤权、到期过程或本地申请人。 */
class MarketSourceApplicationIsolationTest {
    @ParameterizedTest
    @EnumSource(Action.class)
    void originalApplicationLifecycleRefusesCopiedSourceBeforeOwnerOrProviderProcessing(Action action) {
        var source = source();
        var mapper = mock(MarketResourceApplyMapper.class); when(mapper.selectById(7L)).thenReturn(source);
        var guard = mock(MarketObjectGuard.class); var delivery = mock(MarketDeliveryService.class);
        var service = new MarketApplyServiceImpl(); ReflectionTestUtils.setField(service, "applyMapper", mapper);
        ReflectionTestUtils.setField(service, "objectGuard", guard); ReflectionTestUtils.setField(service, "deliveryService", delivery);
        var save = new MarketApplySaveReqVO(); save.setId(7L);
        var check = new MarketCheckReqVO(); check.setId(7L); check.setStatus(2);
        assertThatThrownBy(() -> {
            switch (action) {
                case GET -> service.getApply(7L);
                case UPDATE -> service.updateApply(save);
                case SUBMIT -> service.submitApply(7L);
                case DELETE -> service.deleteApply(7L);
                case CANCEL -> service.cancelApply(7L);
                case CHECK -> service.checkApply(check);
                case AUTH -> service.authApply(7L, 1);
                case REVOKE -> service.revokeApply(7L, "测试");
                case SUPPLEMENT -> service.requestSupplement(7L, "测试");
                case RESUBMIT -> service.resubmitApply(7L);
                case PAUSE -> service.pauseApply(7L, "测试");
                case RESUME -> service.resumeApply(7L);
            }
        }).isInstanceOf(ServiceException.class);
        verify(mapper).selectById(7L); verifyNoMoreInteractions(mapper);
        verifyNoInteractions(guard, delivery);
    }

    @Test
    void expirySkipsEvenAnUnexpectedSourceScanResultWithoutInventingAProcessActorOrRevocation() {
        var mapper = mock(MarketResourceApplyMapper.class); when(mapper.selectExpiredDue(100)).thenReturn(List.of(source()));
        var delivery = mock(MarketDeliveryService.class); var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var service = new MarketApplyServiceImpl(); ReflectionTestUtils.setField(service, "applyMapper", mapper);
        ReflectionTestUtils.setField(service, "deliveryService", delivery); ReflectionTestUtils.setField(service, "transactionManager", transactions);
        assertThat(service.expireDueApplies()).isZero();
        verify(mapper).selectExpiredDue(100); verifyNoMoreInteractions(mapper); verifyNoInteractions(delivery);
    }

    @Test
    void fieldEvidenceEffectivenessAndAllDeliveryActionsRefuseSourceEvenWithForgedAuthFlag() {
        var source = source(); source.setIfAuth(1); source.setSnapshot("{\"frozenColumns\":[\"ID\"]}");
        assertThatThrownBy(() -> MarketFieldScope.effectiveScope(source)).isInstanceOf(ServiceException.class);
        assertThat(new MarketAccessServiceImpl().isEffective(source)).isFalse();
        var delivery = new MarketDeliveryService();
        assertThat(delivery.isConfirmedApply(source)).isFalse();
        assertThat(delivery.currentDeliveryResolution(source, null)).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> delivery.enqueueGrant(source)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> delivery.enqueueApprovedGrant(source, 9L, 100L, "测试")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> delivery.enqueueSuspend(source)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> delivery.enqueueRevoke(source)).isInstanceOf(ServiceException.class);
    }

    private static MarketResourceApplyDO source() {
        return MarketResourceApplyDO.builder().id(7L).assetId(16L).sourceWorkOrderId(1L)
                .applyUserId(null).applyDeptId(100L).status(2).ifAuth(0).build();
    }

    enum Action { GET, UPDATE, SUBMIT, DELETE, CANCEL, CHECK, AUTH, REVOKE, SUPPLEMENT, RESUBMIT, PAUSE, RESUME }
}
