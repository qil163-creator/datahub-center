package cn.iocoder.yudao.framework.dbswitch.product.clickhouse;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** 此产品的原生读取器、写入器和变更同步器共享的 SQL。 */
public final class ClickhouseSql {

    /**
     * 初始化 {@code ClickhouseSql}。
     */
    private ClickhouseSql() {
    }

    /**
     * 按ClickHouse方言转义并引用标识符。
     */
    public static String quote(String name) {
        // RestrictedSelectGuard 有意只接受普通投影名称。原生表
        // 迁移还需要带引号的物理名称；不得因此扩大该防护的 SQL 准入规则。
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0
                || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255
                || !name.equals(new String(name.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid ClickHouse physical identifier");
        }
        return "`" + name.replace("\\", "\\\\").replace("`", "\\`") + "`";
    }

    /**
     * 返回表。
     */
    public static String table(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    public static String columns(List<String> names) {
        if (names == null || names.isEmpty() || names.stream().distinct().count() != names.size()) {
            throw new IllegalArgumentException("ClickHouse requires distinct physical columns");
        }
        return names.stream().map(ClickhouseSql::quote).collect(Collectors.joining(","));
    }

    /**
     * 返回键集合。
     */
    public static String keys(List<String> names) {
        columns(names);
        return names.stream().map(name -> quote(name) + "=?").collect(Collectors.joining(" AND "));
    }

    /**
     * 按当前定义插入一条或一批数据。
     */
    public static String insert(String schema, String table, List<String> fields) {
        return "INSERT INTO " + table(schema, table) + " (" + columns(fields)
                + ") SETTINGS async_insert=0, wait_for_async_insert=1 VALUES ("
                + String.join(",", Collections.nCopies(fields.size(), "?")) + ")";
    }
}
