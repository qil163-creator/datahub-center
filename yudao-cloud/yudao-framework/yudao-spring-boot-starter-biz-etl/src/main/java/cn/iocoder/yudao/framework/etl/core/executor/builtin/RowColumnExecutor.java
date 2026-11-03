package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行列转换节点（toolCode: row-column）
 *
 * COL2ROW 列转行（宽转长，流式）：unpivotFields[] 需转换的列 + keyField 键字段名（默认 key）
 * + valueField 值字段名（默认 value）+ skipNull 跳过空值；其余列原样保留，每行展开为多行
 *
 * ROW2COL 行转列（长转宽，需缓存分组）：groupFields[] 分组字段 + keyField 键字段 + valueField 值字段
 * + mappings[{keyValue 键值, fieldName 生成字段名}]；同组多行合并为一行，按键值映射生成新列
 *
 * @author Li
 */
public class RowColumnExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "row-column";

    /**
     * 初始化 {@code RowColumnExecutor}及其父类状态。
     */
    public RowColumnExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String mode = StrUtil.blankToDefault(config().getStr("mode"), "COL2ROW").toUpperCase();
        switch (mode) {
            case "COL2ROW" -> columnsToRows();
            case "ROW2COL" -> rowsToColumns();
            default -> throw new IllegalStateException(
                    String.format("暂不支持的转换类型【%s】", mode));
        }
    }

    /**
     * 列转行：每行数据按需转换的列展开为多行（键列 + 值列），其余列原样保留
     */
    private void columnsToRows() throws Exception {
        List<String> unpivotFields = strList("unpivotFields");
        if (unpivotFields.isEmpty()) {
            throw new IllegalStateException(
                    String.format("节点【%s】缺少配置：需转换的列", node.getName()));
        }
        String keyField = StrUtil.blankToDefault(config().getStr("keyField"), "key");
        String valueField = StrUtil.blankToDefault(config().getStr("valueField"), "value");
        boolean skipNull = config().getBool("skipNull", false);

        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Map<String, Object> carried = new LinkedHashMap<>(row);
            unpivotFields.forEach(carried::remove);
            for (String field : unpivotFields) {
                Object value = row.get(field);
                if (skipNull && (value == null || StrUtil.isBlank(String.valueOf(value)))) {
                    continue;
                }
                Map<String, Object> out = new LinkedHashMap<>(carried);
                out.put(keyField, field);
                out.put(valueField, value);
                pushRow(out);
            }
        }
        logLine(String.format("列转行完成，输出 %s 行", writeCount.get()));
    }

    /**
     * 行转列：按分组字段聚合多行，键字段的值按映射生成新列（同组同键后值覆盖前值）
     */
    private void rowsToColumns() throws Exception {
        List<String> groupFields = strList("groupFields");
        String keyField = requireConfig("keyField", "键字段");
        String valueField = requireConfig("valueField", "值字段");
        JSONArray mappings = config().getJSONArray("mappings");
        Map<String, String> keyToField = new LinkedHashMap<>();
        if (mappings != null) {
            for (Object item : mappings) {
                JSONObject mapping = (JSONObject) item;
                if (StrUtil.isNotBlank(mapping.getStr("keyValue"))
                        && StrUtil.isNotBlank(mapping.getStr("fieldName"))) {
                    keyToField.put(mapping.getStr("keyValue"), mapping.getStr("fieldName"));
                }
            }
        }
        if (keyToField.isEmpty()) {
            throw new IllegalStateException(
                    String.format("节点【%s】缺少配置：键值映射", node.getName()));
        }

        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            StringBuilder groupKey = new StringBuilder();
            for (String field : groupFields) {
                groupKey.append(row.get(field)).append('\u0001');
            }
            Map<String, Object> finalRow = row;
            Map<String, Object> out = groups.computeIfAbsent(groupKey.toString(), key -> {
                Map<String, Object> init = new LinkedHashMap<>();
                groupFields.forEach(field -> init.put(field, finalRow.get(field)));
                keyToField.values().forEach(fieldName -> init.put(fieldName, null));
                return init;
            });
            String keyValue = row.get(keyField) == null ? "" : String.valueOf(row.get(keyField));
            String fieldName = keyToField.get(keyValue);
            if (fieldName != null) {
                out.put(fieldName, row.get(valueField));
            }
        }
        for (Map<String, Object> out : groups.values()) {
            checkStop();
            pushRow(out);
        }
        logLine(String.format("行转列完成，读取 %s 行，聚合输出 %s 行", readCount.get(), writeCount.get()));
    }

    /**
     * 从节点配置读取字符串列表。
     */
    private List<String> strList(String key) {
        List<String> values = new ArrayList<>();
        JSONArray array = config().getJSONArray(key);
        if (array != null) {
            array.forEach(item -> {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    values.add(String.valueOf(item));
                }
            });
        }
        return values;
    }

}
