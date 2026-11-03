package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.PostgreSqlUtils;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/** PostgreSQL 协议写入器使用的双引号表关系和列列表。 */
final class PostgresCompatibleIdentifiers {

    /**
     * 初始化 {@code PostgresCompatibleIdentifiers}。
     */
    private PostgresCompatibleIdentifiers() {
    }

    /**
     * 返回表。
     */
    static String table(String schema, String table) {
        return PostgreSqlUtils.quoteIdentifier(schema) + '.' + PostgreSqlUtils.quoteIdentifier(table);
    }

    /**
     * 返回字段集合。
     */
    static String fields(List<String> fields) {
        if (fields == null || fields.isEmpty() || new HashSet<>(fields).size() != fields.size()) {
            throw new IllegalArgumentException("Target columns must be a non-empty list of distinct names");
        }
        return fields.stream().map(PostgreSqlUtils::quoteIdentifier).collect(Collectors.joining(","));
    }
}
