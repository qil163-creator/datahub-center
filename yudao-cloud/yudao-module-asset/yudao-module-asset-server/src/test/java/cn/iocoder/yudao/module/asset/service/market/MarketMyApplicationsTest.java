package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCheckProcessDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketMyApplicationsTest {
    @Test
    void pageForcesOwnScopeEvenWhenClientRequestsProviderRowsAndKeepsAllStatuses() {
        var mapper = mock(MarketResourceApplyMapper.class);
        var guard = mock(MarketObjectGuard.class);
        var display = mock(MarketDisplayHelper.class);
        var service = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(service, "applyMapper", mapper);
        ReflectionTestUtils.setField(service, "objectGuard", guard);
        ReflectionTestUtils.setField(service, "displayHelper", display);
        when(guard.requireUserId()).thenReturn(9L);
        var rows = List.of(0, 1, 2, 3, 4, 5, 6, 8).stream().map(status ->
                MarketResourceApplyDO.builder().id(status.longValue() + 1).assetId(15L)
                        .applyUserId(9L).status(status).build()).toList();
        var req = new MarketApplyPageReqVO();
        req.setIfSelf(0); req.setIfOwner(1);
        when(mapper.selectPage(req, 9L, true, false, List.of())).thenReturn(new PageResult<>(rows, 8L));
        var asset = new DataAssetDO(); asset.setId(15L); asset.setAssetName("资产名称");
        when(display.assets(anyCollection())).thenReturn(Map.of(15L, asset));

        var page = service.getMyApplications(req);
        assertThat(page.getTotal()).isEqualTo(8L);
        assertThat(page.getList()).extracting("status").containsExactly(0, 1, 2, 3, 4, 5, 6, 8);
        assertThat(page.getList()).extracting("assetName").containsOnly("资产名称");
        verify(mapper).selectPage(req, 9L, true, false, List.of());
        verify(guard).requireUserId();
        verifyNoMoreInteractions(guard);
    }

    @Test
    void ownedMapperPredicateAlwaysExcludesOtherUsersAndMigratedWorkOrders() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MarketResourceApplyDO.class);
        var mapper = mock(MarketResourceApplyMapper.class, CALLS_REAL_METHODS);
        doAnswer(call -> {
            Wrapper<?> wrapper = call.getArgument(1);
            assertThat(wrapper.getSqlSegment()).contains("source_work_order_id IS NULL", "apply_user_id =")
                    .doesNotContain(" OR ", "status =");
            assertThat(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper)
                    .getParamNameValuePairs().values()).contains(9L);
            return PageResult.empty();
        }).when(mapper).selectPage(any(MarketApplyPageReqVO.class), any(Wrapper.class));
        mapper.selectPage(new MarketApplyPageReqVO(), 9L, true, false, List.of());
    }

    @Test
    void detailRejectsAnotherUserAndMigratedWorkOrderEvenForProvider() {
        var mapper = mock(MarketResourceApplyMapper.class);
        var guard = mock(MarketObjectGuard.class);
        var service = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(service, "applyMapper", mapper);
        ReflectionTestUtils.setField(service, "objectGuard", guard);
        when(guard.requireUserId()).thenReturn(9L);
        when(mapper.selectById(7L)).thenReturn(MarketResourceApplyDO.builder().id(7L).applyUserId(10L).build());
        assertThatThrownBy(() -> service.getMyApplication(7L)).isInstanceOf(ServiceException.class);
        when(mapper.selectById(8L)).thenReturn(MarketResourceApplyDO.builder().id(8L).applyUserId(9L)
                .sourceWorkOrderId(99L).build());
        assertThatThrownBy(() -> service.getMyApplication(8L)).isInstanceOf(ServiceException.class);
        verify(guard, never()).assertCanViewApply(any(), any());
    }

    @Test
    void detailReturnsOwnRejectedApplicationAndItsReviewHistory() {
        var mapper = mock(MarketResourceApplyMapper.class);
        var guard = mock(MarketObjectGuard.class);
        var display = mock(MarketDisplayHelper.class);
        var processes = mock(MarketCheckProcessMapper.class);
        var service = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(service, "applyMapper", mapper);
        ReflectionTestUtils.setField(service, "objectGuard", guard);
        ReflectionTestUtils.setField(service, "displayHelper", display);
        ReflectionTestUtils.setField(service, "processMapper", processes);
        ReflectionTestUtils.setField(service, "assetMapper", mock(DataAssetMapper.class));
        ReflectionTestUtils.setField(service, "deliveryOperationMapper", mock(MarketDeliveryOperationMapper.class));
        ReflectionTestUtils.setField(service, "deliveryService", mock(MarketDeliveryService.class));
        ReflectionTestUtils.setField(service, "accessService", mock(MarketAccessService.class));
        var apply = MarketResourceApplyDO.builder().id(7L).applyUserId(9L).assetId(15L)
                .status(3).checkNote("材料不足").build();
        when(mapper.selectById(7L)).thenReturn(apply);
        when(guard.requireUserId()).thenReturn(9L);
        when(display.assets(anyCollection())).thenReturn(Map.of());
        when(processes.selectListByApplyId(7L)).thenReturn(List.of(MarketCheckProcessDO.builder()
                .resourceApplyId(7L).note("材料不足").build()));
        var result = service.getMyApplication(7L);
        assertThat(result.getStatus()).isEqualTo(3);
        assertThat(result.getCheckNote()).isEqualTo("材料不足");
        assertThat(result.getProcesses()).extracting("note").containsExactly("材料不足");
        verify(guard).assertCanViewApply(apply, null);
    }
}
