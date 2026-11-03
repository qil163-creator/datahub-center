package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.hutool.core.util.StrUtil;

import java.util.List;

/**
 * 单字段唯一校验：字段值不得重复（允许 NULL 重复，与 SQL UNIQUE 语义一致）
 *
 * <p>基于分组统计，无行级合规片段；统计 SQL 由 buildCountSql 的重写版本输出
 *
 * @author Li
 */
public class UniquenessValidationGenerator extends AbstractUniquenessValidationGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "UNIQUE_VALIDATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "单字段唯一校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"ignoreNull\": true}";
    }

    /**
     * 返回当前规则计算必须读取的列集合。
     */
    @Override
    protected List<String> requiredColumns(AuditRuleContext context) {
        if (StrUtil.isBlank(context.getTableName()) || StrUtil.isBlank(context.getColumn())) {
            throw new IllegalArgumentException("单字段唯一性校验需要配置表和字段");
        }
        return List.of(context.getColumn().trim());
    }

    /**
     * 返回当前规则默认是否忽略空值。
     */
    @Override
    protected boolean defaultIgnoreNull() {
        return true;
    }

}
