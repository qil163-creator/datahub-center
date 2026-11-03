package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.iocoder.yudao.framework.quality.core.clean.*;

import java.util.*;

/** 检查整行所有实际字段，不使用任意占位字段模拟全行规则。 */
public final class RemoveEmptyRowFunction extends ConfiguredCleanFunction {

    /**
     * 初始化 {@code RemoveEmptyRowFunction}及其父类状态。
     */
    public RemoveEmptyRowFunction() {
        super("REMOVE_EMPTY_ROW", "全字段为空删除", "{}");
    }

    /**
     * 未证明边界的扩展函数默认按集合级处理，不能自动进入逐行流水线。
     */
    @Override
    public ExecutionScope executionScope() {
        return ExecutionScope.ROW_SET;
    }

    /**
     * 返回当前规则使用的目标列绑定信息。
     */
    @Override
    public ColumnBinding getColumnBinding() {
        return ColumnBinding.ALL;
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> input) {
        Map<String, Object> config = new LinkedHashMap<>(input == null ? Map.of() : input);
        Object columns = config.remove("columns");
        if (columns != null && (!(columns instanceof List<?> values) || !values.isEmpty())) {
            throw new IllegalArgumentException("全字段为空删除不接受指定字段绑定");
        }
        return checked(config);
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        normalizeConfig(config);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row : rows) {
            CleanExecutionControl.checkpoint();
            if (row.values().stream().anyMatch(value -> !empty(value))) {
                CleanExecutionControl.retainOutput(row);
                result.add(row);
            }
        }
        return result;
    }
}
