package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.product.mysql.MysqlCompatibleProfile;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** 游标读取与目标端幂等重放共享的 MySQL 协议目录凭据；产品事实来自对应配置。 */
public final class MysqlCursorMetadata {

    /**
     * 初始化 {@code MysqlCursorMetadata}。
     */
    private MysqlCursorMetadata() {
    }

    /**
     * 按MySQL方言转义并引用标识符。
     */
    public static String quote(String name) {
        return '`' + name.replace("`", "``") + '`';
    }

    /**
     * 生成当前操作引用的限定关系名。
     */
    public static String reference(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    public static void verifyProduct(Connection connection, MysqlCompatibleProfile profile,
            int timeout) throws SQLException {
        profile.verifyProduct(connection, timeout);
    }

    /**
     * 读取当前表的原生物理身份。
     */
    public static String physicalIdentity(Connection connection, MysqlCompatibleProfile profile, String schema,
            String table, int timeout) throws SQLException {
        return profile.physicalIdentity(connection, schema, table, timeout);
    }

    /** 通过产品字典视图获取 InnoDB 基表标识；调用方已验证产品。 */
    public static String innodbTableIdentity(Connection connection, ProductTypeEnum product, String view, String schema,
            String table, int timeout) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT ENGINE,TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !"InnoDB".equalsIgnoreCase(rows.getString(1)) || !"BASE TABLE".equals(
                        rows.getString(2)) || rows.next()) {
                    throw new SQLException("Cursor requires an InnoDB base table");
                }
            }
        }
        try (var statement = connection.prepareStatement(
                "SELECT TABLE_ID FROM information_schema." + view + " WHERE NAME=CAST(? AS BINARY)")) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, schema + "/" + table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("InnoDB physical table identity is unavailable");
                }
                String id = rows.getString(1);
                if (id == null || !id.matches("[1-9][0-9]*") || rows.next()) {
                    throw new SQLException("InnoDB table identity is ambiguous");
                }
                return product + ":INNODB_TABLE_ID_V1:" + id;
            }
        }
    }

    public record Column(String name, String nativeType, String dataType, boolean nullable, Integer precision,
                         Integer scale, String charset, String collation, String extra, String generated,
                         String defaultValue, Integer temporalPrecision) {

        /**
         * 按当前值域约定解析或转换无符号数值。
         */
        public boolean unsigned() {
            return nativeType.toLowerCase(Locale.ROOT).contains(" unsigned");
        }

        /**
         * 根据当前类型及元数据证据确定允许的值域。
         */
        public CursorKeyContract.Domain domain() {
            int bits = switch (dataType.toLowerCase(Locale.ROOT)) {
                case "tinyint" -> 8;
                case "smallint" -> 16;
                case "mediumint" -> 24;
                case "int" -> 32;
                case "bigint" -> 64;
                default -> 0;
            };
            if (bits != 0) {
                return CursorKeyContract.Domain.integer(bits, unsigned());
            }
            if (dataType.equalsIgnoreCase("decimal") && precision != null && scale != null) {
                return CursorKeyContract.Domain.decimal(precision, scale, unsigned());
            }
            return CursorKeyContract.Domain.nativeOnly();
        }
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    public static Map<String, Column> columns(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        Map<String, Column> columns = new LinkedHashMap<>();
        String sql = "SELECT COLUMN_NAME,COLUMN_TYPE,DATA_TYPE,IS_NULLABLE,NUMERIC_PRECISION,NUMERIC_SCALE,"
                + "CHARACTER_SET_NAME,COLLATION_NAME,EXTRA,GENERATION_EXPRESSION,COLUMN_DEFAULT,DATETIME_PRECISION "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (columns.size() >= 2000) {
                        throw new SQLException("Cursor column budget exceeded");
                    }
                    Column column = new Column(rows.getString(1), rows.getString(2), rows.getString(3),
                            "YES".equals(rows.getString(4)),
                            rows.getObject(5, Integer.class), rows.getObject(6, Integer.class), rows.getString(7),
                            rows.getString(8),
                            rows.getString(9), rows.getString(10), rows.getString(11),
                            rows.getObject(12, Integer.class));
                    if (columns.putIfAbsent(column.name(), column) != null) {
                        throw new SQLException("Ambiguous cursor column");
                    }
                }
            }
        }
        return columns;
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    public static CursorKeyContract keyContract(Connection connection, MysqlCompatibleProfile profile, String schema,
            String table, List<String> keys, int timeout) throws SQLException {
        profile.verifyProduct(connection, timeout);
        ProductTypeEnum product = profile.product();
        Map<String, Column> columns = columns(connection, schema, table, timeout);
        StringBuilder material = new StringBuilder("MYSQL_CURSOR_KEY_V1").append('\0').append(product)
                .append('\0').append(connection.getMetaData().getDatabaseProductVersion());
        String comparison = profile.keyComparisonProfile(connection, timeout);
        if (comparison != null) {
            material.append('\0').append(comparison);
        }
        List<CursorKeyContract.Domain> domains = new ArrayList<>();
        for (String key : keys) {
            Column column = columns.get(key);
            if (column == null || column.nullable() || column.generated() != null && !column.generated().isEmpty()) {
                throw new SQLException("Cursor key requires complete non-null ordinary columns");
            }
            material.append('\0').append(column.nativeType()).append('\0').append(column.charset()).append('\0')
                    .append(column.collation());
            domains.add(column.domain());
        }
        return new CursorKeyContract(product, DigestUtil.sha256Hex(material.toString()), domains);
    }

    /** 没有 TRIGGER 权限时，information_schema 结果为空不能证明没有触发器。 */
    public static void requireReplayWithoutTriggers(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        boolean visible = false;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
                while (rows.next()) {
                    var grant = java.util.regex.Pattern.compile("^GRANT (.+) ON (.+) TO .+$")
                            .matcher(rows.getString(1));
                    if (!grant.matches()) {
                        continue;
                    }
                    String privileges = grant.group(1), scope = grant.group(2);
                    if ((privileges.equals("ALL PRIVILEGES") || java.util.Arrays.stream(privileges.split(",\\s*"))
                            .anyMatch("TRIGGER"::equals))
                            && (scope.equals("*.*") || scope.equals(quote(schema) + ".*") || scope.equals(
                            reference(schema, table)))) {
                        visible = true;
                    }
                }
            }
        }
        if (!visible) {
            throw new SQLException("Cursor target trigger absence requires visible TRIGGER metadata");
        }
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE EVENT_OBJECT_SCHEMA=? AND EVENT_OBJECT_TABLE=?")) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != 0) {
                    throw new SQLException("Cursor replay of trigger side effects is unsupported");
                }
            }
        }
    }
}
