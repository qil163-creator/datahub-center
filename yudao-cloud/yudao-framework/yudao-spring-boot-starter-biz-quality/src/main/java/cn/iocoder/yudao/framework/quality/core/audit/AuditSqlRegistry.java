package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.quality.core.QualityStrategyMeta;
import cn.iocoder.yudao.framework.quality.core.audit.generator.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 稽查 SQL 生成器注册表：strategyKey → 生成器
 *
 * @author Li
 */
public class AuditSqlRegistry {

    private final Map<String, AuditSqlGenerator> generators = new LinkedHashMap<>();

    /**
     * 初始化 {@code AuditSqlRegistry}。
     */
    public AuditSqlRegistry() {
        register(new CharacterValidationGenerator());
        register(new LengthValidationGenerator());
        register(new DecimalPrecisionValidationGenerator());
        register(new EnumValidationGenerator());
        register(new NumericRangeValidationGenerator());
        register(new TimeOrderValidationGenerator());
        register(new GroupFieldCompletenessGenerator());
        register(new CompositeUniquenessValidationGenerator());
        register(new MutualExclusionValidationGenerator());
        // 融合四系统新增的规则生成器
        register(new NotNullValidationGenerator());
        register(new RegexValidationGenerator());
        register(new DateFormatValidationGenerator());
        register(new UniquenessValidationGenerator());
        register(new ForeignKeyValidationGenerator());
        register(new PlaceholderValidationGenerator());
        register(new TimelinessValidationGenerator());
        register(new CrossTableConsistencyGenerator());
        register(new NumericRelationValidationGenerator());
        register(new ConditionalRequiredValidationGenerator());
        register(new SensitiveMaskValidationGenerator());
        register(new RecordCompletenessValidationGenerator());
        register(new NumericOutlierValidationGenerator());
        register(new StateConsistencyValidationGenerator());
        register(new ModelStructureValidationGenerator());
        register(new ColumnMetadataCompletenessGenerator());
    }

    /**
     * 登记受管资源的停止回调及实际退出检查。
     */
    public void register(AuditSqlGenerator generator) {
        generators.put(generator.getStrategyKey(), generator);
    }

    /**
     * 获取生成器，不存在返回 null（规则允许暂未接入引擎的策略）
     */
    public AuditSqlGenerator getGenerator(String strategyKey) {
        return strategyKey == null ? null : generators.get(strategyKey);
    }

    /**
     * 检查当前集合或结构是否包含指定对象。
     */
    public boolean contains(String strategyKey) {
        return strategyKey != null && generators.containsKey(strategyKey);
    }

    /**
     * 校验并规范化当前策略的配置。
     */
    public Map<String, Object> normalizeConfig(String strategyKey, Map<String, Object> config) {
        AuditSqlGenerator generator = getGenerator(strategyKey);
        if (generator == null) {
            throw new IllegalArgumentException("Unknown audit strategy: " + strategyKey);
        }
        return generator.normalizeConfig(config);
    }

    /**
     * 全部策略元数据
     */
    public List<QualityStrategyMeta> listStrategies() {
        List<QualityStrategyMeta> result = new ArrayList<>();
        for (AuditSqlGenerator generator : generators.values()) {
            result.add(new QualityStrategyMeta(generator.getStrategyKey(), generator.getStrategyName(),
                    null, generator.getConfigExample()));
        }
        return result;
    }

}
