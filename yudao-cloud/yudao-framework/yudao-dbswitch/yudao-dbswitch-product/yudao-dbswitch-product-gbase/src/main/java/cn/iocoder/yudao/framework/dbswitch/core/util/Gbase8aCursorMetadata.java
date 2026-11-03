package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GBase 8a 8.6.2.43 游标结构：仅支持 EXPRESS 基本表，按水位优先的 ORDER BY 作为键集顺序，且
 * 必须实际验证有序列在数据层面的唯一性（GBase 8a 仅将 PRIMARY KEY 保留为元数据，绝不强制
 * 唯一性）。水位和键列仅接受精确数值、精确民用时间及 VARCHAR 文本；文本键
 * 仅在完全相同的原生契约下重放。已验证服务器的 BIGINT 范围为 [-9223372036854775806, 9223372036854775806]，
 * 窄于标准有符号 64 位值域。
 */
public final class Gbase8aCursorMetadata {

    public static final String VERIFIED_SERVER = "gbase-8.6.2.43-";

    /**
     * 初始化 {@code Gbase8aCursorMetadata}。
     */
    private Gbase8aCursorMetadata() {
    }

    /**
     * 按当前数据库方言转义并引用标识符。
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

    public record Column(String name, String nativeType, String dataType, boolean nullable, Integer precision,
                         Integer scale, String charset, String collation, String extra, String defaultValue,
                         String columnComment) {

        /**
         * 按当前值域约定解析或转换无符号数值。
         */
        public boolean unsigned() {
            return nativeType.toLowerCase(Locale.ROOT).contains("unsigned");
        }
    }

    public record Shape(String schema, String table, String engine, String tableType, String tableComment,
                        String createTime, String createDdl, String version, String physicalTableId,
                        Map<String, Column> columns) {

        /**
         * 初始化 {@code Shape}。
         */
        public Shape {
            quote(schema);
            quote(table);
            columns = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        }

        /**
         * 返回列。
         */
        public Column column(String name) {
            return columns.get(name);
        }

        /** 完整关系依据；DROP/CREATE、ALTER 或引擎改变必须使持久化游标计划失效。 */
        public String relationEvidence() {
            // CREATE_TIME 会随普通 DML/锁刷新；只保留为观测事实，身份使用原厂 TABLE_ID。
            return "GBASE8A_RELATION_V2\0" + version + '\0' + engine + '\0' + tableType + '\0' + tableComment
                    + '\0' + physicalTableId + '\0' + DigestUtil.sha256Hex(createDdl == null ? "" : createDdl);
        }

        /**
         * 根据已观测列属性生成稳定的列结构证据。
         */
        public String columnEvidence() {
            var text = new StringBuilder("GBASE8A_COLUMNS_V1");
            for (var column : columns.values()) {
                text.append('\0').append(column.name()).append('\0').append(column.nativeType())
                        .append('\0').append(column.dataType()).append('\0').append(column.nullable()).append('\0')
                        .append(column.precision()).append('\0').append(column.scale()).append('\0')
                        .append(column.charset()).append('\0').append(column.collation());
            }
            return text.toString();
        }

        /**
         * 根据服务端版本和键列定义生成游标键证据。
         */
        public String keyEvidence(List<String> keys) {
            var text = new StringBuilder("GBASE8A_CURSOR_KEY_V1\0").append(VERIFIED_SERVER).append('\0')
                    .append(version);
            for (String key : keys) {
                var column = columns.get(key);
                text.append('\0').append(column.nativeType()).append('\0').append(column.charset()).append('\0')
                        .append(column.collation());
            }
            return text.toString();
        }

        /**
         * 返回当前源端读取契约。
         */
        public CursorKeyContract sourceContract(List<String> keys) {
            var domains = new ArrayList<CursorKeyContract.Domain>();
            for (String key : keys) {
                domains.add(domain(columns.get(key)));
            }
            return new CursorKeyContract(ProductTypeEnum.GBASE8A, DigestUtil.sha256Hex(keyEvidence(keys)), domains);
        }
    }

    /** 精确键值域；已验证服务器的 GBase 8a BIGINT 两端均比标准有符号 64 位边界少两个值。 */
    public static CursorKeyContract.Domain domain(Column column) {
        int bits = switch (column.dataType().toLowerCase(Locale.ROOT)) {
            case "tinyint" -> 8;
            case "smallint" -> 16;
            case "mediumint" -> 24;
            case "int" -> 32;
            case "bigint" -> 64;
            default -> 0;
        };
        if (bits == 64) {
            BigDecimal maximum = new BigDecimal("9223372036854775806");
            return new CursorKeyContract.Domain(
                    new CursorKeyContract.Numeric(column.unsigned() ? BigDecimal.ZERO : maximum.negate(), maximum, 0));
        }
        if (bits != 0) {
            return CursorKeyContract.Domain.integer(bits, column.unsigned());
        }
        if (column.dataType().equalsIgnoreCase("decimal") && column.precision() != null && column.scale() != null) {
            return CursorKeyContract.Domain.decimal(column.precision(), column.scale(), column.unsigned());
        }
        if (List.of("datetime", "date", "varchar").contains(column.dataType().toLowerCase(Locale.ROOT))) {
            return CursorKeyContract.Domain.nativeOnly();
        }
        throw unsupportedArgument("GBase 8a cursor key type is not verified");
    }

    /**
     * 判断当前类型是否处于已支持的游标键值域。
     */
    public static boolean supportedKeyType(Column column) {
        return switch (column.dataType().toLowerCase(Locale.ROOT)) {
            case "tinyint", "smallint", "mediumint", "int", "bigint", "decimal", "datetime", "date", "varchar" -> true;
            default -> false;
        };
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    public static void verifyProduct(Connection connection, int timeout) throws SQLException {
        if (connection == null || timeout < 1) {
            throw unsupported("GBase 8a cursor requires a bounded connection");
        }
        var metadata = connection.getMetaData();
        boolean valid = "GBase".equalsIgnoreCase(metadata.getDatabaseProductName())
                && metadata.getDatabaseMajorVersion() == 8 && metadata.getDatabaseMinorVersion() == 6
                && metadata.getDatabaseProductVersion() != null
                && metadata.getDatabaseProductVersion().contains("8.6.2.43")
                && metadata.getDriverName() != null && metadata.getDriverName().startsWith("GBase JDBC Driver");
        if (!valid) {
            throw unsupported("Cursor requires the verified GBase 8a 8.6.2.43 server product");
        }
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Shape read(Connection connection, String schema, String table, int timeout) throws SQLException {
        verifyProduct(connection, timeout);
        String engine = null, tableType = null, tableComment = null, createTime = null, physicalTableId = null;
        try (var statement = connection.prepareStatement("SELECT ENGINE,TABLE_TYPE,TABLE_COMMENT,CREATE_TIME,TABLE_ID "
                + "FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("GBase 8a cursor requires one EXPRESS base table");
                }
                engine = rows.getString(1);
                tableType = rows.getString(2);
                tableComment = rows.getString(3);
                createTime = rows.getString(4);
                physicalTableId = rows.getString(5);
                if (physicalTableId == null || physicalTableId.isBlank()) {
                    throw unsupported("GBase physical table identity is unavailable");
                }
                if (!"EXPRESS".equalsIgnoreCase(engine) || !"BASE TABLE".equals(tableType) || rows.next()) {
                    throw unsupported("GBase 8a cursor requires one EXPRESS base table");
                }
            }
        }
        String createDdl = null;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery("SHOW CREATE TABLE " + reference(schema, table))) {
                if (!rows.next() || rows.getString(2) == null || rows.getString(2).isBlank() || rows.getString(2)
                        .length() > 1024 * 1024) {
                    throw unsupported("GBase 8a table DDL is unavailable");
                }
                createDdl = rows.getString(2);
                if (rows.next()) {
                    throw unsupported("GBase 8a table DDL is ambiguous");
                }
            }
        }
        return new Shape(schema, table, engine, tableType, tableComment, createTime, createDdl,
                connection.getMetaData().getDatabaseProductVersion(), physicalTableId,
                columns(connection, schema, table, timeout));
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    public static Map<String, Column> columns(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        Map<String, Column> columns = new LinkedHashMap<>();
        String sql = "SELECT COLUMN_NAME,COLUMN_TYPE,DATA_TYPE,IS_NULLABLE,NUMERIC_PRECISION,NUMERIC_SCALE,"
                + "CHARACTER_SET_NAME,COLLATION_NAME,EXTRA,COLUMN_DEFAULT,COLUMN_COMMENT "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (columns.size() >= 2000) {
                        throw unsupported("GBase 8a cursor column budget exceeded");
                    }
                    Integer precision = null, scale = null;
                    if (rows.getObject(5) instanceof Number number) {
                        precision = number.intValue();
                    }
                    if (rows.getObject(6) instanceof Number number) {
                        scale = number.intValue();
                    }
                    Column column = new Column(rows.getString(1), rows.getString(2), rows.getString(3),
                            "YES".equals(rows.getString(4)),
                            precision, scale, rows.getString(7), rows.getString(8),
                            rows.getString(9), rows.getString(10), rows.getString(11));
                    if (columns.putIfAbsent(column.name(), column) != null) {
                        throw unsupported("Ambiguous GBase 8a cursor column");
                    }
                }
            }
        }
        if (columns.isEmpty()) {
            throw unsupported("GBase 8a cursor columns are unavailable");
        }
        return columns;
    }

    /** GBase 8a 绝不强制 PRIMARY KEY 唯一性：必须根据实际数据证明有序列非空且唯一。 */
    public static void requireUniqueKeys(Connection connection, String reference, List<String> keys,
            int timeout) throws SQLException {
        String sql = "SELECT 1 FROM " + reference + " GROUP BY " + String.join(",",
                keys.stream().map(Gbase8aCursorMetadata::quote).toList())
                + " HAVING COUNT(*)>1 LIMIT 1";
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery(sql)) {
                if (rows.next()) {
                    throw unsupported("GBase 8a cursor ordered columns are not proven unique on the actual data");
                }
            }
        }
    }

    /** 投影列的精确源值；时间值为民用时间，绝不是纪元时间。 */
    public static Object readValue(ResultSet rows, int index, Column column) throws SQLException {
        Object value = rows.getObject(index);
        if (value == null) {
            return null;
        }
        if (column.dataType().equalsIgnoreCase("datetime")) {
            if (value instanceof java.sql.Timestamp timestamp) {
                return timestamp.toLocalDateTime();
            }
            if (value instanceof LocalDateTime time) {
                return time;
            }
            return value;
        }
        if (column.dataType().equalsIgnoreCase("date")) {
            if (value instanceof java.sql.Date date) {
                return date.toLocalDate();
            }
            if (value instanceof LocalDate date) {
                return date;
            }
            return value;
        }
        if (column.dataType().equalsIgnoreCase("time")) {
            if (value instanceof java.sql.Time time) {
                return time.toLocalTime();
            }
            if (value instanceof LocalTime time) {
                return time;
            }
            return value;
        }
        if (column.dataType().equalsIgnoreCase("bigint") && value instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        return value;
    }

    /**
     * 返回游标值。
     */
    public static TypedCursorValue cursorValue(Column column, Object value) {
        return TypedCursorValue.of(cursorType(column), column.nativeType(), value);
    }

    /** 校验已持久化组件，并返回规范化比较值。 */
    public static Object cursorComponent(Column column, TypedCursorValue value) {
        if (value == null || value.jdbcType() != cursorType(column) || !value.typeName().equals(column.nativeType())) {
            throw new IllegalArgumentException("GBase 8a cursor tuple type changed");
        }
        Object decoded = value.jdbcValue();
        if (column.dataType().equalsIgnoreCase("bigint")) {
            long integer;
            if (decoded instanceof Long longValue) {
                integer = longValue;
            } else if (decoded instanceof BigInteger big) {
                integer = big.longValueExact();
            } else {
                throw new IllegalArgumentException("GBase 8a cursor key is not an exact integer");
            }
            if (integer > 9223372036854775806L || integer < -9223372036854775806L) {
                throw new IllegalArgumentException("GBase 8a cursor key is outside its native range");
            }
            return integer;
        }
        if (decoded instanceof BigDecimal decimal) {
            int scale = column.scale() == null ? 0 : column.scale(), precision = column.precision() == null ? scale : column.precision();
            if (decimal.scale() > scale || decimal.precision() - decimal.scale() > precision - scale) {
                throw new IllegalArgumentException("GBase 8a cursor key is outside its native range");
            }
            return decimal;
        }
        return decoded;
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    public static void bind(PreparedStatement statement, int index, Column column, Object value) throws SQLException {
        if (value instanceof BigDecimal decimal) {
            statement.setBigDecimal(index, decimal);
        } else if (value instanceof LocalDateTime time) {
            statement.setTimestamp(index, java.sql.Timestamp.valueOf(time));
        } else if (value instanceof LocalDate date) {
            statement.setDate(index, java.sql.Date.valueOf(date));
        } else if (value instanceof LocalTime time) {
            statement.setTime(index, java.sql.Time.valueOf(time));
        } else {
            statement.setObject(index, value, cursorType(column));
        }
    }

    /**
     * 返回游标类型。
     */
    private static int cursorType(Column column) {
        return switch (column.dataType().toLowerCase(Locale.ROOT)) {
            case "tinyint" -> Types.TINYINT;
            case "smallint" -> Types.SMALLINT;
            case "mediumint", "int" -> Types.INTEGER;
            case "bigint" -> Types.BIGINT;
            case "decimal" -> Types.NUMERIC;
            case "date" -> Types.DATE;
            case "datetime" -> Types.TIMESTAMP;
            case "varchar" -> Types.VARCHAR;
            default -> throw new IllegalArgumentException("GBase 8a cursor key type is unsupported");
        };
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    static IllegalArgumentException unsupportedArgument(String reason) {
        return new IllegalArgumentException(reason);
    }
}
