package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlDialect;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 枚举值校验：字段值须在指定值域集合内
 *
 * @author Li
 */
public class EnumValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "ENUM_VALIDATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "枚举值校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"validValues\": [\"男\", \"女\", \"未知\"], \"ignoreCase\": false, \"ignoreNullValue\": false}";
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    @Override
    @SuppressWarnings("unchecked")
    protected String buildValidFrag(AuditRuleContext context) {
        Object rawValues = context.getConfig() == null ? null : context.getConfig().get("validValues");
        if (!(rawValues instanceof List) || CollUtil.isEmpty((List<Object>) rawValues)) {
            throw new IllegalArgumentException("枚举值校验需要配置 validValues（合法值列表）");
        }
        boolean ignoreCase = Boolean.TRUE.equals(
                cn.hutool.core.map.MapUtil.getBool(context.getConfig(), "ignoreCase", false));
        Set<String> values = new LinkedHashSet<>();
        for (Object value : (List<Object>) rawValues) {
            String item = String.valueOf(value);
            values.add(AuditSqlDialect.stringLiteral(context.getProductType(),
                    ignoreCase ? item.toUpperCase() : item));
        }
        String column = AuditSqlDialect.castText(context.database(), context.getColumn());
        if (ignoreCase) {
            return "UPPER(" + column + ") IN (" + String.join(", ", values) + ")";
        }
        return AuditSqlDialect.caseSensitive(context.getProductType(), column)
                + " IN (" + String.join(", ", values) + ")";
    }

}
