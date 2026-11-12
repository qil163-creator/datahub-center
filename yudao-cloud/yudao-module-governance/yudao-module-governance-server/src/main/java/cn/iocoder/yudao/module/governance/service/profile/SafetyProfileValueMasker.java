package cn.iocoder.yudao.module.governance.service.profile;

import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskRespDTO;
import cn.iocoder.yudao.module.governance.service.security.SafetyDynamicMaskService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于动态脱敏的落库前脱敏：调用 {@link SafetyDynamicMaskService#maskForStorage}（不看场景开关与白名单，
 * 命中分类、敏感标注或来源不可追溯的敏感表即整体替换为不可逆掩码），再叠加探查自身的元数据敏感依据：
 * 元数据判定敏感但脱敏服务未处理的字段，不保存任何取值文本。
 *
 * <p>同模块调用，无 RPC；服务异常向上抛出，由调用方按失败关闭处理。</p>
 *
 * @author Li
 */
@Component
public class SafetyProfileValueMasker implements ProfileValueMasker {

    @Resource
    private SafetyDynamicMaskService dynamicMaskService;

    /**
     * 以“每个取值位置一行、每个字段一列”的形状批量脱敏。
     */
    @Override
    public Map<String, MaskedValues> mask(List<ColumnValues> columns) {
        Map<String, MaskedValues> result = new HashMap<>();
        if (columns.isEmpty()) {
            return result;
        }
        List<SafetyDynamicMaskReqDTO.Column> maskColumns = new ArrayList<>(columns.size());
        int rowCount = 0;
        for (ColumnValues input : columns) {
            ProfileColumnSensitivity column = input.column();
            SafetyDynamicMaskReqDTO.Column maskColumn = new SafetyDynamicMaskReqDTO.Column();
            maskColumn.setOutputName(column.columnName());
            maskColumn.setSources(List.of(SafetyDynamicMaskReqDTO.Source.of(column.datasourceId(),
                    column.schemaName(), column.tableName(), column.columnName())));
            maskColumns.add(maskColumn);
            rowCount = Math.max(rowCount, input.values().size());
        }
        List<Map<String, Object>> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (ColumnValues input : columns) {
                row.put(input.column().columnName(), i < input.values().size() ? input.values().get(i) : null);
            }
            rows.add(row);
        }
        SafetyDynamicMaskRespDTO resp = dynamicMaskService.maskForStorage(maskColumns, rows);
        Map<String, String> reasons = new HashMap<>();
        resp.getMaskedColumns().forEach(masked -> reasons.put(masked.getOutputName(), masked.getReason()));
        for (ColumnValues input : columns) {
            String name = input.column().columnName();
            int size = input.values().size();
            if (reasons.containsKey(name)) {
                List<String> values = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    Object value = i < resp.getRows().size() ? resp.getRows().get(i).get(name) : null;
                    // 原值为空的位置（如无最值）保持为空，不保存掩码
                    values.add(input.values().get(i) == null || value == null ? null : value.toString());
                }
                result.put(name, new MaskedValues(true, values, reasons.get(name)));
            } else if (input.column().sensitive()) {
                result.put(name, MaskedValues.redacted(size, REASON_METADATA));
            } else {
                result.put(name, new MaskedValues(false, input.values(), null));
            }
        }
        return result;
    }

}
