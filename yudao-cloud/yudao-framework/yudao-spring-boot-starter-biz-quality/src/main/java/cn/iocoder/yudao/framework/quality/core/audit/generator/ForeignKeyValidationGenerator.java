package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;

/**
 * 外键校验：当前表的字段值必须存在于对照主表的对照字段中
 *
 * <p>config: {"refTable": "dim_org", "refColumn": "org_code", "ignoreNullValue": true}
 *
 * @author Li
 */
public class ForeignKeyValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "FOREIGN_KEY_VALIDATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "外键校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"refTable\": \"dim_org\", \"refColumn\": \"org_code\", \"ignoreNullValue\": true}";
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    @Override
    protected String buildValidFrag(AuditRuleContext context) {
        String refTable = MapUtil.getStr(context.getConfig(), "refTable");
        String refColumn = MapUtil.getStr(context.getConfig(), "refColumn");
        if (StrUtil.isBlank(refTable) || StrUtil.isBlank(refColumn)) {
            throw new IllegalArgumentException("外键校验需要配置 refTable 与 refColumn");
        }
        String column = context.getColumn();
        return column + " IN (SELECT " + refColumn + " FROM " + refTable + " WHERE " + refColumn + " IS NOT NULL)";
    }

}
