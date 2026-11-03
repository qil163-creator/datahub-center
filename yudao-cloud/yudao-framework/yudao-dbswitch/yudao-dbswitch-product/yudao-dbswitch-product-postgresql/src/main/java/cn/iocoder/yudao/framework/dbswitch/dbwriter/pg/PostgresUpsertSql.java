package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/** PostgreSQL 唯一键核验与原子 UPSERT SQL；同一源码也供独立 Java 8 DataX 插件编译。 */
public final class PostgresUpsertSql {
    private PostgresUpsertSql() { }

    public static String quote(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0 || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 63)
            throw new IllegalArgumentException("PostgreSQL 标识符无效");
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    public static String target(String schema, String table) { return quote(schema) + "." + quote(table); }

    /** 调用方持有目标表写锁并负责同一连接的提交/回滚，不在此创建连接或提交。 */
    public static void verifyUniqueKey(Connection connection, String schema, String table,
                                       List<String> uniqueKeys, int timeoutSeconds) throws SQLException {
        if (uniqueKeys == null || uniqueKeys.isEmpty() || new HashSet<>(uniqueKeys).size() != uniqueKeys.size())
            throw new IllegalArgumentException("幂等写入必须指定不重复的唯一键");
        String sql = "SELECT i.indexrelid,k.ordinality,a.attname,a.attnotnull,"
                + "i.indisvalid AND i.indisready AND i.indislive AND i.indimmediate "
                + "AND i.indpred IS NULL AND i.indexprs IS NULL AND am.amname='btree' AND opc.opcdefault "
                + "AND i.indcollation[(k.ordinality-1)::int]=a.attcollation AS safe "
                + "FROM pg_catalog.pg_class t JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace "
                + "JOIN pg_catalog.pg_index i ON i.indrelid=t.oid AND i.indisunique "
                + "JOIN pg_catalog.pg_class ix ON ix.oid=i.indexrelid "
                + "JOIN pg_catalog.pg_am am ON am.oid=ix.relam "
                + "CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY k(attnum,ordinality) "
                + "LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum "
                + "LEFT JOIN pg_catalog.pg_opclass opc ON opc.oid=i.indclass[(k.ordinality-1)::int] "
                + "WHERE n.nspname=? AND t.relname=? AND t.relkind='r' AND k.ordinality<=i.indnkeyatts "
                + "ORDER BY i.indexrelid,k.ordinality";
        Map<Long, Set<String>> indexes = new HashMap<>();
        Set<Long> unsafe = new HashSet<>();
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong(1);
                    String name = rows.getString(3);
                    if (name == null || !rows.getBoolean(4) || !rows.getBoolean(5)) {
                        unsafe.add(id);
                    }
                    indexes.computeIfAbsent(id, ignored -> new HashSet<>()).add(name);
                }
            }
        }
        Set<String> expected = new HashSet<>(uniqueKeys);
        if (indexes.entrySet().stream()
                .noneMatch(entry -> !unsafe.contains(entry.getKey()) && entry.getValue().equals(expected))) {
            throw new SQLException("UPSERT requires an exact valid non-null ordinary unique key");
        }
    }

    /** 不写键列；相同值重放不产生无意义 UPDATE，避免重复修改更新时间。 */
    public static String sql(String schema, String table, List<String> fields, List<String> keys, List<String> holders) {
        if (fields == null || fields.isEmpty() || keys == null || keys.isEmpty()
                || !fields.containsAll(keys) || new HashSet<>(fields).size() != fields.size()
                || new HashSet<>(keys).size() != keys.size() || holders.size() != fields.size())
            throw new IllegalArgumentException("UPSERT 字段、唯一键或参数数量无效");
        List<String> updates = fields.stream().filter(name -> !keys.contains(name)).collect(Collectors.toList());
        String sql = "INSERT INTO " + target(schema, table) + " AS dh_target (" + quoted(fields) + ") VALUES ("
                + String.join(",", holders) + ") ON CONFLICT (" + quoted(keys) + ") ";
        if (updates.isEmpty()) return sql + "DO NOTHING";
        return sql + "DO UPDATE SET " + updates.stream().map(name -> quote(name) + "=EXCLUDED." + quote(name)).collect(Collectors.joining(","))
                + " WHERE ROW(" + updates.stream().map(name -> "dh_target." + quote(name)).collect(Collectors.joining(","))
                + ") IS DISTINCT FROM ROW(" + updates.stream().map(name -> "EXCLUDED." + quote(name)).collect(Collectors.joining(",")) + ")";
    }

    private static String quoted(List<String> fields) { return fields.stream().map(PostgresUpsertSql::quote).collect(Collectors.joining(",")); }
}
