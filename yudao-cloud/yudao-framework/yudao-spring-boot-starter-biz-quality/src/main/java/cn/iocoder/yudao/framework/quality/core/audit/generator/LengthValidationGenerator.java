package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.hutool.core.map.MapUtil;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlDialect;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * 字段长度范围校验：字符长度须落在 [minLength, maxLength]
 *
 * @author Li
 */
public class LengthValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "LENGTH_VALIDATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "字段长度范围校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"minLength\": 11, \"maxLength\": 11, \"ignoreNullValue\": false}";
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    @Override
    protected String buildValidFrag(AuditRuleContext context) {
        Integer min = MapUtil.getInt(context.getConfig(), "minLength");
        Integer max = MapUtil.getInt(context.getConfig(), "maxLength");
        if ((min != null && min < 0) || (max != null && max < 0)) {
            throw new IllegalArgumentException("长度范围校验要求长度边界不能小于零");
        }
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException("长度范围校验要求 minLength 不能大于 maxLength");
        }
        String lengthExpr = AuditSqlDialect.charLength(context.getProductType(),
                AuditSqlDialect.castText(context.database(), context.getColumn()));
        List<String> frags = new ArrayList<>();
        if (min != null) {
            frags.add(lengthExpr + " >= " + min);
        }
        if (max != null) {
            frags.add(lengthExpr + " <= " + max);
        }
        if (frags.isEmpty()) {
            throw new IllegalArgumentException("长度范围校验至少需要配置 minLength 或 maxLength");
        }
        return String.join(" AND ", frags);
    }

}
