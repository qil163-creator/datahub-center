package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.modeling.api.indicator.IndicatorQueryApi;
import cn.iocoder.yudao.module.modeling.api.indicator.dto.IndicatorQueryApiReqDTO;
import cn.iocoder.yudao.module.modeling.api.indicator.dto.IndicatorQueryApiRespDTO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 指标接口：配置校验、口径固定、参数映射、调用主体与失败关闭。
 */
class DataApiIndicatorQueryServiceTest {

    private static final String CONFIG = "{\"indicatorIds\":[1,2],\"dimensions\":[\"uid_region\"],\"timeGrain\":\"DAY\","
            + "\"limit\":100,\"startParam\":\"startDate\",\"endParam\":\"endDate\","
            + "\"filters\":[{\"param\":\"region\",\"fieldUid\":\"uid_region\"}]}";
    private static final String PARAMS = "[{\"name\":\"startDate\"},{\"name\":\"endDate\"},{\"name\":\"region\"}]";

    private IndicatorQueryApi api;
    private DataApiIndicatorQueryService service;

    @BeforeEach
    void setUp() {
        api = mock(IndicatorQueryApi.class);
        service = new DataApiIndicatorQueryService();
        ReflectionTestUtils.setField(service, "indicatorQueryApi", api);
    }

    @Test
    void configIsValidatedAndReferencedParamsMustBeDeclared() {
        DataApiIndicatorConfig config = DataApiIndicatorConfig.parse(CONFIG);
        assertThat(config.paramNames()).containsExactly("startDate", "endDate", "region");
        config.requireParamsDeclared(PARAMS);
        assertThatThrownBy(() -> config.requireParamsDeclared("[{\"name\":\"region\"}]"))
                .isInstanceOf(ServiceException.class).hasMessageContaining("startDate");
        assertThatThrownBy(() -> DataApiIndicatorConfig.parse("{\"indicatorIds\":[]}")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> DataApiIndicatorConfig.parse("{\"indicatorIds\":[1],\"timeGrain\":\"HOUR\"}"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> DataApiIndicatorConfig.parse("{\"indicatorIds\":[1],\"startParam\":\"s\"}"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> DataApiIndicatorConfig.parse("not json")).isInstanceOf(ServiceException.class);
    }

    @Test
    void callerParamsOnlyFillTheDeclaredSlotsAndTheViewerIsTheGrantSubject() {
        IndicatorQueryApiRespDTO resp = new IndicatorQueryApiRespDTO();
        resp.setRows(List.of(Map.of("dim_0", "华东", "ind_1", "12.50")));
        resp.setTruncated(true);
        when(api.query(any())).thenReturn(CommonResult.success(resp));

        var result = service.execute(indicator(), Map.of("startDate", "2026-01-01", "endDate", "2026-01-31",
                "region", "华东", "indicatorIds", "99"), 500, 42L);

        ArgumentCaptor<IndicatorQueryApiReqDTO> captor = ArgumentCaptor.forClass(IndicatorQueryApiReqDTO.class);
        verify(api).query(captor.capture());
        IndicatorQueryApiReqDTO request = captor.getValue();
        assertThat(request.getViewerUserId()).isEqualTo(42L);
        assertThat(request.getIndicatorIds()).containsExactly(1L, 2L);
        assertThat(request.getLimit()).isEqualTo(100);
        assertThat(request.getStartDate()).isEqualTo("2026-01-01");
        assertThat(request.getFilters()).singleElement().satisfies(filter -> {
            assertThat(filter.getFieldUid()).isEqualTo("uid_region");
            assertThat(filter.getValue()).isEqualTo("华东");
        });
        assertThat(result.getRows().getFirst().get("ind_1")).isEqualTo("12.50");
        assertThat(result.getHasMore()).isTrue();
    }

    @Test
    void indicatorServiceFailureReturnsNoData() {
        when(api.query(any())).thenThrow(new IllegalStateException("down"));
        assertThatThrownBy(() -> service.execute(indicator(), Map.of(), null, 42L))
                .isInstanceOf(ServiceException.class).hasMessageContaining("指标服务暂不可用");
    }

    @Test
    void contractHashCoversTheIndicatorConfigOnlyForIndicatorApis() {
        DataApiDO first = indicator();
        DataApiDO second = indicator();
        second.setIndicatorConfigJson(CONFIG.replace("[1,2]", "[1,3]"));
        assertThat(DataApiContract.contractHash(first)).isNotEqualTo(DataApiContract.contractHash(second));
        DataApiDO table = new DataApiDO();
        table.setConfigType(DataApiDO.CONFIG_TYPE_TABLE);
        String before = DataApiContract.contractHash(table);
        table.setIndicatorConfigJson(CONFIG);
        assertThat(DataApiContract.contractHash(table)).isEqualTo(before);
        DataApiDO restored = new DataApiDO();
        DataApiContract.applySnapshot(restored, DataApiContract.snapshot(first));
        assertThat(restored.getIndicatorConfigJson()).isEqualTo(CONFIG);
    }

    private static DataApiDO indicator() {
        DataApiDO api = new DataApiDO();
        api.setId(7L);
        api.setConfigType(DataApiDO.CONFIG_TYPE_INDICATOR);
        api.setApiType(DataApiDO.API_TYPE_QUERY);
        api.setIndicatorConfigJson(CONFIG);
        api.setRequestParamsJson(PARAMS);
        return api;
    }
}
