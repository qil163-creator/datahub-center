package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.iocoder.yudao.framework.quality.core.audit.*;

import java.util.*;

/**
 * 严格公历校验，覆盖闰年、各月天数和时分秒，避免数据库宽松日期解析。
 */
public class DateFormatValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "DATE_FORMAT_VALIDATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "日期时间格式校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"pattern\":\"yyyy-MM-dd\",\"ignoreNullValue\":true}";
    }

    /**
     * 旧策略保留原配置，新策略可收紧参数契约。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> input) {
        Map<String, Object> result = AuditConfigSupport.config(input, "pattern", "regex", "invalidValues",
                "ignoreNullValue");
        result.put("pattern",
                AuditConfigSupport.choice(result.get("pattern"), "pattern", "yyyy-MM-dd", "yyyy-MM-dd", "yyyy/MM/dd",
                        "yyyyMMdd", "HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss"));
        if (result.containsKey("regex")) {
            String regex = AuditConfigSupport.text(result.get("regex"), "regex", 1024);
            try {
                java.util.regex.Pattern.compile(regex);
            } catch (java.util.regex.PatternSyntaxException ex) {
                throw new IllegalArgumentException("Invalid date regex", ex);
            }
            result.put("regex", regex);
        }
        if (result.containsKey("invalidValues")) {
            result.put("invalidValues", AuditConfigSupport.texts(result.get("invalidValues"), "invalidValues", 0, 128));
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
        if (context.resolveColumns().size() != 1) {
            throw new IllegalArgumentException("Date validation requires one column");
        }
        String expression = AuditSqlDialect.castText(context.database(), context.resolveColumns().get(0));
        String pattern = (String) config.get("pattern");
        String time = "([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]";
        String separator = pattern.startsWith("yyyy/") ? "/" : pattern.startsWith("yyyyMMdd") ? "" : "-";
        String regex = "HH:mm:ss".equals(pattern) ? time : dateRegex(separator);
        if (pattern.endsWith("HH:mm:ss") && !"HH:mm:ss".equals(pattern)) {
            regex += pattern.contains("'T'") ? "T" + time : " " + time;
        }
        List<String> conditions = new ArrayList<>();
        conditions.add(AuditSqlDialect.regexpMatch(context.getProductType(), expression, "^(" + regex + ")$"));
        if (config.containsKey("regex")) {
            conditions.add(
                    AuditSqlDialect.regexpMatch(context.getProductType(), expression, (String) config.get("regex")));
        }
        if (config.containsKey("invalidValues")) {
            List<String> values = AuditConfigSupport.texts(config.get("invalidValues"), "invalidValues", 0, 128);
            if (!values.isEmpty()) {
                conditions.add(
                        expression + " NOT IN (" + values.stream().map(v -> AuditConfigSupport.literal(context, v))
                                .collect(java.util.stream.Collectors.joining(", ")) + ")");
            }
        }
        return "(" + String.join(" AND ", conditions) + ")";
    }

    /**
     * 构建包含年份、月份及闰年约束的日期匹配表达式。
     */
    private static String dateRegex(String separator) {
        String year = "([0-9]{3}[1-9]|[0-9]{2}[1-9][0-9]|[0-9][1-9][0-9]{2}|[1-9][0-9]{3})";
        String leap = "([0-9]{2}(0[48]|[2468][048]|[13579][26])|(0[48]|[2468][048]|[13579][26])00)";
        String day28 = "(0[1-9]|1[0-9]|2[0-8])";
        return "(" + year + separator + "((01|03|05|07|08|10|12)" + separator + "(0[1-9]|[12][0-9]|3[01])|"
                + "(04|06|09|11)" + separator + "(0[1-9]|[12][0-9]|30)|02" + separator + day28 + ")|"
                + leap + separator + "02" + separator + "29)";
    }
}
