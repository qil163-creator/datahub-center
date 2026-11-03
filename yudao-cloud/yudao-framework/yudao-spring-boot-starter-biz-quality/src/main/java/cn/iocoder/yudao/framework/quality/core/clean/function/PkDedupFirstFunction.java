package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;
import cn.iocoder.yudao.framework.quality.core.clean.CleanExecutionControl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按主键去重（保留首条）：按唯一标识字段去重，保留首次出现的记录
 *
 * @author Li
 */
public class PkDedupFirstFunction implements CleanFunction {

    /**
     * 返回当前规则使用的目标列绑定信息。
     */
    @Override
    public ColumnBinding getColumnBinding() {
        return ColumnBinding.MULTIPLE;
    }

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "PK_DEDUP_FIRST";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "按主键去重（保留首条）";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"customer_id\"]}";
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        List<String> columns = CleanValueSupport.requiredColumns(config);
        Set<List<?>> seen = new HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            CleanExecutionControl.checkpoint();
            List<?> key = CleanValueSupport.compositeKey(row, columns);
            if (!seen.contains(key)) {
                CleanExecutionControl.retainOutput(row);
                seen.add(key);
                result.add(row);
            }
        }
        return result;
    }

}
