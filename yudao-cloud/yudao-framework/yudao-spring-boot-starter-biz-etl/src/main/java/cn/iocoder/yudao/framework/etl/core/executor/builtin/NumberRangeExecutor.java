package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数值范围节点（toolCode: number-range）：按区间把数值映射为目标值（如 0-60 → 不及格）
 *
 * 配置：sourceField 来源字段；targetField 目标字段（空则覆盖来源字段）；
 * ranges [{min 下限（含，空为负无穷）, max 上限（不含，空为正无穷）, value 目标值}]，按顺序首个命中生效；
 * defaultValue 未命中/非数值时的默认值（空则置 null）
 *
 * @author Li
 */
public class NumberRangeExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "number-range";

    /**
     * 初始化 {@code NumberRangeExecutor}及其父类状态。
     */
    public NumberRangeExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String sourceField = requireConfig("sourceField", "来源字段");
        String targetField = StrUtil.blankToDefault(config().getStr("targetField"), sourceField);
        String defaultValue = config().getStr("defaultValue");
        List<JSONObject> ranges = resolveRanges();

        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Map<String, Object> result = new LinkedHashMap<>(row);
            result.put(targetField, mapValue(row.get(sourceField), ranges, defaultValue));
            pushRow(result);
        }
        logLine(String.format("数值范围映射完成，输出 %s 行", writeCount.get()));
    }

    /**
     * 解析范围集合。
     */
    private List<JSONObject> resolveRanges() {
        List<JSONObject> ranges = new ArrayList<>();
        JSONArray array = config().getJSONArray("ranges");
        if (array != null) {
            for (Object item : array) {
                ranges.add((JSONObject) item);
            }
        }
        if (ranges.isEmpty()) {
            throw new IllegalStateException(
                    String.format("节点【%s】缺少配置：范围规则", node.getName()));
        }
        return ranges;
    }

    /**
     * 映射值。
     */
    private Object mapValue(Object value, List<JSONObject> ranges, String defaultValue) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (!NumberUtil.isNumber(text)) {
            return defaultValue;
        }
        BigDecimal number = new BigDecimal(text);
        for (JSONObject range : ranges) {
            BigDecimal min = toDecimal(range.getStr("min"));
            BigDecimal max = toDecimal(range.getStr("max"));
            boolean hitMin = min == null || number.compareTo(min) >= 0;
            boolean hitMax = max == null || number.compareTo(max) < 0;
            if (hitMin && hitMax) {
                return range.getStr("value");
            }
        }
        return defaultValue;
    }

    /**
     * 将输入值转换为精确小数。
     */
    private BigDecimal toDecimal(String text) {
        return StrUtil.isBlank(text) || !NumberUtil.isNumber(text.trim())
                ? null : new BigDecimal(text.trim());
    }

}
