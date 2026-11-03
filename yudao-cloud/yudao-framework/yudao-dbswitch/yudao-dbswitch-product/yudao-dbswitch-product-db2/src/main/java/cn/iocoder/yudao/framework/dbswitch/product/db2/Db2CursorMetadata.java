package cn.iocoder.yudao.framework.dbswitch.product.db2;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaFingerprint;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * 原生 DB2 LUW 12.1.5.0 重放值域。仅准入一个已验证的服务端配置：产品
 * DB2/LINUXX8664，JCC 版本令牌 SQL12015 或 SQL120150，服务级别 "DB2 v12.1.5.0"。VARCHAR 长度按数据库代码页的字节计，
 * 文本按各列代码页的二进制代码单元顺序排序，
 * 代码页是原生键契约的一部分。GENERATED ALWAYS 标识值绝不能
 * 显式提供：服务端对所有 OVERRIDING SYSTEM VALUE 写法均返回 SQLSTATE 42601。
 */
public final class Db2CursorMetadata {

    /**
     * 初始化 {@code Db2CursorMetadata}。
     */
    private Db2CursorMetadata() {
    }

    public record Shape(TableSchemaMetadata schema, String physical, String fingerprint) {

        /**
         * 读取或组装当前操作所使用的列定义。
         */
        public Map<String, ColumnDescription> columns() {
            var values = new LinkedHashMap<String, ColumnDescription>();
            schema.columns().forEach(c -> values.put(c.getFieldName(), c));
            return Collections.unmodifiableMap(values);
        }
    }

    /**
     * 按DB2方言转义并引用标识符。
     */
    public static String quote(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.length() > 128) {
            throw new IllegalArgumentException("Invalid DB2 physical identifier");
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    public static String relation(String schema, String table) {
        return quote(schema) + '.' + quote(table);
    }

    /**
     * 返回字段集合。
     */
    public static String fields(List<String> names) {
        return String.join(",", names.stream().map(Db2CursorMetadata::quote).toList());
    }

    /** JCC 4.13 与 4.33 对同一已验证服务级别报告不同版本令牌。 */
    public static void product(Connection c) throws SQLException {
        String name = c.getMetaData().getDatabaseProductName(), version = c.getMetaData().getDatabaseProductVersion();
        if (!"DB2/LINUXX8664".equals(name) || !("SQL12015".equals(version) || "SQL120150".equals(version))) {
            throw new SQLException("DB2 12.1.5 native replay connection required");
        }
        try (var s = c.createStatement()) {
            s.setQueryTimeout(15);
            s.setMaxRows(1);
            try (var r = s.executeQuery("SELECT service_level FROM SYSIBMADM.ENV_INST_INFO")) {
                if (!r.next() || !"DB2 v12.1.5.0".equals(r.getString(1))) {
                    throw new SQLException("DB2 12.1.5.0 service level is required");
                }
            }
        }
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Shape read(Connection c, String schema, String table, int timeout) throws SQLException {
        product(c);
        String physical = physical(c, schema, table, timeout);
        var metadata = readShape(c, schema, table, timeout);
        if (!physical.equals(physical(c, schema, table, timeout))) {
            throw new SQLException("DB2 table changed during replay observation");
        }
        return new Shape(metadata, physical, SchemaFingerprint.of(metadata));
    }

    /** 包含服务端名称、模式与表，以及 SYSCAT.TABLES 对象代次；恢复或重建会改变 CREATE_TIME/TABLEID。 */
    public static String physical(Connection c, String schema, String table, int timeout) throws SQLException {
        String server;
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            try (var r = s.executeQuery("VALUES CURRENT SERVER")) {
                if (!r.next()) {
                    throw new SQLException("DB2 server identity unavailable");
                }
                server = r.getString(1);
            }
        }
        try (var p = c.prepareStatement(
                "SELECT CREATE_TIME, TABLEID FROM SYSCAT.TABLES WHERE TABSCHEMA=? AND TABNAME=? AND TYPE='T'")) {
            p.setQueryTimeout(timeout);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                if (!r.next()) {
                    throw new SQLException("DB2 native table incarnation is unavailable");
                }
                String created = r.getString(1);
                int id = r.getInt(2);
                if (created == null || id <= 0 || r.next()) {
                    throw new SQLException("DB2 native table incarnation is ambiguous");
                }
                return DigestUtil.sha256Hex(
                        String.join("\0", "DB2_1215_SOURCE_PHYSICAL_V1", server, schema, table, created,
                                String.valueOf(id)));
            }
        }
    }

    /**
     * 读取当前目标关系的结构定义。
     */
    private static TableSchemaMetadata readShape(Connection c, String schema, String table,
            int timeout) throws SQLException {
        var provenance = new MetadataProvenance(ProductTypeEnum.DB2, "DB2/LINUXX8664",
                c.getMetaData().getDatabaseProductVersion(), "DB2_1215_CATALOG_V1", Instant.now());
        Map<String, ColumnDescription> columns = new LinkedHashMap<>();
        try (var p = c.prepareStatement(
                "SELECT COLNAME, TYPENAME, LENGTH, SCALE, NULLS, IDENTITY, GENERATED, CODEPAGE FROM SYSCAT.COLUMNS WHERE TABSCHEMA=? AND TABNAME=? ORDER BY COLNO")) {
            p.setQueryTimeout(timeout);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    if (columns.size() >= 1012) {
                        throw new SQLException("DB2 column limit exceeded");
                    }
                    String name = r.getString(1);
                    int codePage = r.getInt(8);
                    String type = normalizeType(r.getString(2), r.getInt(3), r.getInt(4), codePage);
                    boolean numeric = type.matches("(?:decimal|numeric)\\([0-9]+,[0-9]+\\)");
                    Integer precision = numeric ? Integer.valueOf(
                            r.getInt(3)) : null, scale = numeric ? Integer.valueOf(r.getInt(4)) : null;
                    var column = new ColumnDescription();
                    column.setDbType(ProductTypeEnum.DB2);
                    column.setFieldName(name);
                    column.setFieldType(jdbcType(type));
                    column.setFieldTypeName(type);
                    column.setSigned(true);
                    column.setPrecisionSize(precision == null ? 0 : precision);
                    column.setScaleSize(scale == null ? 0 : scale);
                    boolean nullable = "Y".equals(r.getString(5));
                    column.setNullable(nullable);
                    boolean identity = "Y".equals(r.getString(6));
                    column.setAutoIncrement(identity);
                    Long characterLength = type.matches("(?:character|varchar)\\([0-9]+\\)") ? Long.valueOf(
                            r.getInt(3)) : null;
                    column.setMetadataEvidence(
                            new ColumnMetadataEvidence(provenance, MetadataValue.value(type), MetadataValue.absent(),
                                    MetadataValue.value(nullable),
                                    precision == null ? MetadataValue.absent() : MetadataValue.value(precision),
                                    scale == null ? MetadataValue.absent() : MetadataValue.value(scale),
                                    characterLength == null ? MetadataValue.absent() : MetadataValue.value(
                                            characterLength),
                                    identity ? MetadataValue.value(r.getString(7)) : MetadataValue.absent(),
                                    MetadataValue.absent(),
                                    codePage > 0 ? MetadataValue.value(
                                            String.valueOf(codePage)) : MetadataValue.absent(),
                                    MetadataValue.absent()));
                    columns.put(name, column);
                }
            }
        }
        if (columns.isEmpty()) {
            throw new SQLException("DB2 table is not visible");
        }
        List<String> primary = new ArrayList<>();
        var ordered = new TreeMap<Short, String>();
        try (var r = c.getMetaData().getPrimaryKeys(null, schema, table)) {
            while (r.next()) {
                ordered.put(r.getShort("KEY_SEQ"), r.getString("COLUMN_NAME"));
            }
        }
        ordered.forEach((seq, name) -> primary.add(name));
        for (String key : primary) {
            if (!columns.containsKey(key)) {
                throw new SQLException("DB2 primary key column is invisible");
            }
        }
        columns.values().forEach(column -> column.setPk(primary.contains(column.getFieldName())));
        return new TableSchemaMetadata(new ArrayList<>(columns.values()), primary, provenance,
                MetadataValue.value(true), MetadataValue.value(List.of()));
    }

    /**
     * 事务内仅通过直接 SYSCAT 查询重新校验目标端，包括物理标识、列
     * 结构（名称、类型、长度、小数位数、可空性、标识生成方式及代码页），以及从
     * SYSCAT.TABCONST/KEYCOLUSE 读取的有序主键。
     */
    public static void verifyInTransaction(Connection c, String schema, String table, Shape shape,
            int timeout) throws SQLException {
        if (c.getAutoCommit()) {
            throw new SQLException("DB2 target recheck requires an owned transaction");
        }
        if (!shape.physical().equals(physical(c, schema, table, timeout))) {
            throw new SQLException("DB2 target incarnation changed");
        }
        Map<String, String> columns = new LinkedHashMap<>();
        try (var p = c.prepareStatement(
                "SELECT COLNAME, TYPENAME, LENGTH, SCALE, NULLS, IDENTITY, GENERATED, CODEPAGE FROM SYSCAT.COLUMNS WHERE TABSCHEMA=? AND TABNAME=? ORDER BY COLNO")) {
            p.setQueryTimeout(timeout);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    if (columns.size() >= 1012) {
                        throw new SQLException("DB2 target column set changed");
                    }
                    String type = normalizeType(r.getString(2), r.getInt(3), r.getInt(4), r.getInt(8));
                    columns.put(r.getString(1),
                            r.getString(1) + "\0" + type + "\0" + r.getInt(4) + "\0" + r.getString(5)
                                    + "\0" + r.getString(6) + "\0" + r.getString(7) + "\0" + r.getInt(8));
                }
            }
        }
        if (columns.size() != shape.columns().size()) {
            throw new SQLException("DB2 target column set changed");
        }
        for (var entry : shape.columns().entrySet()) {
            var column = entry.getValue();
            String type = column.getMetadataEvidence().nativeType().value();
            String expectedScale = type.matches("decimal\\([0-9]+,[0-9]+\\)") ? String.valueOf(
                    column.getMetadataEvidence().numericScale().value())
                    : type.matches("timestamp\\(([0-9]+)\\)") ? type.substring(type.indexOf('(') + 1,
                    type.indexOf(')')) : "0";
            String expectedPage = column.getMetadataEvidence().charset()
                    .value() == null ? "0" : column.getMetadataEvidence().charset().value();
            String actual = columns.get(entry.getKey());
            if (actual == null || !actual.equals(
                    entry.getKey() + "\0" + type + "\0" + expectedScale + "\0" + (column.isNullable() ? "Y" : "N") + "\0"
                            + (column.isAutoIncrement() ? "Y" : "N") + "\0" + (column.isAutoIncrement() ? column.getMetadataEvidence()
                            .identity().value() : " ") + "\0"
                            + expectedPage)) {
                throw new SQLException("DB2 target column shape changed");
            }
        }
        String constraint = null;
        try (var p = c.prepareStatement(
                "SELECT CONSTNAME FROM SYSCAT.TABCONST WHERE TABSCHEMA=? AND TABNAME=? AND TYPE='P'")) {
            p.setQueryTimeout(timeout);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    if (constraint != null) {
                        throw new SQLException("DB2 target has multiple primary keys");
                    }
                    constraint = r.getString(1);
                }
            }
        }
        if (constraint == null) {
            throw new SQLException("DB2 target primary key is missing");
        }
        List<String> primary = new ArrayList<>();
        try (var p = c.prepareStatement(
                "SELECT COLNAME FROM SYSCAT.KEYCOLUSE WHERE CONSTNAME=? AND TABSCHEMA=? AND TABNAME=? ORDER BY COLSEQ")) {
            p.setQueryTimeout(timeout);
            p.setString(1, constraint);
            p.setString(2, schema);
            p.setString(3, table);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    primary.add(r.getString(1));
                }
            }
        }
        if (!primary.equals(shape.schema().primaryKeys())) {
            throw new SQLException("DB2 target primary key changed");
        }
    }

    /**
     * 规范化类型。
     */
    private static String normalizeType(String raw, int length, int scale, int codePage) throws SQLException {
        if (raw == null || raw.isBlank() || raw.length() > 32) {
            throw new SQLException("DB2 column type is unreadable");
        }
        String type = raw.toLowerCase(Locale.ROOT);
        if (type.equals("decimal") || type.equals("numeric")) {
            return "decimal(" + length + "," + scale + ")";
        }
        if (type.equals("character") || type.equals("varchar")) {
            return codePage == 0 ? type + "(" + length + ") for bit data" : type + "(" + length + ")";
        }
        if (type.equals("timestamp")) {
            return "timestamp(" + scale + ")";
        }
        if (type.matches(
                "bigint|integer|smallint|time|date|real|double|float|decfloat|clob|blob|dbclob|graphic|vargraphic|long varchar|xml")) {
            return type;
        }
        throw new SQLException("DB2 column has no exact replay codec");
    }

    /**
     * 确定当前列或值对应的 JDBC 类型。
     */
    public static int jdbcType(String type) {
        type = type.split("\\(")[0];
        return switch (type) {
            case "smallint" -> Types.SMALLINT;
            case "integer" -> Types.INTEGER;
            case "bigint" -> Types.BIGINT;
            case "decimal", "numeric" -> Types.NUMERIC;
            case "timestamp" -> Types.TIMESTAMP;
            case "time" -> Types.TIME;
            case "date" -> Types.DATE;
            case "varchar" -> Types.VARCHAR;
            case "character" -> Types.CHAR;
            case "real" -> Types.REAL;
            case "double", "float" -> Types.DOUBLE;
            case "decfloat" -> Types.NUMERIC;
            case "clob" -> Types.CLOB;
            case "dbclob" -> Types.NCLOB;
            case "blob" -> Types.BLOB;
            case "long varchar" -> Types.LONGVARCHAR;
            case "xml" -> Types.SQLXML;
            default -> Types.OTHER;
        };
    }

    /**
     * 返回键集合。
     */
    public static CursorKeyContract keys(Shape shape, List<String> keys) throws SQLException {
        if (!shape.schema().primaryKeys().equals(keys)) {
            throw new SQLException("DB2 replay requires the exact ordered primary key");
        }
        var columns = shape.columns();
        var domains = new ArrayList<CursorKeyContract.Domain>();
        var identity = new StringBuilder("DB2_CURSOR_KEYS_V1");
        for (String key : keys) {
            var c = columns.get(key);
            if (c == null || c.isNullable()) {
                throw new SQLException("DB2 cursor key is nullable or absent");
            }
            requireOrdered(c);
            var numeric = numeric(c);
            domains.add(numeric == null ? CursorKeyContract.Domain.nativeOnly() : numeric);
            identity.append('\0').append(nativeType(c));
            String page = c.getMetadataEvidence().charset().value();
            if (page != null) {
                identity.append(':').append(page);
            }
        }
        return new CursorKeyContract(ProductTypeEnum.DB2, DigestUtil.sha256Hex(identity.toString()), domains);
    }

    /** 水位与键集比较均由服务端评估；Java 仅持久化精确值。 */
    public static void requireOrdered(ColumnDescription c) throws SQLException {
        if (c.isNullable() || !nativeType(c).matches(
                "smallint|integer|bigint|decimal\\([1-9][0-9]?,[0-9]+\\)|timestamp\\([0-9]+\\)|time|date|(?:character|varchar)\\([1-9][0-9]*\\)")) {
            throw new SQLException("DB2 cursor ordering type is not verified");
        }
    }

    /** 仅支持精确数值或本地日期、时间水位；文本键仅在同一代码页内使用原生语义。 */
    public static void requireWatermark(ColumnDescription c) throws SQLException {
        if (c.isNullable() || !nativeType(c).matches(
                "smallint|integer|bigint|decimal\\([1-9][0-9]?,[0-9]+\\)|timestamp\\([0-9]+\\)|time|date")) {
            throw new SQLException("DB2 watermark must be an exact numeric or date/time column");
        }
    }

    /**
     * 根据原生列信息确定数据库类型声明。
     */
    public static String nativeType(ColumnDescription c) throws SQLException {
        String t = c.getMetadataEvidence().nativeType().value();
        if (t == null || !t.matches(
                "smallint|integer|bigint|decimal\\([1-9][0-9]?,[0-9]+\\)|timestamp\\([0-9]+\\)|time|date|(?:character|varchar)\\([1-9][0-9]*\\)|character\\([1-9][0-9]*\\) for bit data|varchar\\([1-9][0-9]*\\) for bit data|real|double|float|decfloat|clob|blob|dbclob|graphic|vargraphic|long varchar|xml")) {
            throw new SQLException("DB2 column has no exact replay codec");
        }
        return t;
    }

    /** 持久化游标类型仅采用已验证的精确集合；其他列通过通用规范化处理。 */
    public static int jdbcType(ColumnDescription c) throws SQLException {
        return jdbcType(nativeType(c));
    }

    /**
     * 按当前值域约定解析或转换数值。
     */
    public static CursorKeyContract.Domain numeric(ColumnDescription c) throws SQLException {
        return switch (nativeType(c).split("\\(")[0]) {
            case "smallint" -> CursorKeyContract.Domain.integer(16, false);
            case "integer" -> CursorKeyContract.Domain.integer(32, false);
            case "bigint" -> CursorKeyContract.Domain.integer(64, false);
            case "decimal" -> CursorKeyContract.Domain.decimal(c.getMetadataEvidence().numericPrecision().value(),
                    c.getMetadataEvidence().numericScale().value(), false);
            default -> null;
        };
    }

    /** 精确持久化值：DB2 时间戳保留微秒，VARCHAR 长度按 UTF-8 字节计。 */
    public static void exact(Object value, ColumnDescription c) throws SQLException {
        String type = nativeType(c);
        if (value == null) {
            throw new SQLException("NULL DB2 cursor values are unsupported");
        }
        if (type.matches("smallint|integer|bigint")) {
            BigInteger number;
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                number = BigInteger.valueOf(((Number) value).longValue());
            } else if (value instanceof BigInteger v) {
                number = v;
            } else if (value instanceof BigDecimal v) {
                try {
                    number = v.toBigIntegerExact();
                } catch (ArithmeticException e) {
                    throw new SQLException("DB2 integer key is fractional");
                }
            } else {
                throw new SQLException("DB2 integer key type changed");
            }
            if (number.compareTo(BigInteger.valueOf(type.equals("bigint") ? Long.MIN_VALUE : type.equals(
                    "smallint") ? Short.MIN_VALUE : Integer.MIN_VALUE)) < 0
                    || number.compareTo(BigInteger.valueOf(type.equals("bigint") ? Long.MAX_VALUE : type.equals(
                    "smallint") ? Short.MAX_VALUE : Integer.MAX_VALUE)) > 0) {
                throw new SQLException("DB2 integer key is out of range");
            }
            return;
        }
        if (type.matches("decimal\\([0-9]+,[0-9]+\\)")) {
            BigDecimal number;
            if (value instanceof BigDecimal v) {
                number = v;
            } else if (value instanceof BigInteger v) {
                number = new BigDecimal(v);
            } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                number = BigDecimal.valueOf(((Number) value).longValue());
            } else {
                throw new SQLException("DB2 decimal key type changed");
            }
            int scale = c.getMetadataEvidence().numericScale().value(), precision = c.getMetadataEvidence()
                    .numericPrecision().value();
            try {
                number = number.setScale(scale, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException e) {
                throw new SQLException("DB2 decimal key scale would be lost");
            }
            if (number.abs().compareTo(BigDecimal.TEN.pow(precision - scale)) >= 0) {
                throw new SQLException("DB2 decimal key is out of range");
            }
            return;
        }
        if (type.startsWith("timestamp")) {
            var time = civil(value);
            int fraction = Integer.parseInt(type.substring(type.indexOf('(') + 1, type.indexOf(')')));
            if (time == null || time.getYear() < 1 || time.getYear() > 9999 || fraction > 9 || time.getNano() % fractionUnit(
                    fraction) != 0) {
                throw new SQLException("DB2 timestamp would round this value");
            }
            return;
        }
        if (type.equals("time")) {
            var time = clock(value);
            if (time == null || time.getNano() != 0) {
                throw new SQLException("DB2 time stores whole seconds only");
            }
            return;
        }
        if (type.equals("date")) {
            LocalDate date = value instanceof LocalDate v ? v : value instanceof java.sql.Date v ? v.toLocalDate() : null;
            if (date == null && civil(value) instanceof LocalDateTime time && time.toLocalTime()
                    .equals(LocalTime.MIDNIGHT)) {
                date = time.toLocalDate();
            }
            if (date == null || date.getYear() < 1 || date.getYear() > 9999) {
                throw new SQLException("Exact DB2 date input required");
            }
            return;
        }
        if (type.matches("(?:character|varchar)\\([1-9][0-9]*\\)")) {
            if (!(value instanceof String text)) {
                throw new SQLException("DB2 text key type changed");
            }
            int length = Integer.parseInt(type.substring(type.indexOf('(') + 1, type.indexOf(')')));
            if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > length) {
                throw new SQLException("DB2 text key exceeds its domain");
            }
            return;
        }
        throw new SQLException("DB2 cursor value has no exact codec");
    }

    /**
     * 按当前值域约定解析或转换本地日期时间。
     */
    private static LocalDateTime civil(Object value) {
        return value instanceof LocalDateTime v ? v : value instanceof java.sql.Timestamp v ? v.toLocalDateTime() : null;
    }

    /**
     * 返回当前时钟。
     */
    private static LocalTime clock(Object value) {
        if (value instanceof LocalTime v) {
            return v;
        }
        if (value instanceof java.sql.Time v) {
            return v.toLocalTime();
        }
        LocalDateTime time = civil(value);
        return time == null ? null : time.toLocalTime();
    }

    /** 小数秒精度对应的纳秒步长：10^(9-precision)。 */
    public static int fractionUnit(int fraction) {
        if (fraction < 0 || fraction > 9) {
            throw new IllegalArgumentException("DB2 fractional precision is out of range");
        }
        return (int) Math.pow(10, 9 - fraction);
    }

    /** 采用适合驱动绑定的参数值；时间值使用 java.sql 包装类型传输。 */
    public static Object bindable(Object value, String typeName) {
        if (value instanceof LocalDateTime time) {
            return java.sql.Timestamp.valueOf(time);
        }
        if (value instanceof LocalDate date) {
            return java.sql.Date.valueOf(date);
        }
        if (value instanceof LocalTime time) {
            return java.sql.Time.valueOf(time);
        }
        return value;
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Object read(ResultSet r, int index, ColumnDescription c, long maxCellBytes) throws SQLException {
        String type = nativeType(c);
        if (type.equals("date")) {
            Object value = r.getObject(index);
            if (value == null) {
                return null;
            }
            if (value instanceof java.sql.Date date) {
                return date.toLocalDate();
            }
            throw new SQLException("DB2 date value is unreadable");
        }
        if (type.startsWith("timestamp")) {
            java.sql.Timestamp value = r.getTimestamp(index);
            return value == null ? null : value.toLocalDateTime();
        }
        if (type.equals("time")) {
            java.sql.Time value = r.getTime(index);
            return value == null ? null : value.toLocalTime();
        }
        if (type.matches("decimal\\([0-9]+,[0-9]+\\)")) {
            return r.getBigDecimal(index);
        }
        if (type.matches("(?:character|varchar)\\([0-9]+\\)")) {
            String value = r.getString(index);
            if (value == null) {
                return null;
            }
            if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxCellBytes) {
                throw new SQLException("DB2 text cell exceeds its byte budget");
            }
            return value;
        }
        return cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions.normalize(r.getObject(index),
                maxCellBytes);
    }
}
