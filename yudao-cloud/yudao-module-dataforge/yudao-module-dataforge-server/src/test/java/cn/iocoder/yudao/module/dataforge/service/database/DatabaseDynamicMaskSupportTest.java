package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
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

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_PREVIEW_FAILED;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_QUERY_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据库预览/查询结果的动态脱敏接入：来源追溯、失败关闭和内部采样场景。
 */
public class DatabaseDynamicMaskSupportTest {

    private static final String MASKED = "***";

    private SafetySecurityApi api;
    private DatabaseDynamicMaskSupport support;

    /**
     * 原样返回的脱敏支持，供不关心脱敏的 DatabaseApiImpl 测试注入。
     */
    public static DatabaseDynamicMaskSupport passThrough() {
        SafetySecurityApi api = mock(SafetySecurityApi.class);
        when(api.maskRows(any())).thenAnswer(invocation -> {
            SafetyDynamicMaskReqDTO req = invocation.getArgument(0);
            SafetyDynamicMaskRespDTO resp = new SafetyDynamicMaskRespDTO();
            resp.setEnabled(true);
            resp.setRows(req.getRows());
            return CommonResult.success(resp);
        });
        DatabaseDynamicMaskSupport support = new DatabaseDynamicMaskSupport();
        ReflectionTestUtils.setField(support, "safetySecurityApi", api);
        return support;
    }

    @BeforeEach
    void setUp() {
        api = mock(SafetySecurityApi.class);
        support = new DatabaseDynamicMaskSupport();
        ReflectionTestUtils.setField(support, "safetySecurityApi", api);
    }

    @Test
    void tablePreviewMapsEveryColumnToThePhysicalColumnAndReturnsMaskedRows() {
        maskColumn("phone");
        DatabasePreviewRespDTO resp = support.maskTable(7L, "public", "citizen", null,
                preview(List.of("id", "phone"), List.of(1, "13800000000")), DATABASE_PREVIEW_FAILED);

        SafetyDynamicMaskReqDTO req = captured();
        assertThat(req.getScene()).isEqualTo("DATABASE_PREVIEW");
        assertThat(req.getResourceName()).isEqualTo("public.citizen");
        assertThat(req.getColumns()).extracting(SafetyDynamicMaskReqDTO.Column::getOutputName)
                .containsExactly("id", "phone");
        SafetyDynamicMaskReqDTO.Source source = req.getColumns().get(1).getSources().getFirst();
        assertThat(List.of(source.getDatasourceId(), source.getSchemaName(), source.getTableName(),
                source.getColumnName())).containsExactly(7L, "public", "citizen", "phone");
        assertThat(resp.getRows().getFirst().get("phone")).isEqualTo(MASKED);
        assertThat(resp.getMaskedColumns()).singleElement()
                .satisfies(column -> {
                    assertThat(column.getName()).isEqualTo("phone");
                    assertThat(column.getReason()).isEqualTo(SafetyDynamicMaskRespDTO.REASON_RULE);
                    assertThat(column.getCategoryNames()).containsExactly("手机号");
                });
    }

    @Test
    void governanceUnavailableFailsClosedWithoutReturningRows() {
        when(api.maskRows(any())).thenThrow(new IllegalStateException("connect timed out"));
        DatabasePreviewRespDTO preview = preview(List.of("phone"), List.of("13800000000"));

        assertThatThrownBy(() -> support.maskTable(7L, null, "citizen", "ASSET_PREVIEW", preview,
                DATABASE_PREVIEW_FAILED))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("动态脱敏服务暂不可用")
                .hasMessageNotContaining("13800000000");
    }

    @Test
    void incompleteMaskResultFailsClosed() {
        when(api.maskRows(any())).thenReturn(CommonResult.success(new SafetyDynamicMaskRespDTO()));

        assertThatThrownBy(() -> support.maskTable(7L, null, "citizen", null,
                preview(List.of("phone"), List.of("13800000000")), DATABASE_PREVIEW_FAILED))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("动态脱敏结果不完整");
    }

    @Test
    void emptyResultAndSensitiveScanSkipTheRpc() {
        DatabasePreviewRespDTO empty = preview(List.of("phone"));
        assertThat(support.maskTable(7L, null, "citizen", null, empty, DATABASE_PREVIEW_FAILED).getMaskedColumns())
                .isEmpty();

        DatabasePreviewRespDTO sample = preview(List.of("phone"), List.of("13800000000"));
        DatabasePreviewRespDTO scanned = support.maskTable(7L, null, "citizen",
                DatabaseDynamicMaskSupport.SCENE_SENSITIVE_SCAN, sample, DATABASE_PREVIEW_FAILED);
        assertThat(scanned.getRows().getFirst().get("phone")).isEqualTo("13800000000");
        verify(api, never()).maskRows(any());
    }

    @Test
    void queryTracesAliasesAndTreatsHashOrCountAsAlreadyProtected() {
        passRows();
        support.maskQuery(7L, ProductTypeEnum.POSTGRESQL, "public",
                "SELECT phone AS p, md5(phone) AS h, count(*) AS c FROM public.citizen GROUP BY phone",
                null, null, "DATA_QUERY", preview(List.of("p", "h", "c"), List.of("1", "x", 1)),
                DATABASE_QUERY_FAILED);

        List<SafetyDynamicMaskReqDTO.Column> columns = captured().getColumns();
        assertThat(columns.get(0).getTraceable()).isTrue();
        assertThat(columns.get(0).getSources()).singleElement()
                .satisfies(source -> {
                    assertThat(source.getTableName()).isEqualTo("citizen");
                    assertThat(source.getColumnName()).isEqualTo("phone");
                });
        assertThat(columns.get(1).getSources()).isEmpty();
        assertThat(columns.get(1).getTraceable()).isTrue();
        assertThat(columns.get(2).getTraceable()).isTrue();
    }

    @Test
    void duplicateLabelsAreAlignedByPosition() {
        passRows();
        // 结果集键 phone / phone_2：第二列实际来自 name，不能沿用第一列的来源。
        DatabasePreviewRespDTO preview = preview(List.of("phone", "phone"), List.of("1", "n"));
        support.maskQuery(7L, ProductTypeEnum.POSTGRESQL, "public",
                "SELECT phone, name AS phone FROM public.citizen", null, null, null, preview,
                DATABASE_QUERY_FAILED);

        List<SafetyDynamicMaskReqDTO.Column> columns = captured().getColumns();
        assertThat(columns).extracting(SafetyDynamicMaskReqDTO.Column::getOutputName)
                .containsExactly("phone", "phone_2");
        assertThat(columns.get(0).getSources().getFirst().getColumnName()).isEqualTo("phone");
        assertThat(columns.get(1).getSources().getFirst().getColumnName()).isEqualTo("name");
    }

    @Test
    void singleTableWildcardMapsResultColumnsToThatTable() {
        passRows();
        support.maskQuery(7L, ProductTypeEnum.POSTGRESQL, "public", "SELECT * FROM public.citizen",
                null, null, null, preview(List.of("id", "phone"), List.of(1, "1")), DATABASE_QUERY_FAILED);

        List<SafetyDynamicMaskReqDTO.Column> columns = captured().getColumns();
        assertThat(columns).allSatisfy(column -> assertThat(column.getTraceable()).isTrue());
        assertThat(columns.get(1).getSources().getFirst().getTableName()).isEqualTo("citizen");
        assertThat(columns.get(1).getSources().getFirst().getColumnName()).isEqualTo("phone");
    }

    @Test
    void unsupportedProductMarksColumnsUntraceableWithTheDeclaredTableAsCandidate() {
        passRows();
        support.maskQuery(7L, ProductTypeEnum.SQLITE3, null, "SELECT phone FROM citizen",
                "main", "citizen", null, preview(List.of("phone"), List.of("1")), DATABASE_QUERY_FAILED);

        SafetyDynamicMaskReqDTO.Column column = captured().getColumns().getFirst();
        assertThat(column.getTraceable()).isFalse();
        assertThat(column.getSources()).isEmpty();
        assertThat(column.getCandidateTables()).singleElement()
                .satisfies(table -> {
                    assertThat(table.getSchemaName()).isEqualTo("main");
                    assertThat(table.getTableName()).isEqualTo("citizen");
                    assertThat(table.getColumnName()).isNull();
                });
    }

    @Test
    void unparsableSqlFallsBackToCandidateTablesInsteadOfPassingThrough() {
        passRows();
        support.maskQuery(7L, ProductTypeEnum.POSTGRESQL, "public", "SELECT phone FROM (",
                null, "citizen", null, preview(List.of("phone"), List.of("1")), DATABASE_QUERY_FAILED);

        SafetyDynamicMaskReqDTO.Column column = captured().getColumns().getFirst();
        assertThat(column.getTraceable()).isFalse();
        assertThat(column.getCandidateTables()).extracting(SafetyDynamicMaskReqDTO.Source::getTableName)
                .containsExactly("citizen");
    }

    // ==================== 辅助 ====================

    /**
     * 构造结果集：列键按 DatabaseApiImpl 规则对重名追加序号。
     */
    private static DatabasePreviewRespDTO preview(List<String> labels, List<?>... rows) {
        DatabasePreviewRespDTO resp = new DatabasePreviewRespDTO();
        List<DatabasePreviewRespDTO.Column> columns = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (String label : labels) {
            int occurrence = seen.merge(label.toLowerCase(), 1, Integer::sum);
            columns.add(new DatabasePreviewRespDTO.Column(occurrence == 1 ? label : label + "_" + occurrence,
                    label, "varchar"));
        }
        resp.setColumns(columns);
        List<Map<String, Object>> values = new ArrayList<>();
        for (List<?> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            for (int index = 0; index < columns.size(); index++) {
                item.put(columns.get(index).getName(), row.get(index));
            }
            values.add(item);
        }
        resp.setRows(values);
        resp.setTruncated(false);
        return resp;
    }

    /**
     * governance 返回原样行。
     */
    private void passRows() {
        when(api.maskRows(any())).thenAnswer(invocation -> {
            SafetyDynamicMaskReqDTO req = invocation.getArgument(0);
            SafetyDynamicMaskRespDTO resp = new SafetyDynamicMaskRespDTO();
            resp.setRows(req.getRows());
            return CommonResult.success(resp);
        });
    }

    /**
     * governance 把指定列打码。
     */
    private void maskColumn(String name) {
        when(api.maskRows(any())).thenAnswer(invocation -> {
            SafetyDynamicMaskReqDTO req = invocation.getArgument(0);
            SafetyDynamicMaskRespDTO resp = new SafetyDynamicMaskRespDTO();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> row : req.getRows()) {
                Map<String, Object> copy = new LinkedHashMap<>(row);
                copy.put(name, MASKED);
                rows.add(copy);
            }
            resp.setRows(rows);
            SafetyDynamicMaskRespDTO.MaskedColumn column = new SafetyDynamicMaskRespDTO.MaskedColumn();
            column.setOutputName(name);
            column.setReason(SafetyDynamicMaskRespDTO.REASON_RULE);
            column.setCategoryNames(List.of("手机号"));
            resp.setMaskedColumns(List.of(column));
            return CommonResult.success(resp);
        });
    }

    /**
     * 捕获发往 governance 的请求。
     */
    private SafetyDynamicMaskReqDTO captured() {
        ArgumentCaptor<SafetyDynamicMaskReqDTO> captor = ArgumentCaptor.forClass(SafetyDynamicMaskReqDTO.class);
        verify(api).maskRows(captor.capture());
        return captor.getValue();
    }
}
