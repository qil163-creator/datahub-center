package cn.iocoder.yudao.module.governance.service.profile;

import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskRespDTO;
import cn.iocoder.yudao.module.governance.service.security.SafetyDynamicMaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 落库前脱敏适配：请求形状、掩码结果回填、元数据兜底与异常透传（由执行器失败关闭）。
 */
class SafetyProfileValueMaskerTest {

    private static final String PLACEHOLDER = "******";

    private SafetyDynamicMaskService service;
    private SafetyProfileValueMasker masker;

    @BeforeEach
    void setUp() {
        service = mock(SafetyDynamicMaskService.class);
        masker = new SafetyProfileValueMasker();
        ReflectionTestUtils.setField(masker, "dynamicMaskService", service);
    }

    @Test
    void sendsOneColumnPerFieldWithPhysicalSourceAndOneRowPerValuePosition() {
        when(service.maskForStorage(anyList(), anyList())).thenAnswer(call -> response(call.getArgument(1), Map.of()));
        masker.mask(List.of(
                input("phone", false, Arrays.asList("138", "139", "138", "137")),
                input("status", false, Arrays.asList(null, null))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SafetyDynamicMaskReqDTO.Column>> columns = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> rows = ArgumentCaptor.forClass(List.class);
        verify(service).maskForStorage(columns.capture(), rows.capture());
        assertThat(columns.getValue()).extracting(SafetyDynamicMaskReqDTO.Column::getOutputName)
                .containsExactly("phone", "status");
        SafetyDynamicMaskReqDTO.Source source = columns.getValue().get(0).getSources().get(0);
        assertThat(source.getDatasourceId()).isEqualTo(9L);
        assertThat(source.getSchemaName()).isEqualTo("crm");
        assertThat(source.getTableName()).isEqualTo("customer");
        assertThat(source.getColumnName()).isEqualTo("phone");
        assertThat(rows.getValue()).hasSize(4);
        assertThat(rows.getValue().get(3)).containsEntry("phone", "137").containsEntry("status", null);
    }

    @Test
    void maskedColumnsStorePlaceholderAndOthersKeepRawValues() {
        when(service.maskForStorage(anyList(), anyList()))
                .thenAnswer(call -> response(call.getArgument(1), Map.of("phone", SafetyDynamicMaskRespDTO.REASON_RULE)));
        Map<String, ProfileValueMasker.MaskedValues> result = masker.mask(List.of(
                input("phone", false, Arrays.asList(null, "139", "138")),
                input("status", false, List.of("A", "B"))));

        ProfileValueMasker.MaskedValues phone = result.get("phone");
        assertThat(phone.masked()).isTrue();
        assertThat(phone.reason()).isEqualTo("RULE");
        assertThat(phone.values()).containsExactly(null, PLACEHOLDER, PLACEHOLDER);
        ProfileValueMasker.MaskedValues status = result.get("status");
        assertThat(status.masked()).isFalse();
        assertThat(status.values()).containsExactly("A", "B");
    }

    @Test
    void metadataSensitiveColumnNotMaskedByServiceStoresNoText() {
        when(service.maskForStorage(anyList(), anyList())).thenAnswer(call -> response(call.getArgument(1), Map.of()));
        ProfileValueMasker.MaskedValues result = masker.mask(List.of(input("id_card", true, List.of("a", "b"))))
                .get("id_card");
        assertThat(result.masked()).isTrue();
        assertThat(result.reason()).isEqualTo(ProfileValueMasker.REASON_METADATA);
        assertThat(result.values()).containsOnlyNulls().hasSize(2);
    }

    @Test
    void serviceFailurePropagatesForFailClosedHandling() {
        when(service.maskForStorage(anyList(), anyList())).thenThrow(new IllegalStateException("unresolved"));
        assertThatThrownBy(() -> masker.mask(List.of(input("phone", false, List.of("1")))))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 模拟 maskForStorage：被脱敏列的非空值替换为掩码，其余原样复制。
     */
    private static SafetyDynamicMaskRespDTO response(List<Map<String, Object>> rows, Map<String, String> masked) {
        SafetyDynamicMaskRespDTO resp = new SafetyDynamicMaskRespDTO();
        List<Map<String, Object>> copies = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            masked.keySet().forEach(name -> copy.computeIfPresent(name, (key, value) -> PLACEHOLDER));
            copies.add(copy);
        }
        resp.setRows(copies);
        masked.forEach((name, reason) -> {
            SafetyDynamicMaskRespDTO.MaskedColumn column = new SafetyDynamicMaskRespDTO.MaskedColumn();
            column.setOutputName(name);
            column.setReason(reason);
            resp.getMaskedColumns().add(column);
        });
        return resp;
    }

    private static ProfileValueMasker.ColumnValues input(String column, boolean sensitive, List<String> values) {
        return new ProfileValueMasker.ColumnValues(new ProfileColumnSensitivity(1L, 2L, 9L, "crm", "customer",
                column, sensitive, null, false, false, null, false), values);
    }

}
