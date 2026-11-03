package cn.iocoder.yudao.framework.dbswitch.product.tdengine;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** TDengine 原生值和确认契约；不模拟 JDBC 事务。 */
public final class TdengineJdbcSupport {

    /**
     * 初始化 {@code TdengineJdbcSupport}。
     */
    private TdengineJdbcSupport() {
    }

    public record Column(String name, int jdbcType, String nativeType, int precision, int scale) {

    }

    public record Table(List<Column> columns, List<String> keys, int fractionalDigits, String kind) {

        /**
         * 使用给定参数初始化 {@code Table}。
         */
        public Table(List<Column> columns, List<String> keys, int fractionalDigits) {
            this(columns, keys, fractionalDigits, "NORMAL_TABLE");
        }

        /**
         * 返回列。
         */
        public Column column(String name) {
            return columns.stream().filter(column -> column.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("TDengine physical column is absent"));
        }
    }

    /**
     * 按TDengine方言转义并引用标识符。
     */
    public static String quote(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.TDENGINE, name);
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
    public static String columns(List<String> fields) {
        if (fields == null || fields.isEmpty() || fields.stream().distinct().count() != fields.size()) {
            throw new IllegalArgumentException("TDengine requires distinct physical columns");
        }
        return fields.stream().map(TdengineJdbcSupport::quote).collect(Collectors.joining(","));
    }

    /**
     * 拒绝当前实现未支持的 {@code requireValueDriver} 操作。
     */
    public static void requireValueDriver(Connection connection) throws SQLException {
        String url = connection.getMetaData().getURL();
        if (url == null || !url.startsWith("jdbc:TAOS-WS://")) {
            throw new SQLFeatureNotSupportedException("TDengine value transfer requires the WebSocket JDBC driver");
        }
    }

    /**
     * 返回元数据。
     */
    public static Table metadata(Connection connection, String schema, String table, int timeout) throws Exception {
        requireValueDriver(connection);
        var columns = new ArrayList<Column>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery("SELECT * FROM " + table(schema, table) + " LIMIT 0")) {
                var metadata = rows.getMetaData();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    if (metadata.getColumnType(index) == Types.NULL) {
                        throw new IllegalArgumentException("TDengine driver cannot represent a physical column type");
                    }
                    columns.add(new Column(metadata.getColumnName(index), metadata.getColumnType(index),
                            metadata.getColumnTypeName(index), metadata.getPrecision(index), metadata.getScale(index)));
                }
            }
            List<String> keys = new ArrayList<>();
            var tagNames = new HashSet<String>();
            try (var rows = statement.executeQuery("DESCRIBE " + table(schema, table))) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 4096) {
                        throw new IllegalArgumentException("TDengine column count exceeds the metadata bound");
                    }
                    if (count == 1) {
                        if (!"TIMESTAMP".equals(rows.getString(2))) {
                            throw new IllegalArgumentException("TDengine timestamp key is missing");
                        }
                        keys.add(rows.getString(1));
                    } else if ("COMPOSITE KEY".equals(rows.getString(4))) {
                        keys.add(rows.getString(1));
                    }
                    if ("TAG".equals(rows.getString(4))) {
                        tagNames.add(rows.getString(1));
                    }
                }
            }
            columns.removeIf(column -> tagNames.contains(column.name()));
            String kind;
            try (var query = connection.createStatement()) {
                query.setQueryTimeout(timeout);
                try (var rows = query.executeQuery("SELECT `type` FROM information_schema.ins_tables WHERE db_name='"
                        + schema.replace("'", "''") + "' AND table_name='" + table.replace("'", "''") + "' LIMIT 2")) {
                    if (rows.next()) {
                        kind = rows.getString(1);
                        if (!Set.of("NORMAL_TABLE", "CHILD_TABLE").contains(kind) || rows.next()) {
                            throw new IllegalArgumentException("TDengine physical table kind is unsupported");
                        }
                    } else {
                        kind = "SUPER_TABLE";
                    }
                }
            }
            try (var query = connection.createStatement()) {
                query.setQueryTimeout(timeout);
                try (var rows = query.executeQuery(
                        "SELECT `precision` FROM information_schema.ins_databases WHERE name='"
                                + schema.replace("'", "''") + "' LIMIT 2")) {
                    if (!rows.next()) {
                        throw new IllegalArgumentException("TDengine timestamp precision is unavailable");
                    }
                    int digits = switch (rows.getString(1)) {
                        case "ms" -> 3;
                        case "us" -> 6;
                        case "ns" -> 9;
                        default -> throw new IllegalArgumentException("TDengine timestamp precision is unsupported");
                    };
                    if (rows.next() || keys.isEmpty()) {
                        throw new IllegalArgumentException("TDengine table metadata is ambiguous");
                    }
                    return new Table(List.copyOf(columns), List.copyOf(keys), digits, kind);
                }
            }
        }
    }

    /**
     * 按目标类型约定转换当前输入值。
     */
    public static List<Object[]> convert(List<Object[]> records, List<String> fields, Table table, BatchLimits limits) {
        columns(fields);
        limits.maxMultiValueRows(fields.size(), 0);
        if (!fields.containsAll(table.keys())) {
            throw new IllegalArgumentException("TDengine write requires every stable timestamp/key column");
        }
        var rows = BatchValueConverter.convert(records, fields.size(), limits,
                (value, index) -> convert(value, table.column(fields.get(index)), table.fractionalDigits()));
        var keys = new HashSet<List<Object>>();
        for (var row : rows) {
            var key = table.keys().stream().map(field -> {
                Object value = row[fields.indexOf(field)];
                return value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value;
            }).toList();
            if (key.contains(null) || !keys.add(key)) {
                throw new IllegalArgumentException("TDengine batch has NULL or duplicate stable keys");
            }
        }
        return rows;
    }

    /**
     * 按目标类型约定转换当前输入值。
     */
    public static Object convert(Object value, Column column, int fractionalDigits) {
        if (value == null) {
            return null;
        }
        if (column.jdbcType() == Types.TIMESTAMP) {
            return timestamp(value, fractionalDigits);
        }
        String nativeType = column.nativeType().toUpperCase(Locale.ROOT);
        if (nativeType.endsWith(" UNSIGNED")) {
            int bits = switch (nativeType) {
                case "TINYINT UNSIGNED" -> 8;
                case "SMALLINT UNSIGNED" -> 16;
                case "INT UNSIGNED" -> 32;
                case "BIGINT UNSIGNED" -> 64;
                default -> throw new IllegalArgumentException("TDengine unsigned native type is unsupported");
            };
            BigInteger integer;
            try {
                integer = JdbcValueConversions.toDecimal(value).toBigIntegerExact();
                if (integer.signum() < 0 || integer.bitLength() > bits) {
                    throw new ArithmeticException();
                }
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("TDengine unsigned integer is outside its exact native range");
            }
            // WS 二进制协议要求这些精确装箱类，包括 UINT64 对应的 BigInteger。
            if (bits == 8) {
                return integer.shortValueExact();
            }
            if (bits == 16) {
                return integer.intValueExact();
            }
            if (bits == 32) {
                return integer.longValueExact();
            }
            return integer;
        }
        if (nativeType.equals("BINARY") || nativeType.equals("VARCHAR") || nativeType.equals("VARBINARY")) {
            byte[] bytes = value instanceof byte[] binary ? binary.clone() : utf8(JdbcValueConversions.toText(value));
            if (column.precision() > 0 && bytes.length > column.precision()) {
                throw new IllegalArgumentException("TDengine byte string exceeds the physical column byte length");
            }
            return bytes;
        }
        if (column.jdbcType() == Types.DECIMAL || column.jdbcType() == Types.NUMERIC) {
            try {
                var decimal = JdbcValueConversions.toDecimal(value).setScale(column.scale(), RoundingMode.UNNECESSARY);
                if (column.precision() < 1 || column.precision() > 38 || decimal.precision() > column.precision()) {
                    throw new ArithmeticException();
                }
                return decimal;
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("TDengine decimal cannot be represented exactly");
            }
        }
        return switch (column.jdbcType()) {
            case Types.TINYINT -> JdbcValueConversions.toByte(value);
            case Types.SMALLINT -> JdbcValueConversions.toShort(value);
            case Types.INTEGER -> JdbcValueConversions.toInteger(value);
            case Types.BIGINT -> JdbcValueConversions.toLong(value);
            case Types.BOOLEAN, Types.BIT -> JdbcValueConversions.toBoolean(value);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> {
                byte[] bytes = JdbcValueConversions.toBytes(value);
                if (column.precision() > 0 && bytes.length > column.precision()) {
                    throw new IllegalArgumentException("TDengine binary exceeds the physical column length");
                }
                yield bytes;
            }
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR -> {
                String text = JdbcValueConversions.toText(value);
                utf8(text);
                if (column.precision() > 0 && text.codePointCount(0, text.length()) > column.precision()) {
                    throw new IllegalArgumentException("TDengine text exceeds the physical column length");
                }
                yield text;
            }
            case Types.REAL, Types.FLOAT -> JdbcValueConversions.toFloat(value);
            case Types.DOUBLE -> JdbcValueConversions.toDouble(value);
            default -> throw new IllegalArgumentException("TDengine value type requires an explicit codec");
        };
    }

    /**
     * 按 UTF-8 编码或解码当前文本。
     */
    private static byte[] utf8(String value) {
        try {
            var encoded = java.nio.charset.StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new IllegalArgumentException("TDengine text cannot be represented as valid UTF-8");
        }
    }

    /**
     * 按当前值域约定解析或转换时间戳。
     */
    public static long timestamp(Object value, int digits) {
        Instant instant;
        if (value instanceof Timestamp timestamp) {
            instant = timestamp.toInstant();
        } else if (value instanceof Instant time) {
            instant = time;
        } else if (value instanceof OffsetDateTime time) {
            instant = time.toInstant();
        } else if (value instanceof LocalDateTime time) {
            instant = Timestamp.valueOf(time).toInstant();
        } else {
            throw new IllegalArgumentException("TDengine timestamp requires a typed temporal value");
        }
        long divisor = switch (digits) {
            case 3 -> 1_000_000;
            case 6 -> 1_000;
            case 9 -> 1;
            default -> throw new IllegalArgumentException("TDengine timestamp precision is invalid");
        };
        if (instant.getNano() % divisor != 0) {
            throw new IllegalArgumentException("TDengine timestamp would lose fractional precision");
        }
        try {
            return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L / divisor),
                    instant.getNano() / divisor);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("TDengine timestamp exceeds its signed range");
        }
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    public static void bind(PreparedStatement statement, int index, Object value, Column column) throws SQLException {
        if (value == null) {
            statement.setNull(index, column.jdbcType());
        } else if (column.jdbcType() == Types.TIMESTAMP) {
            statement.setLong(index, (Long) value);
        } else if (value instanceof BigInteger integer) {
            statement.setObject(index, integer);
        } else if (value instanceof BigDecimal decimal) {
            statement.setString(index, decimal.toPlainString());
        } else if (value instanceof byte[] bytes) {
            statement.setBytes(index, bytes);
        } else {
            statement.setObject(index, value);
        }
    }

    /**
     * 生成目标表的 INSERT 语句。
     */
    public static String insertSql(String schema, String table, List<String> fields) {
        return "INSERT INTO " + table(schema, table) + " (" + columns(fields) + ") VALUES ("
                + String.join(",", Collections.nCopies(fields.size(), "?")) + ")";
    }

    /**
     * 按当前定义插入一条或一批数据。
     */
    public static long insert(Connection connection, NonTransactionalWriteExecution.Attempt attempt,
            String schema, String table, List<String> fields, Table metadata, List<Object[]> rows,
            int timeout) throws Exception {
        requireValueDriver(connection);
        if (metadata.kind().equals("SUPER_TABLE")) {
            throw new IllegalArgumentException("TDengine supertable writes require explicit leaf-table routing");
        }
        long confirmed = 0;
        try (var statement = connection.prepareStatement(insertSql(schema, table, fields))) {
            statement.setQueryTimeout(timeout);
            for (var row : rows) {
                for (int index = 0; index < fields.size(); index++) {
                    bind(statement, index + 1, row[index], metadata.column(fields.get(index)));
                }
                attempt.sending();
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("TDengine did not confirm the written row");
                }
                confirmed++;
            }
        }
        return confirmed;
    }
}
