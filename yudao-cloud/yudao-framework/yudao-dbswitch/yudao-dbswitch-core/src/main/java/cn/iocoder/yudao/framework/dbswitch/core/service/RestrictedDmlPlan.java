package cn.iocoder.yudao.framework.dbswitch.core.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 单条受限 DML 的解析结果。不表示已经授权执行，也不表示目标库已验证事务语义。
 */
public final class RestrictedDmlPlan {

    public enum Kind {
        UPDATE, DELETE, INSERT
    }

    private final Kind kind;

    private final Set<String> tableNames;

    private final String normalizedSql;

    /**
     * 初始化 {@code RestrictedDmlPlan}。
     */
    public RestrictedDmlPlan(Kind kind, Set<String> tableNames, String normalizedSql) {
        this.kind = kind;
        this.tableNames = Collections.unmodifiableSet(new LinkedHashSet<>(tableNames));
        this.normalizedSql = normalizedSql;
    }

    /**
     * 返回种类。
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * 返回表名称集合。
     */
    public Set<String> getTableNames() {
        return tableNames;
    }

    /**
     * 返回规范化SQL。
     */
    public String getNormalizedSql() {
        return normalizedSql;
    }

    /**
     * 要求当前操作只引用一个明确的目标表。
     */
    public String requireSingleTable() {
        if (tableNames.size() != 1) {
            throw new IllegalArgumentException("修复 DML 只允许操作一张已授权表");
        }
        return tableNames.iterator().next();
    }
}
