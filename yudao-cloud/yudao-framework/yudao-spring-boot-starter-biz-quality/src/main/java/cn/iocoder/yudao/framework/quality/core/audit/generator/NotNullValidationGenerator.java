package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlDialect;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;

/**
 * 非空校验：字段值不得为 NULL 或空字符串
 *
 * <p>config: {"allowBlank": false, "removeWhitespace": false}
 *
 * @author Li
 */
public class NotNullValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "NOT_NULL_VALIDATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "非空校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"allowBlank\": false}";
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    @Override
    protected String buildValidFrag(AuditRuleContext context) {
        boolean allowBlank = Boolean.TRUE.equals(context.getConfig().get("allowBlank"));
        if (allowBlank) {
            // 仅不允许 NULL，允许空字符串
            return context.getColumn() + " IS NOT NULL";
        }
        if (context.getProductType() == ProductTypeEnum.ORACLE) {
            // Oracle 将 '' 存为 NULL；<> '' 的结果为 UNKNOWN。LENGTH 也会拒绝 EMPTY_CLOB()。
            return "(" + context.getColumn() + " IS NOT NULL AND LENGTH(" + context.getColumn() + ") > 0)";
        }
        if (context.getProductType() == ProductTypeEnum.SQLSERVER) {
            // LEN 会去掉尾部空格；对显式文本使用 DATALENGTH 可将它们与空字符串区分。
            return "(" + context.getColumn() + " IS NOT NULL AND DATALENGTH("
                    + AuditSqlDialect.castText(context.database(), context.getColumn()) + ") > 0)";
        }
        if (context.getProductType() == ProductTypeEnum.OCEANBASE || context.getProductType() == ProductTypeEnum.MARIADB) {
            // PAD SPACE 排序规则将空格与 '' 视为相等，因此改为检查长度；
            // allowBlank=false 拒绝空字符串，除非规则明确执行归一化，否则不会拒绝空白文本。
            String text = AuditSqlDialect.castText(context.database(), context.getColumn());
            return "(" + context.getColumn() + " IS NOT NULL AND "
                    + AuditSqlDialect.charLength(context.getProductType(), text) + " > 0)";
        }
        if (context.getProductType() == ProductTypeEnum.OSCAR) {
            // ShenTong V8.0.251217 区分 '' 和 NULL，但将数值 0 与 '' 视为相等。
            // 转换为 varchar 后，0 被视为有值，'' 被视为空。此处不使用 Oracle 的 LENGTH 形式。
            return "(" + context.getColumn() + " IS NOT NULL AND CAST("
                    + context.getColumn() + " AS VARCHAR(40)) <> '')";
        }
        // 不允许 NULL 且不允许空字符串
        return "(" + context.getColumn() + " IS NOT NULL AND "
                + AuditSqlDialect.castText(context.database(), context.getColumn()) + " <> '')";
    }

}
