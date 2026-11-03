package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;
import cn.iocoder.yudao.framework.quality.core.audit.AuditConfigSupport;

import java.util.*;

/**
 * 跨表一致性校验：连接键存在且选定字段与同一条引用记录一致
 *
 * <p>config: {"refTable": "dwd_student", "refColumn": "id", "leftColumn": "student_id", "ignoreNullValue": true,
 * "nullRefIsInvalid": false}
 *
 * @author Li
 */
public class CrossTableConsistencyGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "CROSS_TABLE_CONSISTENCY";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "跨表一致性校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"refTable\":\"dwd_student\",\"refColumn\":\"id\",\"leftColumn\":\"student_id\",\"comparisons\":[{\"leftColumn\":\"name\",\"rightColumn\":\"name\"}]}";
    }

    /**
     * 旧策略保留原配置，新策略可收紧参数契约。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> input) {
        Map<String, Object> result = AuditConfigSupport.config(input, "refTable", "refColumn", "leftColumn",
                "comparisons", "ignoreNullValue", "nullRefIsInvalid", "referenceObjectKey");
        if (result.containsKey("comparisons")) {
            List<Map<String, Object>> comparisons = AuditConfigSupport.objects(result.get("comparisons"), "comparisons",
                    1, 32);
            for (Map<String, Object> comparison : comparisons) {
                AuditConfigSupport.config(comparison, "leftColumn", "rightColumn");
                for (String key : List.of("leftColumn", "rightColumn")) {
                    comparison.put(key, AuditConfigSupport.text(comparison.get(key), key, 256));
                }
            }
            result.put("comparisons", comparisons);
        }
        result.put("ignoreNullValue", AuditConfigSupport.bool(result, "ignoreNullValue", false));
        return result;
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    @Override
    protected String buildValidFrag(AuditRuleContext context) {
        Map<String, Object> config = normalizeConfig(context.getConfig());
        String refTable = MapUtil.getStr(context.getConfig(), "refTable");
        String refColumn = MapUtil.getStr(context.getConfig(), "refColumn");
        String leftColumn = MapUtil.getStr(context.getConfig(), "leftColumn");
        if (StrUtil.isBlank(refTable) || StrUtil.isBlank(refColumn)) {
            throw new IllegalArgumentException("跨表一致性校验需要配置 refTable 与 refColumn");
        }
        if (StrUtil.isBlank(leftColumn)) {
            leftColumn = context.getColumn();
        }
        AuditConfigSupport.field(context, leftColumn, "leftColumn");
        if (!config.containsKey("comparisons")) {
            return leftColumn + " IN (SELECT " + refColumn + " FROM " + refTable + " WHERE " + refColumn + " IS NOT NULL)";
        }
        List<String> matches = new ArrayList<>();
        matches.add("qdata_ref." + refColumn + " = " + context.getTableName() + "." + leftColumn);
        for (Map<String, Object> comparison : AuditConfigSupport.objects(config.get("comparisons"), "comparisons", 1,
                32)) {
            String left = context.getTableName() + "." + AuditConfigSupport.field(context, comparison.get("leftColumn"),
                    "comparisons.leftColumn");
            String right = "qdata_ref." + AuditConfigSupport.text(comparison.get("rightColumn"),
                    "comparisons.rightColumn", 256);
            matches.add("(" + left + " = " + right + " OR (" + left + " IS NULL AND " + right + " IS NULL))");
        }
        return "EXISTS (SELECT 1 FROM " + refTable + " qdata_ref WHERE " + String.join(" AND ", matches) + ")";
    }

    /**
     * 构建"违规"条件片段：默认对合规片段取反； 不忽略空值时，稽查字段为 NULL 也计为违规（与 qData 语义一致）。
     */
    @Override
    protected String buildInvalidFrag(AuditRuleContext context) {
        Map<String, Object> config = normalizeConfig(context.getConfig());
        if (!config.containsKey("comparisons")) {
            return super.buildInvalidFrag(context);
        }
        String key = MapUtil.getStr(config, "leftColumn");
        if (StrUtil.isBlank(key)) {
            key = context.getColumn();
        }
        String valid = buildValidFrag(context);
        return context.isIgnoreNullValue() ? "(" + key + " IS NOT NULL AND NOT (" + valid + "))"
                : "(" + key + " IS NULL OR NOT (" + valid + "))";
    }

}
