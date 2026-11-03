package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 字段组完整性校验：逻辑上应成对出现的字段须同时填写
 *
 * <p>fillStrategy=1：字段组必须全部填写（部分为空即违规）；
 * fillStrategy=2：字段组要么全空、要么全填（部分填写即违规）
 *
 * @author Li
 */
public class GroupFieldCompletenessGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "GROUP_FIELD_COMPLETENESS";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "字段组完整性校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"fillStrategy\": 1}";
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    @Override
    protected String buildValidFrag(AuditRuleContext context) {
        List<String> columns = context.resolveColumns();
        if (CollUtil.isEmpty(columns) || columns.size() < 2) {
            throw new IllegalArgumentException("字段组完整性校验需要至少两个稽查字段");
        }
        int fillStrategy = MapUtil.getInt(context.getConfig(), "fillStrategy", 1);
        String allNotNull = columns.stream().map(col -> col + " IS NOT NULL").collect(Collectors.joining(" AND "));
        if (fillStrategy == 1) {
            return allNotNull;
        }
        if (fillStrategy == 2) {
            String allNull = columns.stream().map(col -> col + " IS NULL").collect(Collectors.joining(" AND "));
            return "(" + allNull + ") OR (" + allNotNull + ")";
        }
        throw new IllegalArgumentException("字段组完整性校验不支持的 fillStrategy：" + fillStrategy);
    }

    /**
     * 构建"违规"条件片段：默认对合规片段取反； 不忽略空值时，稽查字段为 NULL 也计为违规（与 qData 语义一致）。
     */
    @Override
    protected String buildInvalidFrag(AuditRuleContext context) {
        // 空值本身即检查对象，不叠加 IS NULL 判定
        return "NOT (" + buildValidFrag(context) + ")";
    }

}
