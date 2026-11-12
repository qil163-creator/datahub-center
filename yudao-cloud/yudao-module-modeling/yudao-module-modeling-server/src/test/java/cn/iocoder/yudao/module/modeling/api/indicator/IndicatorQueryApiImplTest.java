package cn.iocoder.yudao.module.modeling.api.indicator;

import cn.iocoder.yudao.module.modeling.api.indicator.dto.IndicatorQueryApiReqDTO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorQueryRespVO;
import cn.iocoder.yudao.module.modeling.service.indicator.IndicatorQueryMaskSupport;
import cn.iocoder.yudao.module.modeling.service.indicator.IndicatorQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 指标查询 RPC：请求转换、查看主体只在调用范围内生效、结果不含内部口径。
 */
class IndicatorQueryApiImplTest {

    @Test
    void viewerOverrideIsScopedToTheCallAndTheResultIsTrimmed() {
        IndicatorQueryService service = mock(IndicatorQueryService.class);
        AtomicReference<Long> seen = new AtomicReference<>();
        IndicatorQueryRespVO result = new IndicatorQueryRespVO();
        IndicatorQueryRespVO.Column column = new IndicatorQueryRespVO.Column();
        column.setKey("dim_0");
        column.setTitle("地区");
        column.setKind("DIMENSION");
        result.setColumns(List.of(column));
        result.setRows(List.of(Map.of("dim_0", "***")));
        IndicatorQueryRespVO.MaskedColumn masked = new IndicatorQueryRespVO.MaskedColumn();
        masked.setName("dim_0");
        result.setMaskedColumns(List.of(masked));
        when(service.query(any())).thenAnswer(invocation -> {
            seen.set((Long) ReflectionTestUtils.invokeMethod(IndicatorQueryMaskSupport.class, "viewer"));
            return result;
        });
        IndicatorQueryApiImpl impl = new IndicatorQueryApiImpl();
        ReflectionTestUtils.setField(impl, "queryService", service);
        IndicatorQueryApiReqDTO request = new IndicatorQueryApiReqDTO();
        request.setViewerUserId(42L);
        request.setIndicatorIds(List.of(1L));
        request.setStartDate("2026-01-01");
        request.setEndDate("2026-01-31");

        var resp = impl.query(request).getCheckedData();

        assertThat(seen.get()).isEqualTo(42L);
        assertThat((Long) ReflectionTestUtils.invokeMethod(IndicatorQueryMaskSupport.class, "viewer")).isNull();
        assertThat(resp.getMaskedColumns()).containsExactly("dim_0");
        assertThat(resp.getColumns()).singleElement().satisfies(item -> assertThat(item.getTitle()).isEqualTo("地区"));
        assertThat(IndicatorQueryApiImpl.toReq(request).getTimeRange().getEnd()).isEqualTo("2026-01-31");
    }
}
