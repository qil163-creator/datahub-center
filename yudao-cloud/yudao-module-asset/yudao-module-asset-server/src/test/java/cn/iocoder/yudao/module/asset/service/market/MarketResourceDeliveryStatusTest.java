package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceCommentMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.DataAssetService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class MarketResourceDeliveryStatusTest {
    @org.junit.jupiter.api.BeforeAll
    static void initializeMetadata() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                MarketResourceApplyDO.class);
    }

    @Test
    void resourceAccessFlagsMustUseOneEffectiveApplicationRead() {
        var service = new MarketResourceServiceImpl();
        var assets = mock(DataAssetService.class);
        var applies = mock(MarketResourceApplyMapper.class);
        var comments = mock(MarketResourceCommentMapper.class);
        var access = mock(MarketAccessService.class);
        var delivery = mock(MarketDeliveryService.class);
        ReflectionTestUtils.setField(service, "dataAssetService", assets);
        ReflectionTestUtils.setField(service, "applyMapper", applies);
        ReflectionTestUtils.setField(service, "commentMapper", comments);
        ReflectionTestUtils.setField(service, "marketAccessService", access);
        ReflectionTestUtils.setField(service, "marketDeliveryService", delivery);
        var asset = new DataAssetRespVO();
        asset.setId(5L);
        when(assets.getPublishedAssetPage(any())).thenReturn(new PageResult<>(List.of(asset), 1L));
        var apply = new MarketResourceApplyDO();
        apply.setId(7L);
        // 批准发生于旧实现的两次独立读取之间：不能拼出“未授权但可使用”。
        when(access.findEffectiveApplies(9L, List.of(5L))).thenReturn(Map.of(), Map.of(5L, apply));
        when(delivery.isConfirmedApply(apply)).thenReturn(true);
        var loginUser = new LoginUser();
        loginUser.setId(9L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
        try {
            var row = service.getPublishedPage(new DataAssetPageReqVO()).getList().getFirst();
            assertThat(row.getAuthorized()).isFalse();
            assertThat(row.getUsable()).isFalse();
            verify(access).findEffectiveApplies(9L, List.of(5L));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void publishedResourceUsesDeliveryLedgerRatherThanStaleApplicationIntent() {
        var service = new MarketResourceServiceImpl();
        var assets = mock(DataAssetService.class);
        ReflectionTestUtils.setField(service, "dataAssetService", assets);
        ReflectionTestUtils.setField(service, "applyMapper", mock(MarketResourceApplyMapper.class));
        ReflectionTestUtils.setField(service, "commentMapper", mock(MarketResourceCommentMapper.class));
        var asset = new DataAssetRespVO();
        asset.setId(5L);
        when(assets.getPublishedAssetPage(any())).thenReturn(new PageResult<>(List.of(asset), 1L));
        var accessService = mock(MarketAccessService.class);
        var deliveryService = mock(MarketDeliveryService.class);
        ReflectionTestUtils.setField(service, "marketAccessService", accessService);
        ReflectionTestUtils.setField(service, "marketDeliveryService", deliveryService);
        var apply = new MarketResourceApplyDO();
        apply.setId(7L);
        apply.setApiId(2L);
        apply.setDeliveryStatus(MarketDeliveryService.DELIVERY_PENDING);
        when(accessService.findEffectiveApplies(9L, List.of(5L))).thenReturn(Map.of(5L, apply));
        when(deliveryService.isConfirmedApply(apply)).thenReturn(true);
        var loginUser = new LoginUser();
        loginUser.setId(9L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
        try {
            var row = service.getPublishedPage(new DataAssetPageReqVO()).getList().getFirst();
            assertThat(row.getAuthorized()).isTrue();
            assertThat(row.getUsable()).isTrue();
            verify(accessService).findEffectiveApplies(9L, List.of(5L));
            verify(deliveryService).isConfirmedApply(apply);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
