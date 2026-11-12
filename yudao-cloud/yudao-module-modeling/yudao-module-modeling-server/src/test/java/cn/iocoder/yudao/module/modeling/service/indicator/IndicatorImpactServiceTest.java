package cn.iocoder.yudao.module.modeling.service.indicator;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorBoardSaveReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorImpactRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorQueryReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorBoardDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorBoardMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper;
import cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApi;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyIndicatorApiRefRespDTO;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 口径变更影响分析与看板：派生/复合逐层向上、看板组件、数据服务接口、数据服务不可用降级、看板只允许本人修改。
 */
class IndicatorImpactServiceTest {

    private IndicatorModelMapper indicators;
    private IndicatorBoardMapper boards;
    private SupplyDataApiApi supply;
    private IndicatorImpactService impact;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        indicators = mock(IndicatorModelMapper.class);
        boards = mock(IndicatorBoardMapper.class);
        supply = mock(SupplyDataApiApi.class);
        impact = new IndicatorImpactService();
        ReflectionTestUtils.setField(impact, "indicatorMapper", indicators);
        ReflectionTestUtils.setField(impact, "boardMapper", boards);
        ReflectionTestUtils.setField(impact, "supplyDataApiApi", supply);
        IndicatorModelDO gmv = indicator(1L, "gmv", "ATOMIC", null, null);
        IndicatorModelDO gmvMonth = indicator(2L, "gmv_month", "DERIVED", 1L, null);
        IndicatorModelDO ratio = indicator(3L, "gmv_ratio", "COMPOSITE", null, "{gmv_month} / {order_cnt}");
        IndicatorModelDO growth = indicator(4L, "ratio_growth", "COMPOSITE", null, "{GMV_RATIO} * 100");
        when(indicators.selectById(1L)).thenReturn(gmv);
        when(indicators.selectList(any(SFunction.class), eq("COMPOSITE"))).thenReturn(List.of(ratio, growth));
        when(indicators.selectList(any(SFunction.class), eq(1L))).thenReturn(List.of(gmvMonth));
        when(indicators.selectList(any(SFunction.class), eq(2L))).thenReturn(List.of());
        when(indicators.selectList(any(SFunction.class), eq(3L))).thenReturn(List.of());
        when(indicators.selectList(any(SFunction.class), eq(4L))).thenReturn(List.of());
        IndicatorBoardDO board = new IndicatorBoardDO();
        board.setId(9L);
        board.setName("经营日报");
        board.setWidgetsJson(JsonUtils.toJsonString(List.of(widget("w1", "毛利率", 3L), widget("w2", "订单", 5L))));
        when(boards.selectListByName(null)).thenReturn(List.of(board));
    }

    @Test
    void dependentsAreFollowedUpwardThroughDerivedAndCompositeIndicators() {
        SupplyIndicatorApiRefRespDTO api = new SupplyIndicatorApiRefRespDTO();
        api.setId(70L);
        api.setName("毛利率接口");
        api.setIndicatorIds(List.of(3L));
        when(supply.getIndicatorReferences(anyList())).thenReturn(CommonResult.success(List.of(api)));

        IndicatorImpactRespVO resp = impact.analyze(1L);

        assertThat(resp.getDependents()).extracting(IndicatorImpactRespVO.Item::getCode)
                .containsExactly("gmv_month", "gmv_ratio", "ratio_growth");
        assertThat(resp.getDependents()).extracting(IndicatorImpactRespVO.Item::getDepth).containsExactly(1, 2, 3);
        assertThat(resp.getDependents().get(1).getUpstreamId()).isEqualTo(2L);
        assertThat(resp.getBoards()).singleElement().satisfies(board ->
                assertThat(board.getWidgets()).containsExactly("毛利率"));
        assertThat(resp.getApis()).extracting(IndicatorImpactRespVO.Api::getId).containsExactly(70L);
        assertThat(resp.getApisAvailable()).isTrue();
        assertThat(resp.getTruncated()).isFalse();
    }

    @Test
    void supplyOutageDegradesOnlyTheApiList() {
        when(supply.getIndicatorReferences(anyList())).thenThrow(new IllegalStateException("down"));
        IndicatorImpactRespVO resp = impact.analyze(1L);
        assertThat(resp.getApisAvailable()).isFalse();
        assertThat(resp.getDependents()).hasSize(3);
    }

    @Test
    void unknownIndicatorIsRejectedAndBadFormulasAreIgnored() {
        assertThatThrownBy(() -> impact.analyze(99L)).isInstanceOf(ServiceException.class);
        assertThat(IndicatorImpactService.references("{a} +")).isEmpty();
        assertThat(IndicatorImpactService.references("{A} + {b}")).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void boardsAreOwnerOnlyAndWidgetKeysUnique() {
        IndicatorBoardService service = new IndicatorBoardService();
        ReflectionTestUtils.setField(service, "boardMapper", boards);
        IndicatorBoardDO board = new IndicatorBoardDO();
        board.setId(9L);
        board.setOwnerUserId(1L);
        board.setRevision(0);
        when(boards.selectById(9L)).thenReturn(board);
        IndicatorBoardSaveReqVO req = new IndicatorBoardSaveReqVO();
        req.setId(9L);
        req.setName("看板");
        req.setExpectedRevision(0);
        req.setWidgets(List.of(widget("w1", "a", 1L)));
        try (MockedStatic<SecurityFrameworkUtils> security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(2L);
            assertThatThrownBy(() -> service.update(req)).isInstanceOf(ServiceException.class)
                    .hasMessageContaining("本人");
            assertThatThrownBy(() -> service.delete(9L)).isInstanceOf(ServiceException.class);
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(1L);
            when(boards.updateByRevision(any(), eq(0))).thenReturn(0);
            assertThatThrownBy(() -> service.update(req)).isInstanceOf(ServiceException.class)
                    .hasMessageContaining("其他操作");
        }
        assertThatThrownBy(() -> IndicatorBoardService.validate(List.of(widget("w1", "a", 1L), widget("w1", "b", 2L))))
                .isInstanceOf(ServiceException.class);
    }

    private static IndicatorModelDO indicator(Long id, String code, String type, Long base, String formula) {
        IndicatorModelDO indicator = new IndicatorModelDO();
        indicator.setId(id);
        indicator.setCode(code);
        indicator.setName(code);
        indicator.setIndicatorType(type);
        indicator.setBaseIndicatorId(base);
        indicator.setFormula(formula);
        indicator.setStatus("ACTIVE");
        return indicator;
    }

    private static IndicatorBoardSaveReqVO.Widget widget(String key, String title, Long indicatorId) {
        IndicatorBoardSaveReqVO.Widget widget = new IndicatorBoardSaveReqVO.Widget();
        widget.setKey(key);
        widget.setTitle(title);
        widget.setChartType("TABLE");
        IndicatorQueryReqVO query = new IndicatorQueryReqVO();
        query.setIndicatorIds(List.of(indicatorId));
        widget.setQuery(query);
        return widget;
    }
}
