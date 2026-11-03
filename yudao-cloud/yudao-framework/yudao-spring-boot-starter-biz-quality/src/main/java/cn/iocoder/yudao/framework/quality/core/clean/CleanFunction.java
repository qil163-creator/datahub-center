package cn.iocoder.yudao.framework.quality.core.clean;

import java.util.List;
import java.util.Map;

/**
 * 清洗规则函数：对行数据（List&lt;Map&gt;，与 ETL 引擎的行模型一致）做标准化处理
 *
 * <p>参考 qData 清洗引擎（CleanTransition）的策略语义，改为纯 Java 内存实现，
 * 单条规则既可处理值级转换（大小写、格式化），也可处理行集级操作（去重、删行）。
 *
 * @author Li
 */
public interface CleanFunction {

    /** 行级函数保持一进一出且只修改绑定字段；集合级函数须由集合执行阶段调用。 */
    enum ExecutionScope {ROW, ROW_SET}

    /** 字段绑定与执行范围独立：集合规则也可能只处理一个字段。 */
    enum ColumnBinding {SINGLE, MULTIPLE, ALL}

    /**
     * 返回当前规则使用的目标列绑定信息。
     */
    default ColumnBinding getColumnBinding() {
        return ColumnBinding.SINGLE;
    }

    /** 目标字段之外还必须存在的排序、优先级或更新字段。 */
    default List<String> referencedColumns(Map<String, Object> config) {
        return List.of();
    }

    /** 未证明边界的扩展函数默认按集合级处理，不能自动进入逐行流水线。 */
    default ExecutionScope executionScope() {
        return ExecutionScope.ROW_SET;
    }

    /** 策略标识（与规则表 strategy_key 对应） */
    String getStrategyKey();

    /** 策略名称 */
    String getStrategyName();

    /** 规则配置 JSON 示例 */
    String getConfigExample();

    /** 规范化并校验已持久化的函数配置；实现必须拒绝未知键。 */
    default Map<String, Object> normalizeConfig(Map<String, Object> config) {
        return CleanFunctionConfigSupport.checked(config, java.util.Set.of("columns"));
    }

    /**
     * 执行清洗
     *
     * @param rows   行数据（不会被原地修改，返回新集合）
     * @param config 规则配置（含 columns 目标字段等）
     * @return 清洗后的行数据
     */
    List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config);

}
