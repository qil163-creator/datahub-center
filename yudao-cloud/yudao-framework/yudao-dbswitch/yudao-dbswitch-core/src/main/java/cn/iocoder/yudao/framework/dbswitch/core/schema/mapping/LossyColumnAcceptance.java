package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 针对 LOSSY 映射的逐列显式决策。仅对 LOSSY 列调用；UNSUPPORTED 列
 * 绝不能接受。组件默认拒绝所有损失；由业务调用方决策并传入策略。
 */
@FunctionalInterface
public interface LossyColumnAcceptance {

    /**
     * 判断当前类型或能力是否接受给定输入。
     */
    boolean accepts(ColumnTypeMapping mapping);

    /**
     * 对当前全部候选项构造拒绝结果。
     */
    static LossyColumnAcceptance rejectAll() {
        return mapping -> false;
    }

    /**
     * 仅当某个精确物理列名报告的所有损失代码均已确认时，才接受该 LOSSY 列；
     * 同一列出现新的或不同损失时会再次拒绝。
     */
    static LossyColumnAcceptance acknowledged(Map<String, Set<String>> lossCodesByColumn) {
        var snapshot = new LinkedHashMap<String, Set<String>>();
        lossCodesByColumn.forEach(
                (column, codes) -> snapshot.put(java.util.Objects.requireNonNull(column), Set.copyOf(codes)));
        var acknowledged = Map.copyOf(snapshot);
        return mapping -> {
            var codes = acknowledged.get(mapping.column());
            return codes != null && !mapping.lossCodes().isEmpty() && codes.containsAll(mapping.lossCodes());
        };
    }
}
