package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableColumnDO;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskRespDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyStorageMaskReqDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据表下拉取值的敏感判定：按持久化前脱敏口径，敏感或无法判定时拒绝发布
 */
class FillReferenceSensitiveTest {

    private final SafetySecurityApi safetySecurityApi = mock(SafetySecurityApi.class);
    private final FillReferenceOptionLoader loader = new FillReferenceOptionLoader();
    private final FillTableColumnDO column = FillTableColumnDO.builder().fieldCode("owner").cnName("负责人").build();

    FillReferenceSensitiveTest() {
        ReflectionTestUtils.setField(loader, "safetySecurityApi", safetySecurityApi);
    }

    private static SafetyDynamicMaskRespDTO resp(SafetyDynamicMaskRespDTO.MaskedColumn... masked) {
        SafetyDynamicMaskRespDTO resp = new SafetyDynamicMaskRespDTO();
        resp.setRows(List.of());
        resp.setMaskedColumns(new ArrayList<>(List.of(masked)));
        return resp;
    }

    private static SafetyDynamicMaskRespDTO.MaskedColumn masked(String name, String reason, List<String> categories) {
        SafetyDynamicMaskRespDTO.MaskedColumn column = new SafetyDynamicMaskRespDTO.MaskedColumn();
        column.setOutputName(name);
        column.setReason(reason);
        column.setCategoryNames(categories);
        return column;
    }

    @Test
    void nonSensitiveColumnsPassAndEveryValueIsCheckedInBatches() {
        when(safetySecurityApi.maskForStorage(any())).thenReturn(CommonResult.success(resp()));
        List<String> values = IntStream.range(0, 1500).mapToObj(i -> "v" + i).toList();

        assertThatCode(() -> loader.checkNotSensitive(column, 3L, "public", "dim_user", "name", null,
                new FillReferenceOptionLoader.Options(values, Map.of()))).doesNotThrowAnyException();

        ArgumentCaptor<SafetyStorageMaskReqDTO> captor = ArgumentCaptor.forClass(SafetyStorageMaskReqDTO.class);
        verify(safetySecurityApi, times(2)).maskForStorage(captor.capture());
        SafetyStorageMaskReqDTO first = captor.getAllValues().getFirst();
        assertThat(first.getRows()).hasSize(1000);
        assertThat(first.getColumns()).singleElement().satisfies(item -> {
            assertThat(item.getOutputName()).isEqualTo("value");
            assertThat(item.getSources()).singleElement().satisfies(source -> {
                assertThat(source.getDatasourceId()).isEqualTo(3L);
                assertThat(source.getTableName()).isEqualTo("dim_user");
                assertThat(source.getColumnName()).isEqualTo("name");
            });
        });
    }

    @Test
    void sensitiveParentColumnIsRejectedWithItsCategory() {
        when(safetySecurityApi.maskForStorage(any())).thenReturn(CommonResult.success(
                resp(masked("parent", SafetyDynamicMaskRespDTO.REASON_RULE, List.of("身份证号")))));

        assertThatThrownBy(() -> loader.checkNotSensitive(column, 3L, null, "dim_user", "name", "id_card",
                new FillReferenceOptionLoader.Options(List.of("甲", "乙"), Map.of("110", List.of("甲")))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("dim_user.id_card")
                .hasMessageContaining("身份证号");
        ArgumentCaptor<SafetyStorageMaskReqDTO> captor = ArgumentCaptor.forClass(SafetyStorageMaskReqDTO.class);
        verify(safetySecurityApi).maskForStorage(captor.capture());
        // 有上级的“甲”与没有上级的“乙”都参与判定
        assertThat(captor.getValue().getRows()).containsExactly(
                Map.of("value", "甲", "parent", "110"), Map.of("value", "乙"));
    }

    @Test
    void unavailableSecurityServiceFailsClosed() {
        when(safetySecurityApi.maskForStorage(any())).thenThrow(new IllegalStateException("down"));

        assertThatThrownBy(() -> loader.checkNotSensitive(column, 3L, null, "dim_user", "name", null,
                new FillReferenceOptionLoader.Options(List.of("甲"), Map.of())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("数据安全服务暂不可用");
    }

    @Test
    void unclassifiedSensitiveMarkIsExplained() {
        assertThat(FillReferenceOptionLoader.describeSensitive("dim_user", "mobile",
                masked("value", SafetyDynamicMaskRespDTO.REASON_UNCLASSIFIED, List.of())))
                .contains("dim_user.mobile").contains("已被标记为敏感字段");
    }

}
