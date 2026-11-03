package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 值映射节点（toolCode: value-map）：按映射表把字段值替换为目标值
 *
 * 配置：sourceField 来源字段；targetField 目标字段（空则覆盖来源字段）；
 * mappings [{from 源值, to 目标值}]；unmatched KEEP 保留原值|DEFAULT 设为默认值|SET_NULL 置空；
 * defaultValue 默认值（unmatched=DEFAULT 时）
 *
 * @author Li
 */
public class ValueMapExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "value-map";

    /**
     * 初始化 {@code ValueMapExecutor}及其父类状态。
     */
    public ValueMapExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String sourceField = requireConfig("sourceField", "来源字段");
        String targetField = StrUtil.blankToDefault(config().getStr("targetField"), sourceField);
        String unmatched = StrUtil.blankToDefault(config().getStr("unmatched"), "KEEP").toUpperCase();
        String defaultValue = config().getStr("defaultValue");
        Map<String, String> mappings = resolveMappings();

        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Map<String, Object> result = new LinkedHashMap<>(row);
            Object value = row.get(sourceField);
            String key = value == null ? "" : String.valueOf(value);
            if (mappings.containsKey(key)) {
                result.put(targetField, mappings.get(key));
            } else {
                switch (unmatched) {
                    case "DEFAULT" -> result.put(targetField, defaultValue);
                    case "SET_NULL" -> result.put(targetField, null);
                    default -> result.put(targetField, value);
                }
            }
            pushRow(result);
        }
        logLine(String.format("值映射完成，输出 %s 行", writeCount.get()));
    }

    /**
     * 解析映射集合。
     */
    private Map<String, String> resolveMappings() {
        Map<String, String> mappings = new LinkedHashMap<>();
        JSONArray array = config().getJSONArray("mappings");
        if (array != null) {
            for (Object item : array) {
                JSONObject mapping = (JSONObject) item;
                String from = mapping.getStr("from");
                if (from != null) {
                    mappings.put(from, mapping.getStr("to"));
                }
            }
        }
        if (mappings.isEmpty()) {
            throw new IllegalStateException(
                    String.format("节点【%s】缺少配置：值映射规则", node.getName()));
        }
        return mappings;
    }

}
