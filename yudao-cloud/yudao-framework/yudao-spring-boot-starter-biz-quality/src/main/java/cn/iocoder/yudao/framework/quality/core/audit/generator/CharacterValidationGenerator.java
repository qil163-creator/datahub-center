package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.hutool.core.map.MapUtil;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlDialect;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;

/**
 * 字段字符类型校验：字段值须匹配指定正则（如仅字母数字）
 *
 * @author Li
 */
public class CharacterValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "CHARACTER_VALIDATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "字段字符类型校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"regex\": \"^[0-9]+$\", \"ignoreNullValue\": false}";
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    @Override
    protected String buildValidFrag(AuditRuleContext context) {
        String regex = MapUtil.getStr(context.getConfig(), "regex");
        String column = AuditSqlDialect.castText(context.database(), context.getColumn());
        return AuditSqlDialect.regexpMatch(context.getProductType(), column, regex);
    }

}
