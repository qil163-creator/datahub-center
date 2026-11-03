package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlDialect;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;

/**
 * 正则格式校验：字段值须匹配指定正则表达式
 *
 * <p>config: {"regex": "^[0-9]+$", "ignoreNullValue": false}
 *
 * @author Li
 */
public class RegexValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "REGEX_VALIDATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "正则格式校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"regex\": \"^[0-9]{6}$\", \"ignoreNullValue\": false}";
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    @Override
    protected String buildValidFrag(AuditRuleContext context) {
        String regex = MapUtil.getStr(context.getConfig(), "regex");
        if (StrUtil.isBlank(regex)) {
            throw new IllegalArgumentException("正则格式校验需要配置 regex");
        }
        return AuditSqlDialect.regexpMatch(context.getProductType(),
                AuditSqlDialect.castText(context.database(), context.getColumn()), regex);
    }

}
