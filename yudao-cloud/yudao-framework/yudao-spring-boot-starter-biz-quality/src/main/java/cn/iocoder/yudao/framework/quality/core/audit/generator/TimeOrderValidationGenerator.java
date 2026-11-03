package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 时间字段先后顺序校验：多个时间字段之间须满足业务先后关系
 *
 * <p>config.conditions: [{"leftField": "create_time", "operator": "<=", "rightField": "finish_time"}]
 *
 * @author Li
 */
public class TimeOrderValidationGenerator extends AuditSqlGenerator {

    private static final Set<String> ALLOWED_OPERATORS = Set.copyOf(Arrays.asList("<", "<=", ">", ">=", "=", "<>"));

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "TIME_ORDER_VALIDATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "时间字段顺序校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"conditions\": [{\"leftField\": \"create_time\", \"operator\": \"<=\", \"rightField\": \"finish_time\"}], "
                + "\"ignoreNullValue\": true}";
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    @Override
    @SuppressWarnings("unchecked")
    protected String buildValidFrag(AuditRuleContext context) {
        Object raw = context.getConfig() == null ? null : context.getConfig().get("conditions");
        if (!(raw instanceof List) || CollUtil.isEmpty((List<Object>) raw)) {
            throw new IllegalArgumentException("时间顺序校验需要配置 conditions（比较条件列表）");
        }
        List<String> frags = new ArrayList<>();
        for (Object item : (List<Object>) raw) {
            Map<String, Object> condition = (Map<String, Object>) item;
            String left = MapUtil.getStr(condition, "leftField");
            String operator = StrUtil.trim(MapUtil.getStr(condition, "operator"));
            String right = MapUtil.getStr(condition, "rightField");
            if (StrUtil.hasBlank(left, operator, right)) {
                throw new IllegalArgumentException("时间顺序校验条件缺少 leftField/operator/rightField");
            }
            if (!ALLOWED_OPERATORS.contains(operator)) {
                throw new IllegalArgumentException("时间顺序校验不支持比较符：" + operator);
            }
            frags.add(left + " " + operator + " " + right);
        }
        return String.join(" AND ", frags);
    }

    /**
     * 构建"违规"条件片段：默认对合规片段取反； 不忽略空值时，稽查字段为 NULL 也计为违规（与 qData 语义一致）。
     */
    @Override
    protected String buildInvalidFrag(AuditRuleContext context) {
        String valid = buildValidFrag(context);
        if (context.isIgnoreNullValue()) {
            return "NOT (" + valid + ")";
        }
        // 不忽略空值：参与比较的任一时间字段为 NULL 也视为违规
        StringBuilder frag = new StringBuilder("(");
        for (String column : resolveConditionColumns(context)) {
            frag.append(column).append(" IS NULL OR ");
        }
        frag.append("NOT (").append(valid).append("))");
        return frag.toString();
    }

    /**
     * 解析条件列集合。
     */
    @SuppressWarnings("unchecked")
    private List<String> resolveConditionColumns(AuditRuleContext context) {
        List<String> columns = new ArrayList<>();
        Object raw = context.getConfig() == null ? null : context.getConfig().get("conditions");
        if (raw instanceof List) {
            for (Object item : (List<Object>) raw) {
                Map<String, Object> condition = (Map<String, Object>) item;
                for (String key : Arrays.asList("leftField", "rightField")) {
                    String column = MapUtil.getStr(condition, key);
                    if (StrUtil.isNotBlank(column) && !columns.contains(column.trim())) {
                        columns.add(column.trim());
                    }
                }
            }
        }
        return columns;
    }

}
