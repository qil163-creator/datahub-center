package cn.iocoder.yudao.framework.quality.core.clean;

import cn.iocoder.yudao.framework.quality.core.QualityStrategyMeta;
import cn.iocoder.yudao.framework.quality.core.clean.function.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 清洗函数注册表：strategyKey → 清洗函数
 *
 * @author Li
 */
public class CleanFunctionRegistry {

    private final Map<String, CleanFunction> functions = new LinkedHashMap<>();

    /**
     * 初始化 {@code CleanFunctionRegistry}。
     */
    public CleanFunctionRegistry() {
        register(new WithinBoundaryFunction());
        register(new ValueReplaceFunction());
        register(new PhoneFormatStdFunction());
        register(new DateFormatStdFunction());
        register(new FixToPrecisionFunction());
        register(new RemoveWhitespaceFunction());
        register(new AddPrefixSuffixFunction());
        register(new SimpleReplaceFunction());
        register(new StringSubstrFunction());
        register(new DateFillNullFunction());
        register(new RemoveEmptyCombinationFunction());
        register(new ToUppercaseFunction());
        register(new ToLowercaseFunction());
        register(new MenuCustomFunction());
        register(new PkDedupFirstFunction());
        register(new KeepLatestOrFirstFunction());
        register(new CheckExpirationFunction());
        for (var kind : TypeConversionCleanFunction.Kind.values()) {
            register(new TypeConversionCleanFunction(kind));
        }
        for (var kind : NullFillCleanFunction.Kind.values()) {
            register(new NullFillCleanFunction(kind));
        }
        register(new RemoveEmptyRowFunction());
        register(new UnitConversionFunction());
        for (var kind : TextStandardCleanFunction.Kind.values()) {
            register(new TextStandardCleanFunction(kind));
        }
        for (var kind : PreferredDedupCleanFunction.Kind.values()) {
            register(new PreferredDedupCleanFunction(kind));
        }
        for (var kind : InvalidValueCleanFunction.Kind.values()) {
            register(new InvalidValueCleanFunction(kind));
        }
        for (var kind : DateBoundaryCleanFunction.Kind.values()) {
            register(new DateBoundaryCleanFunction(kind));
        }
    }

    /**
     * 登记受管资源的停止回调及实际退出检查。
     */
    public void register(CleanFunction function) {
        functions.put(function.getStrategyKey(), function);
    }

    /**
     * 获取清洗函数，不存在返回 null（规则允许暂未接入引擎的策略）
     */
    public CleanFunction getFunction(String strategyKey) {
        return strategyKey == null ? null : functions.get(strategyKey);
    }

    /**
     * 检查当前集合或结构是否包含指定对象。
     */
    public boolean contains(String strategyKey) {
        return strategyKey != null && functions.containsKey(strategyKey);
    }

    /** 仅明确声明行级契约的函数允许进入一进一出的执行器，未知策略保守拒绝。 */
    public boolean supportsRowTransform(String strategyKey) {
        CleanFunction function = getFunction(strategyKey);
        return function != null && function.executionScope() == CleanFunction.ExecutionScope.ROW;
    }

    /**
     * 返回当前规则使用的目标列绑定信息。
     */
    public CleanFunction.ColumnBinding getColumnBinding(String strategyKey) {
        CleanFunction function = getFunction(strategyKey);
        if (function == null) {
            throw new IllegalArgumentException("清洗函数未注册：" + strategyKey);
        }
        return function.getColumnBinding();
    }

    /**
     * 返回引用列集合。
     */
    public List<String> getReferencedColumns(String strategyKey, Map<String, Object> config) {
        CleanFunction function = getFunction(strategyKey);
        if (function == null) {
            throw new IllegalArgumentException("清洗函数未注册：" + strategyKey);
        }
        return List.copyOf(function.referencedColumns(normalizeConfig(strategyKey, config)));
    }

    /** 返回已注册函数的规范化有界配置；函数未知时失败。 */
    public Map<String, Object> normalizeConfig(String strategyKey, Map<String, Object> config) {
        CleanFunction function = getFunction(strategyKey);
        if (function == null) {
            throw new IllegalArgumentException("清洗函数未注册：" + strategyKey);
        }
        Map<String, Object> normalized = function.normalizeConfig(config);
        if (function.getColumnBinding() == CleanFunction.ColumnBinding.SINGLE
                && normalized.containsKey("columns")
                && (!(normalized.get("columns") instanceof List<?> columns) || columns.size() != 1)) {
            throw new IllegalArgumentException("该清洗函数必须恰好绑定一个字段");
        }
        return normalized;
    }

    /** 调用方按每条规则重置配额；函数必须在派生结果保留前触发计价，并在循环中检查取消。 */
    public List<Map<String, Object>> applyControlled(String strategyKey, List<Map<String, Object>> rows,
            Map<String, Object> config, java.util.function.Consumer<Map<String, Object>> retainOutput,
            Runnable checkpoint) {
        CleanFunction function = getFunction(strategyKey);
        if (function == null) {
            throw new IllegalArgumentException("清洗函数未注册：" + strategyKey);
        }
        return CleanExecutionControl.scoped(retainOutput, checkpoint, () -> function.apply(rows, config));
    }

    /**
     * 全部策略元数据
     */
    public List<QualityStrategyMeta> listStrategies() {
        List<QualityStrategyMeta> result = new ArrayList<>();
        for (CleanFunction function : functions.values()) {
            result.add(new QualityStrategyMeta(function.getStrategyKey(), function.getStrategyName(),
                    null, function.getConfigExample()));
        }
        return result;
    }

}
