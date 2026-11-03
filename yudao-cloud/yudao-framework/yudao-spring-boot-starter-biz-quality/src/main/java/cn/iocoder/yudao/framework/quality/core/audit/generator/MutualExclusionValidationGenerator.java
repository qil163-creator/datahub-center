package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** 同一记录内，互斥字段最多允许一个满足非空或指定真值条件。 */
public class MutualExclusionValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "MUTUAL_EXCLUSION_VALIDATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "互斥字段冲突校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"condition\":\"NOT_NULL\"}";
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    @Override
    protected String buildValidFrag(AuditRuleContext context) {
        List<String> columns = context.resolveColumns();
        if (columns.size() < 2 || columns.size() > 32) {
            throw new IllegalArgumentException("互斥校验需要 2–32 个字段");
        }
        Map<String, Object> config = context.getConfig() == null ? Map.of() : context.getConfig();
        String condition = String.valueOf(config.getOrDefault("condition", "NOT_NULL"));
        if (!List.of("NOT_NULL", "IS_TRUE").contains(condition)) {
            throw new IllegalArgumentException("互斥条件必须为 NOT_NULL 或 IS_TRUE");
        }
        List<?> values = config.get("trueValues") instanceof List<?> list ? list : List.of();
        if ("IS_TRUE".equals(condition) && values.size() != columns.size()) {
            throw new IllegalArgumentException("每个互斥字段必须配置对应真值");
        }
        return "(" + IntStream.range(0, columns.size()).mapToObj(index -> {
            String predicate = columns.get(index) + ("NOT_NULL".equals(condition)
                    ? " IS NOT NULL" : " = " + literal(values.get(index)));
            return "CASE WHEN " + predicate + " THEN 1 ELSE 0 END";
        }).collect(java.util.stream.Collectors.joining(" + ")) + ") <= 1";
    }

    /**
     * 构建"违规"条件片段：默认对合规片段取反； 不忽略空值时，稽查字段为 NULL 也计为违规（与 qData 语义一致）。
     */
    @Override
    protected String buildInvalidFrag(AuditRuleContext context) {
        return "NOT (" + buildValidFrag(context) + ")";
    }

    /**
     * 返回当前字面量。
     */
    private static String literal(Object value) {
        if (!(value instanceof String text) || text.length() > 256
                || text.indexOf('\0') >= 0 || text.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("互斥真值必须是有效文本");
        }
        return "'" + text.replace("'", "''") + "'";
    }
}
