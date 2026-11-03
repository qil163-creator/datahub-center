package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.iocoder.yudao.framework.quality.core.audit.*;

import java.math.BigDecimal;
import java.util.*;

/** 显式边界、异常值集合或同范围样本的四分位距检测。 */
public class NumericOutlierValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    public String getStrategyKey() {
        return "NUMERIC_OUTLIER_VALIDATION";
    }

    /**
     * 策略名称。
     */
    public String getStrategyName() {
        return "数值异常值校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    public String getConfigExample() {
        return "{\"method\":\"IQR\",\"multiplier\":\"1.5\",\"minSamples\":4,\"ignoreNullValue\":false}";
    }

    /**
     * 旧策略保留原配置，新策略可收紧参数契约。
     */
    public Map<String, Object> normalizeConfig(Map<String, Object> input) {
        Map<String, Object> result = AuditConfigSupport.config(input, "method", "minValue", "maxValue", "includeMin",
                "includeMax", "values", "multiplier", "minSamples", "ignoreNullValue");
        String method = AuditConfigSupport.choice(result.get("method"), "method", "RANGE", "RANGE", "INVALID_VALUES",
                "IQR");
        result.put("method", method);
        result.put("ignoreNullValue", AuditConfigSupport.bool(result, "ignoreNullValue", false));
        if ("RANGE".equals(method)) {
            reject(result, "values", "multiplier", "minSamples");
            if (result.get("minValue") == null && result.get("maxValue") == null) {
                throw new IllegalArgumentException("RANGE requires at least one bound");
            }
            for (String key : List.of("minValue", "maxValue")) {
                if (result.get(key) != null) {
                    result.put(key, AuditConfigSupport.decimal(result.get(key), key));
                }
            }
            if (result.get("minValue") != null && result.get("maxValue") != null
                    && new BigDecimal((String) result.get("minValue")).compareTo(
                    new BigDecimal((String) result.get("maxValue"))) > 0) {
                throw new IllegalArgumentException("minValue must not exceed maxValue");
            }
            result.put("includeMin", AuditConfigSupport.bool(result, "includeMin", true));
            result.put("includeMax", AuditConfigSupport.bool(result, "includeMax", true));
        } else if ("INVALID_VALUES".equals(method)) {
            reject(result, "minValue", "maxValue", "includeMin", "includeMax", "multiplier", "minSamples");
            result.put("values", AuditConfigSupport.texts(result.get("values"), "values", 1, 128).stream()
                    .map(v -> AuditConfigSupport.decimal(v, "values")).toList());
        } else {
            reject(result, "minValue", "maxValue", "includeMin", "includeMax", "values");
            String multiplier = result.get("multiplier") == null ? "1.5" : AuditConfigSupport.decimal(
                    result.get("multiplier"), "multiplier");
            BigDecimal number = new BigDecimal(multiplier);
            if (number.signum() <= 0 || number.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("multiplier must be in (0,100]");
            }
            result.put("multiplier", multiplier);
            result.put("minSamples",
                    result.get("minSamples") == null ? 4 : AuditConfigSupport.integer(result.get("minSamples"),
                            "minSamples", 4, 1000000));
        }
        return result;
    }

    /**
     * 返回当前输入不符合支持范围或契约约定的拒绝结果。
     */
    private static void reject(Map<String, Object> config, String... keys) {
        for (String key : keys) {
            if (config.containsKey(key)) {
                throw new IllegalArgumentException(key + " does not apply to method");
            }
        }
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    protected String buildValidFrag(AuditRuleContext context) {
        Map<String, Object> config = normalizeConfig(context.getConfig());
        if (context.resolveColumns().size() != 1) {
            throw new IllegalArgumentException("Outlier validation requires one numeric column");
        }
        String column = context.resolveColumns().get(0);
        if ("INVALID_VALUES".equals(config.get("method"))) {
            return column + " NOT IN (" + String.join(", ",
                    AuditConfigSupport.texts(config.get("values"), "values", 1, 128)) + ")";
        }
        if ("RANGE".equals(config.get("method"))) {
            List<String> parts = new ArrayList<>();
            if (config.get("minValue") != null) {
                parts.add(column + ((Boolean) config.get("includeMin") ? " >= " : " > ") + config.get("minValue"));
            }
            if (config.get("maxValue") != null) {
                parts.add(column + ((Boolean) config.get("includeMax") ? " <= " : " < ") + config.get("maxValue"));
            }
            return "(" + String.join(" AND ", parts) + ")";
        }
        if (context.getProductType() != cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL) {
            throw new IllegalArgumentException("IQR outlier method currently requires PostgreSQL");
        }
        String scope = effectiveWhereClause(context);
        String source = " FROM " + context.getTableName() + " WHERE " + column + " IS NOT NULL" + (scope == null ? "" : " AND (" + scope + ")");
        String stats = "SELECT COUNT(*) AS n, percentile_disc(0.25) WITHIN GROUP (ORDER BY " + column + ") AS q1, "
                + "percentile_disc(0.75) WITHIN GROUP (ORDER BY " + column + ") AS q3" + source;
        String outerColumn = context.getTableName() + "." + column;
        return "(" + outerColumn + " IS NOT NULL AND (SELECT CASE WHEN n < " + config.get("minSamples")
                + " THEN TRUE ELSE " + outerColumn + " >= q1 - " + config.get("multiplier") + " * (q3-q1) AND "
                + outerColumn + " <= q3 + " + config.get(
                "multiplier") + " * (q3-q1) END FROM (" + stats + ") qdata_iqr))";
    }

    /**
     * 构建"违规"条件片段：默认对合规片段取反； 不忽略空值时，稽查字段为 NULL 也计为违规（与 qData 语义一致）。
     */
    protected String buildInvalidFrag(AuditRuleContext context) {
        String column = context.resolveColumns().isEmpty() ? null : context.resolveColumns().get(0);
        String valid = buildValidFrag(context);
        return context.isIgnoreNullValue() ? "(" + column + " IS NOT NULL AND NOT (" + valid + "))"
                : "(" + column + " IS NULL OR NOT (" + valid + "))";
    }
}
