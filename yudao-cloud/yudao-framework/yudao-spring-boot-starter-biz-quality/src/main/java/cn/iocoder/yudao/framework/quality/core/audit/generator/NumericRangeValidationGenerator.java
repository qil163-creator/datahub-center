package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.hutool.core.map.MapUtil;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 数值字段范围校验：字段值须落在 [minValue, maxValue] 区间
 *
 * @author Li
 */
public class NumericRangeValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "NUMERIC_RANGE_VALIDATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "数值字段范围校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"minValue\": 0, \"maxValue\": 120, \"includeBoundary\": true, \"ignoreNullValue\": false}";
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    @Override
    protected String buildValidFrag(AuditRuleContext context) {
        BigDecimal min = toDecimal(MapUtil.getStr(context.getConfig(), "minValue"));
        BigDecimal max = toDecimal(MapUtil.getStr(context.getConfig(), "maxValue"));
        if (min == null && max == null) {
            throw new IllegalArgumentException("数值范围校验至少需要配置 minValue 或 maxValue");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("数值范围校验要求 minValue 不能大于 maxValue");
        }
        // includeMin/includeMax 是标准编译器使用的无损表示。
        // 回退到历史 includeBoundary 开关，使现有手工编写的质量
        // 规则保持原有行为。
        boolean include = !Boolean.FALSE.equals(MapUtil.getBool(context.getConfig(), "includeBoundary", true));
        boolean includeMin = MapUtil.getBool(context.getConfig(), "includeMin") == null
                ? include : Boolean.TRUE.equals(MapUtil.getBool(context.getConfig(), "includeMin"));
        boolean includeMax = MapUtil.getBool(context.getConfig(), "includeMax") == null
                ? include : Boolean.TRUE.equals(MapUtil.getBool(context.getConfig(), "includeMax"));
        String column = context.getColumn();
        List<String> frags = new ArrayList<>();
        if (min != null) {
            frags.add(column + (includeMin ? " >= " : " > ") + min.toPlainString());
        }
        if (max != null) {
            frags.add(column + (includeMax ? " <= " : " < ") + max.toPlainString());
        }
        return String.join(" AND ", frags);
    }

    /**
     * 将输入值转换为精确小数。
     */
    private BigDecimal toDecimal(String value) {
        if (value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return new BigDecimal(value.trim());
    }

}
