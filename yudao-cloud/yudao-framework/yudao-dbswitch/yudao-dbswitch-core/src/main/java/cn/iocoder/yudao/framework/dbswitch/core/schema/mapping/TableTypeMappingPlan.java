package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 在执行任何 DDL 文本前完成整表决策。只要有一个 UNSUPPORTED 列，或一个 LOSSY 列
 * 未获调用方 {@link LossyColumnAcceptance} 接受，就拒绝整张表。
 */
public record TableTypeMappingPlan(ProductTypeEnum targetProduct, List<ColumnTypeMapping> columns,
                                   Set<String> acceptedLossyColumns) {

    /**
     * 校验构造参数并初始化 {@code TableTypeMappingPlan}。
     */
    public TableTypeMappingPlan {
        Objects.requireNonNull(targetProduct);
        columns = List.copyOf(columns);
        acceptedLossyColumns = Set.copyOf(acceptedLossyColumns);
        var names = new java.util.HashSet<String>();
        for (var column : columns) {
            if (column.targetProduct() != targetProduct || !names.add(column.column())) {
                throw new IllegalArgumentException("Invalid table mapping plan");
            }
        }
        for (var accepted : acceptedLossyColumns) {
            if (columns.stream()
                    .noneMatch(column -> column.column().equals(accepted) && column.fidelity() == TypeFidelity.LOSSY)) {
                throw new IllegalArgumentException("Only LOSSY columns can be accepted");
            }
        }
    }

    /**
     * 返回当前保真度。
     */
    public TypeFidelity fidelity() {
        return columns.stream().map(ColumnTypeMapping::fidelity).reduce(TypeFidelity.EXACT, TypeFidelity::worst);
    }

    /**
     * 返回未通过类型映射准入的列集合。
     */
    public List<ColumnTypeMapping> rejectedColumns() {
        return columns.stream().filter(column -> column.fidelity() == TypeFidelity.UNSUPPORTED
                || column.fidelity() == TypeFidelity.LOSSY && !acceptedLossyColumns.contains(column.column())).toList();
    }

    /**
     * 判断当前类型映射计划是否全部通过准入。
     */
    public boolean accepted() {
        return rejectedColumns().isEmpty();
    }

    /**
     * 检查当前操作已经通过能力或类型准入。
     */
    public TableTypeMappingPlan requireAccepted() {
        if (!accepted()) {
            throw new TypeMappingRejectedException(this);
        }
        return this;
    }

    /**
     * 返回列。
     */
    public ColumnTypeMapping column(String name) {
        return columns.stream().filter(column -> column.column().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Column is not part of the mapping plan"));
    }

    /** 已接受计划中渲染后的列子句。 */
    public String definition(String column) {
        requireAccepted();
        return column(column).definition();
    }

    /**
     * 返回按列记录的类型映射保真度。
     */
    public Map<String, TypeFidelity> fidelityByColumn() {
        var result = new LinkedHashMap<String, TypeFidelity>();
        columns.forEach(column -> result.put(column.column(), column.fidelity()));
        return java.util.Collections.unmodifiableMap(result);
    }
}
