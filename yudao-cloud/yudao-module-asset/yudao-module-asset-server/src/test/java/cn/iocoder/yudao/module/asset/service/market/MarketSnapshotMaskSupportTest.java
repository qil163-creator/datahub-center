package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迁入快照预览按查看者脱敏：来源取本地资产的物理表；资产未登记物理表时整体按无法追溯处理；服务不可用不返回。
 */
class MarketSnapshotMaskSupportTest {

    private SafetySecurityApi api;
    private MarketSnapshotMaskSupport support;

    @BeforeEach
    void setUp() {
        api = mock(SafetySecurityApi.class);
        support = new MarketSnapshotMaskSupport();
        ReflectionTestUtils.setField(support, "safetySecurityApi", api);
    }

    @Test
    void columnsAreTracedToTheAssetTableAndRowsReplaced() {
        when(api.maskRows(any())).thenAnswer(invocation -> masked(invocation.getArgument(0), "phone"));
        DataAssetDO asset = new DataAssetDO();
        asset.setDatasourceId(3L);
        asset.setSchemaName("ods");
        asset.setTableName("users");
        asset.setAssetName("用户表");
        DatabasePreviewRespDTO preview = preview();

        support.mask(asset, preview, 9L);

        assertThat(preview.getRows().getFirst().get("phone")).isEqualTo("***");
        assertThat(preview.getMaskedColumns()).extracting(DatabasePreviewRespDTO.MaskedColumn::getName)
                .containsExactly("phone");
        ArgumentCaptor<SafetyDynamicMaskReqDTO> captor = ArgumentCaptor.forClass(SafetyDynamicMaskReqDTO.class);
        verify(api).maskRows(captor.capture());
        assertThat(captor.getValue().getScene()).isEqualTo("MARKET_PREVIEW");
        assertThat(captor.getValue().getUserId()).isEqualTo(9L);
        SafetyDynamicMaskReqDTO.Source source = captor.getValue().getColumns().get(1).getSources().getFirst();
        assertThat(List.of(source.getDatasourceId(), source.getSchemaName(), source.getTableName(),
                source.getColumnName())).containsExactly(3L, "ods", "users", "phone");
    }

    @Test
    void assetWithoutPhysicalTableMakesEveryColumnUntraceable() {
        when(api.maskRows(any())).thenAnswer(invocation -> masked(invocation.getArgument(0)));
        support.mask(null, preview(), 9L);
        ArgumentCaptor<SafetyDynamicMaskReqDTO> captor = ArgumentCaptor.forClass(SafetyDynamicMaskReqDTO.class);
        verify(api).maskRows(captor.capture());
        assertThat(captor.getValue().getColumns()).allSatisfy(column -> {
            assertThat(column.getTraceable()).isFalse();
            assertThat(column.getCandidateTables()).isEmpty();
        });
    }

    @Test
    void governanceUnavailableReturnsNothing() {
        when(api.maskRows(any())).thenThrow(new IllegalStateException("down"));
        assertThatThrownBy(() -> support.mask(null, preview(), 9L)).isInstanceOf(ServiceException.class)
                .hasMessageNotContaining("13800000000");
    }

    @Test
    void emptySnapshotSkipsTheCall() {
        DatabasePreviewRespDTO empty = new DatabasePreviewRespDTO();
        empty.setColumns(List.of(new DatabasePreviewRespDTO.Column("id", "id", "int")));
        empty.setRows(List.of());
        support.mask(null, empty, 9L);
        verify(api, never()).maskRows(any());
    }

    private static DatabasePreviewRespDTO preview() {
        DatabasePreviewRespDTO preview = new DatabasePreviewRespDTO();
        preview.setColumns(List.of(new DatabasePreviewRespDTO.Column("id", "id", "int"),
                new DatabasePreviewRespDTO.Column("phone", "phone", "varchar")));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("phone", "13800000000");
        preview.setRows(new ArrayList<>(List.of(row)));
        return preview;
    }

    private static CommonResult<SafetyDynamicMaskRespDTO> masked(SafetyDynamicMaskReqDTO req, String... columns) {
        SafetyDynamicMaskRespDTO resp = new SafetyDynamicMaskRespDTO();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : req.getRows()) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            for (String column : columns) {
                copy.put(column, "***");
            }
            rows.add(copy);
        }
        resp.setRows(rows);
        for (String column : columns) {
            SafetyDynamicMaskRespDTO.MaskedColumn item = new SafetyDynamicMaskRespDTO.MaskedColumn();
            item.setOutputName(column);
            item.setReason("RULE");
            resp.getMaskedColumns().add(item);
        }
        return CommonResult.success(resp);
    }
}
