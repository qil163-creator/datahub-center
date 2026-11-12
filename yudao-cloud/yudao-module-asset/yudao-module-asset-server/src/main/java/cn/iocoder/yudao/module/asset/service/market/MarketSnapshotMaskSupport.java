package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_PREVIEW_MASK_FAILED;

/**
 * 迁入申请的快照预览动态脱敏。
 * <p>
 * 快照是交付时保存的数据副本，读取时按当前查看者和当前分类策略脱敏：每列以本地资产登记的物理表同名列为来源。
 * 资产没有登记物理表时，所有列按无法追溯处理（没有候选表即整体打码）。governance 不可用时不返回快照。
 */
@Slf4j
@Component
public class MarketSnapshotMaskSupport {

    static final String SCENE = "MARKET_PREVIEW";

    @Resource
    private SafetySecurityApi safetySecurityApi;

    /**
     * 对快照预览脱敏，原地替换行与脱敏标注。
     *
     * @param asset   快照对应的本地资产；可空
     * @param preview 快照预览
     * @param viewer  查看者
     */
    public void mask(DataAssetDO asset, DatabasePreviewRespDTO preview, Long viewer) {
        if (preview.getRows() == null || preview.getRows().isEmpty() || preview.getColumns() == null
                || preview.getColumns().isEmpty()) {
            preview.setMaskedColumns(new ArrayList<>());
            return;
        }
        boolean traceable = asset != null && asset.getDatasourceId() != null && StrUtil.isNotBlank(asset.getTableName());
        List<SafetyDynamicMaskReqDTO.Column> columns = new ArrayList<>();
        for (DatabasePreviewRespDTO.Column column : preview.getColumns()) {
            SafetyDynamicMaskReqDTO.Column item = new SafetyDynamicMaskReqDTO.Column();
            item.setOutputName(column.getName());
            if (traceable) {
                item.setSources(List.of(SafetyDynamicMaskReqDTO.Source.of(asset.getDatasourceId(),
                        StrUtil.emptyToNull(asset.getSchemaName()), asset.getTableName(),
                        StrUtil.blankToDefault(column.getLabel(), column.getName()))));
            } else {
                item.setTraceable(false);
                item.setSources(List.of());
                item.setCandidateTables(List.of());
            }
            columns.add(item);
        }
        SafetyDynamicMaskReqDTO req = new SafetyDynamicMaskReqDTO();
        req.setUserId(viewer);
        req.setScene(SCENE);
        req.setResourceName(StrUtil.maxLength(asset == null ? "迁入申请快照"
                : StrUtil.blankToDefault(asset.getAssetName(), asset.getTableName()), 250));
        req.setColumns(columns);
        req.setRows(preview.getRows());
        SafetyDynamicMaskRespDTO masked;
        try {
            masked = safetySecurityApi.maskRows(req).getCheckedData();
        } catch (RuntimeException unavailable) {
            log.warn("[mask][快照预览动态脱敏不可用，失败关闭 failureType={}]", unavailable.getClass().getSimpleName());
            throw exception(MARKET_PREVIEW_MASK_FAILED, "动态脱敏服务暂不可用，为保护敏感数据暂不返回结果");
        }
        if (masked == null || masked.getRows() == null || masked.getRows().size() != preview.getRows().size()) {
            throw exception(MARKET_PREVIEW_MASK_FAILED, "动态脱敏结果不完整，为保护敏感数据暂不返回结果");
        }
        List<Map<String, Object>> rows = masked.getRows();
        List<DatabasePreviewRespDTO.MaskedColumn> markers = new ArrayList<>();
        for (SafetyDynamicMaskRespDTO.MaskedColumn column : masked.getMaskedColumns() == null
                ? List.<SafetyDynamicMaskRespDTO.MaskedColumn>of() : masked.getMaskedColumns()) {
            markers.add(new DatabasePreviewRespDTO.MaskedColumn(column.getOutputName(), column.getReason(),
                    column.getCategoryNames()));
        }
        preview.setRows(rows);
        preview.setMaskedColumns(markers);
    }
}
