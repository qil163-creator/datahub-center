package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.etl.core.engine.EtlPreviewEngine;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldMeta;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseDynamicMaskSupport;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseDynamicMaskSupportTest;
import cn.iocoder.yudao.module.dataforge.service.lineage.EtlGraphLineageExtractor;
import cn.iocoder.yudao.module.dataforge.service.lineage.EtlGraphLineageExtractor.FieldTrace;
import cn.iocoder.yudao.module.dataforge.service.lineage.EtlGraphLineageExtractor.PhysicalColumn;
import cn.iocoder.yudao.module.dataforge.service.lineage.EtlGraphLineageExtractor.TracedField;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageNodeRefDTO;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ETL 设计预览动态脱敏：目标输出与输入样本都按物理来源脱敏，无法追溯的字段带候选表，服务不可用时不返回数据。
 */
public class EtlPreviewMaskSupportTest {

    private SafetySecurityApi api;
    private EtlGraphLineageExtractor extractor;
    private EtlPreviewMaskSupport support;

    /**
     * 原样返回的支持类，供不关心脱敏的预览测试注入。
     */
    public static EtlPreviewMaskSupport passThrough() {
        EtlGraphLineageExtractor extractor = mock(EtlGraphLineageExtractor.class);
        when(extractor.traceFields(anyList(), anyList())).thenReturn(new FieldTrace(Map.of(), List.of()));
        EtlPreviewMaskSupport support = new EtlPreviewMaskSupport();
        ReflectionTestUtils.setField(support, "extractor", extractor);
        ReflectionTestUtils.setField(support, "maskSupport", DatabaseDynamicMaskSupportTest.passThrough());
        return support;
    }

    @BeforeEach
    void setUp() {
        api = mock(SafetySecurityApi.class);
        DatabaseDynamicMaskSupport maskSupport = new DatabaseDynamicMaskSupport();
        ReflectionTestUtils.setField(maskSupport, "safetySecurityApi", api);
        extractor = mock(EtlGraphLineageExtractor.class);
        support = new EtlPreviewMaskSupport();
        ReflectionTestUtils.setField(support, "extractor", extractor);
        ReflectionTestUtils.setField(support, "maskSupport", maskSupport);
        LineageNodeRefDTO users = LineageNodeRefDTO.table(7L, "ods", "users");
        when(extractor.traceFields(anyList(), anyList())).thenReturn(new FieldTrace(Map.of(
                "out", Map.of(
                        "phone", new TracedField(List.of(new PhysicalColumn(7L, "ods", "users", "phone", false)), true),
                        "phone_hash", new TracedField(List.of(new PhysicalColumn(7L, "ods", "users", "phone", true)), true),
                        "expr", new TracedField(List.of(), false)),
                "in", Map.of(
                        "phone", new TracedField(List.of(new PhysicalColumn(7L, "ods", "users", "phone", false)), true))),
                List.of(users)));
    }

    @Test
    void targetAndInputSamplesAreMaskedBySourceAndMarked() {
        when(api.maskRows(any())).thenAnswer(invocation -> mask(invocation.getArgument(0), "phone"));

        EtlPreviewEngine.Result masked = support.mask(result(), List.of(), List.of(), "out", 1L);

        assertThat(masked.rows().getFirst().get("phone")).isEqualTo("***");
        assertThat(masked.inputSamples().getFirst().rows().getFirst().get("phone")).isEqualTo("***");
        assertThat(masked.maskedFields()).extracting(EtlPreviewEngine.MaskedField::sourceNodeNo)
                .containsExactly(null, "in");

        ArgumentCaptor<SafetyDynamicMaskReqDTO> captor = ArgumentCaptor.forClass(SafetyDynamicMaskReqDTO.class);
        verify(api, times(2)).maskRows(captor.capture());
        SafetyDynamicMaskReqDTO target = captor.getAllValues().getFirst();
        assertThat(target.getScene()).isEqualTo("ETL_PREVIEW");
        assertThat(target.getUserId()).isEqualTo(1L);
        Map<String, SafetyDynamicMaskReqDTO.Column> byName = new LinkedHashMap<>();
        target.getColumns().forEach(column -> byName.put(column.getOutputName(), column));
        assertThat(byName.get("phone").getSources()).singleElement()
                .satisfies(source -> assertThat(source.getColumnName()).isEqualTo("phone"));
        // 哈希后的字段不再暴露原值，不带来源。
        assertThat(byName.get("phone_hash").getSources()).isEmpty();
        // 表达式字段无法追溯：带候选表，由 governance 判定。
        assertThat(byName.get("expr").getTraceable()).isFalse();
        assertThat(byName.get("expr").getCandidateTables()).extracting(SafetyDynamicMaskReqDTO.Source::getTableName)
                .containsExactly("users");
        // 行里出现但未声明的字段也要参与判定。
        assertThat(byName).containsKey("undeclared");
        assertThat(byName.get("undeclared").getTraceable()).isFalse();
    }

    @Test
    void traceFailureMarksEverythingUntraceable() {
        when(extractor.traceFields(anyList(), anyList())).thenThrow(new IllegalStateException("metadata down"));
        when(api.maskRows(any())).thenAnswer(invocation -> mask(invocation.getArgument(0)));

        support.mask(result(), List.of(), List.of(), "out", 1L);

        ArgumentCaptor<SafetyDynamicMaskReqDTO> captor = ArgumentCaptor.forClass(SafetyDynamicMaskReqDTO.class);
        verify(api, times(2)).maskRows(captor.capture());
        assertThat(captor.getAllValues().getFirst().getColumns())
                .allSatisfy(column -> assertThat(column.getTraceable()).isFalse());
    }

    @Test
    void governanceUnavailableReturnsNoData() {
        when(api.maskRows(any())).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(() -> support.mask(result(), List.of(), List.of(), "out", 1L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("动态脱敏服务暂不可用")
                .hasMessageNotContaining("13800000000");
    }

    @Test
    void failedPreviewIsReturnedUntouched() {
        EtlPreviewEngine.Result failed = new EtlPreviewEngine.Result(false, List.of(), List.of(), 0, 0, List.of(),
                false);
        assertThat(support.mask(failed, List.of(), List.of(), "out", 1L)).isSameAs(failed);
        verify(api, never()).maskRows(any());
    }

    // ==================== 辅助 ====================

    private static EtlPreviewEngine.Result result() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phone", "13800000000");
        out.put("phone_hash", "abc");
        out.put("expr", "x");
        out.put("undeclared", "y");
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("phone", "13800000000");
        var sample = new EtlPreviewEngine.InputSample("in", "读表", "out", "in", List.of(new EtlFieldMeta("phone", null)),
                List.of(in), 1, false);
        return new EtlPreviewEngine.Result(true, List.of(new EtlFieldMeta("phone", null),
                new EtlFieldMeta("phone_hash", null), new EtlFieldMeta("expr", null)), List.of(out), 1, 1, List.of(),
                false, Map.of(), List.of(), "p1", List.of(), null, 1, List.of(sample), "INDEPENDENT_SAMPLES");
    }

    private static CommonResult<SafetyDynamicMaskRespDTO> mask(SafetyDynamicMaskReqDTO req, String... columns) {
        SafetyDynamicMaskRespDTO resp = new SafetyDynamicMaskRespDTO();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : req.getRows()) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            for (String column : columns) {
                if (copy.containsKey(column)) {
                    copy.put(column, "***");
                }
            }
            rows.add(copy);
        }
        resp.setRows(rows);
        List<SafetyDynamicMaskRespDTO.MaskedColumn> masked = new ArrayList<>();
        for (String column : columns) {
            SafetyDynamicMaskRespDTO.MaskedColumn item = new SafetyDynamicMaskRespDTO.MaskedColumn();
            item.setOutputName(column);
            item.setReason("RULE");
            masked.add(item);
        }
        resp.setMaskedColumns(masked);
        return CommonResult.success(resp);
    }
}
