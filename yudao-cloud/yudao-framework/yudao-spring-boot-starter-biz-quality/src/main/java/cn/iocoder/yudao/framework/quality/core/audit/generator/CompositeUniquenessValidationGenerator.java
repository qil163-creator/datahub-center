package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;

import java.util.List;

/**
 * 多字段组合唯一性校验：字段组合在全表中不允许重复
 *
 * @author Li
 */
public class CompositeUniquenessValidationGenerator extends AbstractUniquenessValidationGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "COMPOSITE_UNIQUENESS_VALIDATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "组合唯一性校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"ignoreNull\": false}";
    }

    /**
     * 返回当前规则计算必须读取的列集合。
     */
    @Override
    protected List<String> requiredColumns(AuditRuleContext context) {
        List<String> columns = context.resolveColumns();
        if (StrUtil.isBlank(context.getTableName()) || CollUtil.isEmpty(columns)) {
            throw new IllegalArgumentException("组合唯一性校验需要配置表和字段");
        }
        return columns;
    }

    /**
     * 返回当前规则默认是否忽略空值。
     */
    @Override
    protected boolean defaultIgnoreNull() {
        return false;
    }

}
