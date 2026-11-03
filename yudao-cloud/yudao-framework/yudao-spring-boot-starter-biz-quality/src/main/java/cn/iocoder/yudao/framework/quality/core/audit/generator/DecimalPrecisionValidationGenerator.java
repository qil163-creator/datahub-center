package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.hutool.core.map.MapUtil;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlDialect;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;

/**
 * 数值精度校验：小数位数不得超过业务定义位数（skipInteger=true 时整数视为合规）
 *
 * @author Li
 */
public class DecimalPrecisionValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "DECIMAL_PRECISION_VALIDATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "数值精度校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"precision\": 10, \"scale\": 2, \"skipInteger\": true, \"ignoreNullValue\": false}";
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    @Override
    protected String buildValidFrag(AuditRuleContext context) {
        Integer scale = MapUtil.getInt(context.getConfig(), "scale");
        if (scale == null) {
            throw new IllegalArgumentException("数值精度校验需要配置 scale（小数位数）");
        }
        if (scale < 0) {
            throw new IllegalArgumentException("数值精度校验要求 scale 不能小于零");
        }
        Integer precision = MapUtil.getInt(context.getConfig(), "precision");
        if (precision != null && (precision <= 0 || scale > precision)) {
            throw new IllegalArgumentException("数值精度校验要求 precision > 0 且 0 <= scale <= precision");
        }
        boolean skipInteger = Boolean.TRUE.equals(MapUtil.getBool(context.getConfig(), "skipInteger", false));
        String text = AuditSqlDialect.castText(context.database(), context.getColumn());
        String lengthExpr = AuditSqlDialect.charLength(context.getProductType(), text);
        String dotPos = AuditSqlDialect.position(context.getProductType(), text, ".");
        String fractionFrag = "(" + lengthExpr + " - " + dotPos + ") <= " + scale + " AND " + dotPos + " > 0";
        if (skipInteger) {
            fractionFrag = dotPos + " = 0 OR (" + fractionFrag + ")";
        }
        if (precision == null) {
            // 兼容旧版仅声明小数位数规则的模式。
            return fractionFrag;
        }
        String unsignedDigits = "REPLACE(REPLACE(REPLACE(" + text
                + ", '-', ''), '+', ''), '.', '')";
        // SQL DECIMAL 精度不包含绝对值小于一时，小数点前的单个零
        // （DECIMAL(2,2) 必须接受 0.12 和 -0.12）。
        String leadingFractionalZero = "CASE WHEN " + text + " LIKE '0.%' OR " + text
                + " LIKE '-0.%' OR " + text + " LIKE '+0.%' THEN 1 ELSE 0 END";
        String totalDigitsFrag = "(" + AuditSqlDialect.charLength(context.getProductType(), unsignedDigits)
                + " - " + leadingFractionalZero + ") <= " + precision;
        return totalDigitsFrag + " AND (" + fractionFrag + ")";
    }

}
