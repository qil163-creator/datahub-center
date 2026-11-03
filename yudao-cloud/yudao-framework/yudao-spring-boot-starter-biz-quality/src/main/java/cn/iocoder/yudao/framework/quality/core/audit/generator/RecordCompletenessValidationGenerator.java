package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.iocoder.yudao.framework.quality.core.audit.*;

import java.util.*;

/** 调用方选定的业务字段至少一项有值；主键等技术字段由调用方排除。 */
public class RecordCompletenessValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    public String getStrategyKey() {
        return "RECORD_COMPLETENESS_VALIDATION";
    }

    /**
     * 策略名称。
     */
    public String getStrategyName() {
        return "空记录校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    public String getConfigExample() {
        return "{\"blankAsNull\":true}";
    }

    /**
     * 校验配置。
     */
    public void validateConfig(AuditRuleContext context) {
        normalizeConfig(context.getConfig());
    }

    /**
     * 旧策略保留原配置，新策略可收紧参数契约。
     */
    public Map<String, Object> normalizeConfig(Map<String, Object> input) {
        Map<String, Object> result = AuditConfigSupport.config(input, "blankAsNull", "ignoreNullValue");
        result.put("blankAsNull", AuditConfigSupport.bool(result, "blankAsNull", true));
        result.put("ignoreNullValue", AuditConfigSupport.bool(result, "ignoreNullValue", false));
        return result;
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    protected String buildValidFrag(AuditRuleContext context) {
        Map<String, Object> config = normalizeConfig(context.getConfig());
        if (context.resolveColumns().isEmpty()) {
            throw new IllegalArgumentException("Record completeness requires business columns");
        }
        return context.resolveColumns().stream()
                .map(field -> AuditConfigSupport.present(context, field, (Boolean) config.get("blankAsNull")))
                .collect(java.util.stream.Collectors.joining(" OR ", "(", ")"));
    }

    /**
     * 构建"违规"条件片段：默认对合规片段取反； 不忽略空值时，稽查字段为 NULL 也计为违规（与 qData 语义一致）。
     */
    protected String buildInvalidFrag(AuditRuleContext context) {
        return "NOT (" + buildValidFrag(context) + ")";
    }
}
