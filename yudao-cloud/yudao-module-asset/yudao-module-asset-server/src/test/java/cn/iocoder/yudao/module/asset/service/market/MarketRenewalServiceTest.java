package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketRenewalReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 续期必须是新的申请并重新审批：原批准的期限与来源不变，重复提交只产生一个续期申请。
 */
class MarketRenewalServiceTest {

    private static final LocalDateTime ORIGINAL_END = LocalDateTime.of(2026, 12, 31, 0, 0);
    private static final LocalDateTime LATER_END = LocalDateTime.of(2027, 6, 30, 0, 0);

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MarketResourceApplyDO.class);
    }

    private MarketApplyServiceImpl service;
    private MarketResourceApplyMapper applyMapper;
    private MarketDeliveryService deliveryService;
    private MarketDisplayHelper displayHelper;

    private static MarketResourceApplyDO approved() {
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setId(7L);
        apply.setAssetId(5L);
        apply.setApiId(3L);
        apply.setAppId(2L);
        apply.setApplyUserId(9L);
        apply.setApplyNo("APP-1");
        apply.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        apply.setIfAuth(MarketResourceApplyDO.AUTH_YES);
        apply.setPaused(0);
        apply.setRevision(3);
        apply.setApplyEndTime(ORIGINAL_END);
        apply.setSnapshot("{\"frozenColumns\":[\"id\"],\"tableName\":\"citizen\"}");
        apply.setDeliveryStatus(MarketDeliveryService.DELIVERY_SUCCESS);
        return apply;
    }

    private static MarketRenewalReqVO renewal(String operationId, LocalDateTime end) {
        MarketRenewalReqVO vo = new MarketRenewalReqVO();
        vo.setId(7L);
        vo.setOperationId(operationId);
        vo.setApplyEndTime(end);
        return vo;
    }

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        service = new MarketApplyServiceImpl();
        applyMapper = mock(MarketResourceApplyMapper.class);
        deliveryService = mock(MarketDeliveryService.class);
        displayHelper = mock(MarketDisplayHelper.class);
        MarketObjectGuard objectGuard = mock(MarketObjectGuard.class);
        ReflectionTestUtils.setField(service, "applyMapper", applyMapper);
        ReflectionTestUtils.setField(service, "deliveryService", deliveryService);
        ReflectionTestUtils.setField(service, "displayHelper", displayHelper);
        ReflectionTestUtils.setField(service, "objectGuard", objectGuard);
        ReflectionTestUtils.setField(service, "processMapper", mock(MarketCheckProcessMapper.class));
        ReflectionTestUtils.setField(service, "auditRecorder", mock(MarketAuditRecorder.class));
        ReflectionTestUtils.setField(service, "notifyHelper", mock(MarketNotifyHelper.class));
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        var assetMapper = mock(DataAssetMapper.class);
        var asset = new DataAssetDO();
        asset.setId(5L);
        asset.setStatus(DataAssetDO.STATUS_PUBLISHED);
        asset.setShareAttr(DataAssetDO.SHARE_UNCONDITIONAL);
        when(assetMapper.selectById(5L)).thenReturn(asset);
        ReflectionTestUtils.setField(service, "assetMapper", assetMapper);
        var calendar = mock(MarketWorkingDayService.class);
        when(calendar.plusWorkingDays(any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> ((LocalDateTime) call.getArgument(0)).plusDays((Integer) call.getArgument(1)));
        ReflectionTestUtils.setField(service, "workingDayService", calendar);
        when(objectGuard.requireUserId()).thenReturn(9L);
        when(applyMapper.selectOwnedForRenewal( 7L, 9L)).thenReturn(approved());
        when(applyMapper.databaseNow()).thenReturn(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(applyMapper.nextApplyNoSeq()).thenReturn(88L);
        when(displayHelper.nextBizNo(anyString(), anyLong())).thenReturn("APP-88");
        doAnswer(call -> {
            ((MarketResourceApplyDO) call.getArgument(0)).setId(99L);
            return 1;
        }).when(applyMapper).insertRenewal(any(MarketResourceApplyDO.class));
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    void renewalCreatesANewPendingApplicationAndLeavesTheOriginalUntouched() {
        var processes = (MarketCheckProcessMapper) ReflectionTestUtils.getField(service, "processMapper");
        when(processes.insert(any(cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCheckProcessDO.class)))
                .thenReturn(1);
        Long id = service.createRenewal(renewal("renew-1", LATER_END));
        assertThat(id).isEqualTo(99L);
        ArgumentCaptor<MarketResourceApplyDO> captor = ArgumentCaptor.forClass(MarketResourceApplyDO.class);
        verify(applyMapper).insertRenewal(captor.capture());
        MarketResourceApplyDO created = captor.getValue();
        assertThat(created.getRenewalOfApplyId()).isEqualTo(7L);
        assertThat(created.getRenewalOperationId()).isEqualTo("renew-1");
        assertThat(created.getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_PENDING);
        assertThat(created.getIfAuth()).isEqualTo(MarketResourceApplyDO.AUTH_NO);
        assertThat(created.getDeliveryStatus()).isEqualTo(MarketDeliveryService.DELIVERY_NONE);
        assertThat(created.getApplyEndTime()).isEqualTo(LATER_END);
        assertThat(created.getRenewalRequestedEndTime()).isEqualTo(LATER_END);
        assertThat(created.getDeleted()).isNull();
        assertThat(created.getRevision()).isEqualTo(1);
        // 复制的是原批准证据，不重新冻结当前元数据。
        assertThat(created.getSnapshot()).isEqualTo(approved().getSnapshot());
        // 原申请既不延长，也不重新投递。
        verify(deliveryService, never()).enqueueGrant(any());
        verify(deliveryService, never()).enqueueRevoke(any());
        verify(deliveryService, never()).enqueueSuspend(any());
        verify(applyMapper, never()).updateById(any(MarketResourceApplyDO.class));
    }

    @Test
    void repeatedSubmissionOfTheSameOperationReturnsTheSameRenewal() {
        MarketResourceApplyDO existing = approved();
        existing.setId(99L);
        existing.setRenewalOfApplyId(7L);
        existing.setRenewalOperationId("renew-1");
        existing.setApplyEndTime(LATER_END);
        existing.setRenewalRequestedEndTime(LATER_END);
        when(applyMapper.selectRenewalByOperation( "renew-1")).thenReturn(existing);
        assertThat(service.createRenewal(renewal("renew-1", LATER_END))).isEqualTo(99L);
        verify(applyMapper, never()).insertRenewal(any(MarketResourceApplyDO.class));
    }

    @Test
    void sameOperationWithDifferentDataIsRejected() {
        MarketResourceApplyDO existing = approved();
        existing.setId(99L);
        existing.setRenewalOfApplyId(7L);
        existing.setRenewalOperationId("renew-1");
        existing.setApplyEndTime(LATER_END);
        existing.setRenewalRequestedEndTime(LATER_END);
        when(applyMapper.selectRenewalByOperation( "renew-1")).thenReturn(existing);
        assertThatThrownBy(() -> service.createRenewal(renewal("renew-1", LATER_END.plusDays(1))))
                .isInstanceOf(ServiceException.class);
        verify(applyMapper, never()).insertRenewal(any(MarketResourceApplyDO.class));
    }

    @Test
    void duplicateConflictResolvesToTheWinningRenewalPayload() {
        MarketResourceApplyDO winner = approved();
        winner.setId(99L);
        winner.setRenewalOfApplyId(7L);
        winner.setRenewalOperationId("renew-1");
        winner.setApplyEndTime(LATER_END);
        winner.setRenewalRequestedEndTime(LATER_END);
        when(applyMapper.selectRenewalByOperation( "renew-1")).thenReturn(null, null, winner);
        doAnswer(call -> {
            throw new DuplicateKeyException("uk_market_renewal_operation");
        }).when(applyMapper).insertRenewal(any(MarketResourceApplyDO.class));
        assertThat(service.createRenewal(renewal("renew-1", LATER_END))).isEqualTo(99L);
    }

    @Test
    void renewalMustExtendBeyondTheCurrentApprovalWindow() {
        assertThatThrownBy(() -> service.createRenewal(renewal("renew-2", ORIGINAL_END)))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.createRenewal(renewal("renew-2", ORIGINAL_END.minusDays(1))))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.createRenewal(renewal("renew-2", null)))
                .isInstanceOf(ServiceException.class);
        verify(applyMapper, never()).insertRenewal(any(MarketResourceApplyDO.class));
    }

    @Test
    void onlyAnEffectiveApprovalCanBeRenewed() {
        MarketResourceApplyDO revoked = approved();
        revoked.setStatus(MarketResourceApplyDO.STATUS_REVOKED);
        when(applyMapper.selectOwnedForRenewal( 7L, 9L)).thenReturn(revoked);
        assertThatThrownBy(() -> service.createRenewal(renewal("renew-3", LATER_END)))
                .isInstanceOf(ServiceException.class);

        MarketResourceApplyDO unauthorized = approved();
        unauthorized.setIfAuth(MarketResourceApplyDO.AUTH_NO);
        when(applyMapper.selectOwnedForRenewal( 7L, 9L)).thenReturn(unauthorized);
        assertThatThrownBy(() -> service.createRenewal(renewal("renew-3", LATER_END)))
                .isInstanceOf(ServiceException.class);
        verify(applyMapper, never()).insertRenewal(any(MarketResourceApplyDO.class));
    }

    @Test
    void renewalRequiresAStableOperationId() {
        assertThatThrownBy(() -> service.createRenewal(renewal(null, LATER_END)))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.createRenewal(renewal("  ", LATER_END)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void legacyRenewEndpointNoLongerExtendsAnApprovalInPlace() {
        assertThatThrownBy(() -> service.renewApply(7L, LATER_END))
                .isInstanceOf(ServiceException.class);
        verify(applyMapper, never()).updateById(any(MarketResourceApplyDO.class));
        verify(deliveryService, never()).enqueueGrant(any());
    }
}
