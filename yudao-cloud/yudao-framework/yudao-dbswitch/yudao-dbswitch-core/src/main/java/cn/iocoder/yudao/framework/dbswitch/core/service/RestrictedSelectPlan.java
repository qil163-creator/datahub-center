package cn.iocoder.yudao.framework.dbswitch.core.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 单条受限 SELECT 的解析结果。不表示已经授权执行。
 */
public final class RestrictedSelectPlan {

    private final String schemaName;

    private final String tableName;

    private final boolean wildcard;

    private final Set<String> sourceColumns;

    private final String normalizedSql;

    /**
     * 初始化 {@code RestrictedSelectPlan}。
     */
    public RestrictedSelectPlan(String schemaName, String tableName, boolean wildcard,
            Set<String> sourceColumns, String normalizedSql) {
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.wildcard = wildcard;
        this.sourceColumns = Collections.unmodifiableSet(new LinkedHashSet<>(sourceColumns));
        this.normalizedSql = normalizedSql;
    }

    /**
     * 返回模式名。
     */
    public String getSchemaName() {
        return schemaName;
    }

    /**
     * 返回表名。
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * 返回通配符。
     */
    public boolean isWildcard() {
        return wildcard;
    }

    /**
     * 返回源列集合。
     */
    public Set<String> getSourceColumns() {
        return sourceColumns;
    }

    /**
     * 返回规范化SQL。
     */
    public String getNormalizedSql() {
        return normalizedSql;
    }
}
