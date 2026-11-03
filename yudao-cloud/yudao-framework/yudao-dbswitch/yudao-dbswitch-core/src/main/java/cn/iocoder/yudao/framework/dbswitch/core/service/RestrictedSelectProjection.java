package cn.iocoder.yudao.framework.dbswitch.core.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 执行前受限投影的结果：可直接执行的 SQL，以及它实际选择和引用的物理列。
 *
 * <p>本结果只表示“技术上已限制到给定列集合”，不表示调用方已完成对象授权、行级策略或脱敏。
 *
 * @param sql               重建后的可执行 SQL，标识符按目标产品方言引用
 * @param selectedColumns   输出列（通配符已展开）
 * @param referencedColumns 全部被引用列，包含过滤和排序中出现的列
 */
public record RestrictedSelectProjection(String sql, Set<String> selectedColumns, Set<String> referencedColumns) {

    /**
     * 初始化 {@code RestrictedSelectProjection}。
     */
    public RestrictedSelectProjection {
        selectedColumns = Collections.unmodifiableSet(new LinkedHashSet<>(selectedColumns));
        referencedColumns = Collections.unmodifiableSet(new LinkedHashSet<>(referencedColumns));
    }
}
