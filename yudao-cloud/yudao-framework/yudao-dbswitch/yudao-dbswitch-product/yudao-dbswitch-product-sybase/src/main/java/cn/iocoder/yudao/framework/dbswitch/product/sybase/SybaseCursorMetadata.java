package cn.iocoder.yudao.framework.dbswitch.product.sybase;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaFingerprint;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** 原生 Sybase ASE 16 重放值域，服务器全局排序规则属于键契约。 */
public final class SybaseCursorMetadata {

    /**
     * 初始化 {@code SybaseCursorMetadata}。
     */
    private SybaseCursorMetadata() {
    }

    public record Shape(TableSchemaMetadata schema, String physical, String fingerprint, String sortOrder) {

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
     * 按当前数据库方言转义并引用标识符。
     */
    public static String quote(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.length() > 30) {
            throw new IllegalArgumentException("Invalid Sybase physical identifier");
        }
        return "[" + name.replace("]", "]]") + "]";
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
        return String.join(",", names.stream().map(SybaseCursorMetadata::quote).toList());
    }

    /** 已验证的服务器配置为 ASE 16.0 SP02 PL02，绝不推断其他 Adaptive Server 版本。 */
    public static void product(Connection c) throws SQLException {
        String name = c.getMetaData().getDatabaseProductName(), version = c.getMetaData().getDatabaseProductVersion();
        if (!"Adaptive Server Enterprise".equals(name) || !version.matches("16\\.0(?:\\..*)?")) {
            throw new SQLException("Sybase ASE 16 native replay connection required");
        }
        String banner = banner(c);
        if (!banner.contains("Adaptive Server Enterprise/16.0 SP02 PL02")) {
            throw new SQLException("Sybase ASE SP02 PL02 native replay profile is required");
        }
    }

    /**
     * 返回当前标识信息。
     */
    private static String banner(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(15);
            s.setMaxRows(1);
            try (var r = s.executeQuery("SELECT @@version")) {
                if (!r.next()) {
                    throw new SQLException("Sybase version unavailable");
                }
                return r.getString(1);
            }
        }
    }

    /** 从 sp_helpsort 结果集读取当前服务器排序规则，例如 "50:bin_iso_1"。 */
    public static String sortOrder(Connection c, int timeout) throws SQLException {
        try (var cs = c.prepareCall("{call sp_helpsort()}"); var first = cs.executeQuery()) {
            var r = first;
            while (true) {
                while (r.next()) {
                    String value = r.getString(1);
                    if (value != null && value.startsWith("Sort Order = ")) {
                        String rest = value.substring("Sort Order = ".length()).trim();
                        if (rest.matches("\\d+, [A-Za-z0-9_]+")) {
                            String[] parts = rest.split(", ", 2);
                            return parts[0] + ':' + parts[1];
                        }
                    }
                }
                if (!cs.getMoreResults()) {
                    break;
                }
                r = cs.getResultSet();
            }
        }
        throw new SQLException("Sybase sort order is unavailable");
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Shape read(Connection c, String schema, String table, int timeout) throws SQLException {
        product(c);
        String sort = sortOrder(c, timeout);
        String physical = physical(c, schema, table, timeout, sort);
        var metadata = readShape(c, schema, table, timeout);
        if (!physical.equals(physical(c, schema, table, timeout, sort))) {
            throw new SQLException("Sybase table changed during replay observation");
        }
        return new Shape(metadata, physical, SchemaFingerprint.of(metadata), sort);
    }

    /**
     * 返回物理。
     */
    public static String physical(Connection c, String schema, String table, int timeout,
            String sort) throws SQLException {
        String server;
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            try (var r = s.executeQuery("SELECT @@servername")) {
                if (!r.next()) {
                    throw new SQLException("Sybase server identity unavailable");
                }
                server = r.getString(1);
            }
        }
        try (var p = c.prepareStatement(
                "SELECT o.id, o.crdate FROM sysobjects o WHERE o.type='U' AND o.name=? AND user_name(o.uid)=?")) {
            p.setQueryTimeout(timeout);
            p.setString(1, table);
            p.setString(2, schema);
            try (var r = p.executeQuery()) {
                if (!r.next()) {
                    throw new SQLException("Sybase native table incarnation is unavailable");
                }
                String created = r.getString(2);
                long object = r.getLong(1);
                if (created == null || object <= 0 || r.next()) {
                    throw new SQLException("Sybase native table incarnation is ambiguous");
                }
                String id;
                try (var s = c.createStatement(); var rows = s.executeQuery("SELECT db_id()")) {
                    if (!rows.next()) {
                        throw new SQLException("Sybase database identity unavailable");
                    }
                    id = String.valueOf(rows.getInt(1));
                }
                return DigestUtil.sha256Hex(
                        String.join("\0", "SYBASE16_SOURCE_PHYSICAL_V1", server, id, String.valueOf(object), created,
                                sort));
            }
        }
    }

    /**
     * 读取当前目标关系的结构定义。
     */
    private static TableSchemaMetadata readShape(Connection c, String schema, String table,
            int timeout) throws SQLException {
        var provenance = new MetadataProvenance(ProductTypeEnum.SYBASE, "Adaptive Server Enterprise",
                c.getMetaData().getDatabaseProductVersion(), "SYBASE16_CATALOG_V1", Instant.now());
        Long objectId;
        try (var p = c.prepareStatement(
                "SELECT o.id FROM sysobjects o WHERE o.type='U' AND o.name=? AND user_name(o.uid)=?")) {
            p.setQueryTimeout(timeout);
            p.setString(1, table);
            p.setString(2, schema);
            try (var r = p.executeQuery()) {
                if (!r.next()) {
                    throw new SQLException("Sybase table is not visible");
                }
                objectId = r.getLong(1);
                if (r.next()) {
                    throw new SQLException("Sybase table identity is ambiguous");
                }
            }
        }
        Map<String, ColumnDescription> columns = new LinkedHashMap<>();
        try (var p = c.prepareStatement(
                "SELECT c.colid, c.name, t.name, c.length, c.prec, c.scale, c.status, c.status2 FROM syscolumns c JOIN systypes t ON t.usertype=c.usertype WHERE c.id=? ORDER BY c.colid")) {
            p.setQueryTimeout(timeout);
            p.setLong(1, objectId);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    if (columns.size() >= 512) {
                        throw new SQLException("Sybase column limit exceeded");
                    }
                    String name = r.getString(2), type = normalizeType(r.getString(3), r.getInt(4), r.getInt(5),
                            r.getInt(6));
                    int status = r.getInt(7), status2 = r.getInt(8);
                    if (status2 != 0) {
                        throw new SQLException("Sybase column has unverified defaults or computed semantics");
                    }
                    var column = new ColumnDescription();
                    column.setDbType(ProductTypeEnum.SYBASE);
                    column.setFieldName(name);
                    column.setFieldType(jdbcType(type));
                    column.setFieldTypeName(type);
                    column.setSigned(true);
                    boolean numeric = type.matches("(?:(?:decimal|numeric)\\([0-9]+,[0-9]+\\))");
                    Integer precision = numeric ? Integer.valueOf(
                            r.getInt(5)) : null, scale = numeric ? Integer.valueOf(r.getInt(6)) : null;
                    column.setPrecisionSize(precision == null ? 0 : precision);
                    column.setScaleSize(scale == null ? 0 : scale);
                    boolean nullable = (status & 8) != 0;
                    column.setNullable(nullable);
                    column.setAutoIncrement((status & 128) != 0);
                    Long characterLength = type.matches("(?:uni)?(?:var)?char\\([0-9]+\\)") ? (long) Integer.parseInt(
                            type.substring(type.indexOf('(') + 1, type.indexOf(')'))) : null;
                    column.setMetadataEvidence(
                            new ColumnMetadataEvidence(provenance, MetadataValue.value(type), MetadataValue.absent(),
                                    MetadataValue.value(nullable),
                                    precision == null ? MetadataValue.absent() : MetadataValue.value(precision),
                                    scale == null ? MetadataValue.absent() : MetadataValue.value(scale),
                                    characterLength == null ? MetadataValue.absent() : MetadataValue.value(
                                            characterLength),
                                    (status & 128) != 0 ? MetadataValue.value("IDENTITY") : MetadataValue.absent(),
                                    MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent()));
                    columns.put(name, column);
                }
            }
        }
        if (columns.isEmpty()) {
            throw new SQLException("Sybase target table is not visible");
        }
        List<String> primary = new ArrayList<>();
        var ordered = new TreeMap<Short, String>();
        try (var r = c.getMetaData().getPrimaryKeys(null, null, table)) {
            while (r.next()) {
                ordered.put(r.getShort("KEY_SEQ"), r.getString("COLUMN_NAME"));
            }
        }
        ordered.forEach((seq, name) -> primary.add(name));
        for (String key : primary) {
            if (!columns.containsKey(key)) {
                throw new SQLException("Sybase primary key column is invisible");
            }
        }
        columns.values().forEach(column -> column.setPk(primary.contains(column.getFieldName())));
        return new TableSchemaMetadata(new ArrayList<>(columns.values()), primary, provenance,
                MetadataValue.value(true), MetadataValue.value(List.of()));
    }
    /** systypes 报告裸类型名，带括号类型拼写根据列事实重建。 */
    /**
     * 事务内目标复检仅使用直接目录查询：jconn4 的 getPrimaryKeys 和
     * sp_helpindex、sp_pkeys 会创建临时表，而 tempdb 禁止在多语句
     * 事务中创建临时表；此处验证物理身份、列形态及主键存在性，主键列
     * 顺序无法在事务内重读，仍是显式的剩余限制。
     */
    public static void verifyInTransaction(Connection c, String schema, String table, String sort, String physical,
            Map<String, ColumnDescription> expected, int timeout) throws SQLException {
        if (c.getAutoCommit()) {
            throw new SQLException("Sybase target recheck requires an owned transaction");
        }
        if (!physical.equals(physical(c, schema, table, timeout, sort))) {
            throw new SQLException("Sybase target incarnation changed");
        }
        Long objectId;
        try (var p = c.prepareStatement(
                "SELECT o.id FROM sysobjects o WHERE o.type='U' AND o.name=? AND user_name(o.uid)=?")) {
            p.setQueryTimeout(timeout);
            p.setString(1, table);
            p.setString(2, schema);
            try (var r = p.executeQuery()) {
                if (!r.next()) {
                    throw new SQLException("Sybase target is not visible");
                }
                objectId = r.getLong(1);
                if (r.next()) {
                    throw new SQLException("Sybase target identity is ambiguous");
                }
            }
        }
        Map<String, String> columns = new LinkedHashMap<>();
        try (var p = c.prepareStatement(
                "SELECT c.colid, c.name, t.name, c.length, c.prec, c.scale, c.status, c.status2 FROM syscolumns c JOIN systypes t ON t.usertype=c.usertype WHERE c.id=? ORDER BY c.colid")) {
            p.setQueryTimeout(timeout);
            p.setLong(1, objectId);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    if (columns.size() >= 512 || r.getInt(8) != 0) {
                        throw new SQLException("Sybase target column semantics changed");
                    }
                    Integer precision = r.getInt(5), scale = r.getInt(6);
                    if (r.wasNull()) {
                        precision = null;
                    }
                    if (r.wasNull()) {
                        scale = null;
                    }
                    String base = r.getString(3).toLowerCase(Locale.ROOT);
                    int length = 0;
                    if (base.matches("(?:uni)?(?:var)?char")) {
                        length = r.getInt(4);
                        if (base.startsWith("uni")) {
                            length /= 2;
                        }
                    }
                    columns.put(r.getString(2), r.getString(
                            2) + "\0" + base + "\0" + length + "\0" + precision + "\0" + scale + "\0" + r.getInt(7));
                }
            }
        }
        if (columns.size() != expected.size()) {
            throw new SQLException("Sybase target column set changed");
        }
        for (var entry : expected.entrySet()) {
            var column = entry.getValue();
            String base = column.getMetadataEvidence().nativeType().value();
            Integer precision = column.getMetadataEvidence().numericPrecision()
                    .value(), scale = column.getMetadataEvidence().numericScale().value();
            int length = base.matches("(?:uni)?(?:var)?char\\([0-9]+\\)") ? Integer.parseInt(
                    base.substring(base.indexOf('(') + 1, base.indexOf(')'))) : 0;
            base = base.split("\\(")[0];
            String actual = columns.get(entry.getKey());
            if (actual == null || !actual.equals(
                    entry.getKey() + "\0" + base + "\0" + length + "\0" + precision + "\0" + scale + "\0" + ((column.isNullable() ? 8 : 0) | (column.isAutoIncrement() ? 128 : 0)))) {
                throw new SQLException("Sybase target column shape changed");
            }
        }
        boolean primary = false;
        try (var p = c.prepareStatement("SELECT i.status FROM sysindexes i WHERE i.id=?")) {
            p.setQueryTimeout(timeout);
            p.setLong(1, objectId);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    if ((r.getInt(1) & 2048) != 0) {
                        if (primary) {
                            throw new SQLException("Sybase target has multiple primary keys");
                        }
                        primary = true;
                    }
                }
            }
        }
        if (!primary) {
            throw new SQLException("Sybase target primary key is missing");
        }
    }

    /**
     * 规范化类型。
     */
    private static String normalizeType(String raw, int length, int precision, int scale) throws SQLException {
        if (raw == null || raw.isBlank() || raw.length() > 32) {
            throw new SQLException("Sybase column type is unreadable");
        }
        String type = raw.toLowerCase(Locale.ROOT);
        if (type.equals("double precision")) {
            throw new SQLException("Sybase floating point has no exact replay codec");
        }
        if (type.equals("decimal") || type.equals("numeric")) {
            return type + '(' + precision + ',' + scale + ')';
        }
        if (type.equals("char") || type.equals("varchar")) {
            return type + '(' + length + ')';
        }
        if (type.equals("unichar") || type.equals("univarchar")) {
            return type + '(' + length / 2 + ')';
        }
        if (type.matches(
                "tinyint|smallint|int|integer|bigint|money|smallmoney|float|real|datetime|smalldatetime|date|time|bigdatetime|bigtime|text|unitext|image|binary|varbinary|bit")) {
            return type;
        }
        throw new SQLException("Sybase column has no exact replay codec");
    }

    /**
     * 确定当前列或值对应的 JDBC 类型。
     */
    public static int jdbcType(String type) {
        type = type.split("\\(")[0];
        return switch (type) {
            case "tinyint" -> Types.TINYINT;
            case "smallint" -> Types.SMALLINT;
            case "int", "integer" -> Types.INTEGER;
            case "bigint" -> Types.BIGINT;
            case "decimal", "numeric", "money", "smallmoney" -> Types.NUMERIC;
            case "float", "real" -> Types.FLOAT;
            case "bit" -> Types.BOOLEAN;
            case "char" -> Types.CHAR;
            case "varchar" -> Types.VARCHAR;
            case "unichar", "univarchar" -> Types.VARCHAR;
            case "text", "unitext" -> Types.LONGVARCHAR;
            case "date" -> Types.DATE;
            case "time" -> Types.TIME;
            case "datetime", "smalldatetime", "bigdatetime" -> Types.TIMESTAMP;
            case "bigtime" -> Types.TIME;
            case "image" -> Types.LONGVARBINARY;
            case "binary", "varbinary" -> Types.VARBINARY;
            default -> Types.OTHER;
        };
    }

    /**
     * 返回键集合。
     */
    public static CursorKeyContract keys(Shape shape, List<String> keys) throws SQLException {
        if (!shape.schema().primaryKeys().equals(keys)) {
            throw new SQLException("Sybase replay requires the exact ordered primary key");
        }
        var columns = shape.columns();
        var domains = new ArrayList<CursorKeyContract.Domain>();
        var identity = new StringBuilder("SYBASE_CURSOR_KEYS_V1");
        for (String key : keys) {
            var c = columns.get(key);
            if (c == null || c.isNullable()) {
                throw new SQLException("Sybase cursor key is nullable or absent");
            }
            requireOrdered(c);
            var numeric = numeric(c);
            domains.add(numeric == null ? CursorKeyContract.Domain.nativeOnly() : numeric);
            identity.append('\0').append(nativeType(c)).append(':').append(shape.sortOrder());
        }
        return new CursorKeyContract(ProductTypeEnum.SYBASE, DigestUtil.sha256Hex(identity.toString()), domains);
    }

    /** 水位和键集比较全部由服务器执行；Java 仅持久化精确值。 */
    public static void requireOrdered(ColumnDescription c) throws SQLException {
        if (c.isNullable() || !nativeType(c).matches(
                "tinyint|smallint|int|integer|bigint|(?:decimal|numeric)\\([1-9][0-9]?,[0-9]+\\)|date|datetime|bigdatetime|bigtime|(?:uni)?(?:var)?char\\([1-9][0-9]*\\)")) {
            throw new SQLException("Sybase cursor ordering type is not verified");
        }
    }

    /** 仅支持精确数值或本地日期时间水位，文本键仅限同一排序规则下的原生路径。 */
    public static void requireWatermark(ColumnDescription c) throws SQLException {
        if (c.isNullable() || !nativeType(c).matches(
                "tinyint|smallint|int|integer|bigint|(?:decimal|numeric)\\([1-9][0-9]?,[0-9]+\\)|date|datetime|bigdatetime|bigtime")) {
            throw new SQLException("Sybase watermark must be an exact numeric or date/time column");
        }
    }

    /**
     * 根据原生列信息确定数据库类型声明。
     */
    public static String nativeType(ColumnDescription c) throws SQLException {
        String t = c.getMetadataEvidence().nativeType().value();
        if (t == null || !t.matches(
                "tinyint|smallint|int|integer|bigint|(?:decimal|numeric)\\([1-9][0-9]?,[0-9]+\\)|date|datetime|bigdatetime|bigtime|(?:uni)?(?:var)?char\\([1-9][0-9]*\\)|money|smallmoney|float|real|time|bit|smalldatetime|text|unitext|image|binary|varbinary|char|varchar|unichar|univarchar")) {
            throw new SQLException("Sybase column has no exact replay codec");
        }
        return t;
    }

    /** 持久化游标类型：datetime 为规范刻度，bigdatetime 和 bigtime 为微秒值。 */
    public static int jdbcType(ColumnDescription c) throws SQLException {
        String type = nativeType(c);
        if (type.startsWith("uni")) {
            return Types.VARCHAR;
        }
        return jdbcType(type);
    }

    /**
     * 按当前值域约定解析或转换数值。
     */
    public static CursorKeyContract.Domain numeric(ColumnDescription c) throws SQLException {
        return switch (nativeType(c).split("\\(")[0]) {
            case "tinyint" -> CursorKeyContract.Domain.integer(8, true);
            case "smallint" -> CursorKeyContract.Domain.integer(16, false);
            case "int", "integer" -> CursorKeyContract.Domain.integer(32, false);
            case "bigint" -> CursorKeyContract.Domain.integer(64, false);
            case "decimal", "numeric" ->
                    CursorKeyContract.Domain.decimal(c.getMetadataEvidence().numericPrecision().value(),
                            c.getMetadataEvidence().numericScale().value(), false);
            default -> null;
        };
    }

    /** ASE datetime 存储 1/300 秒刻度，只有规范刻度值才能往返。 */
    public static void exact(Object value, ColumnDescription c) throws SQLException {
        String type = nativeType(c);
        if (value == null) {
            throw new SQLException("NULL Sybase cursor values are unsupported");
        }
        if (type.matches("tinyint|smallint|int|integer|bigint")) {
            BigInteger number;
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                number = BigInteger.valueOf(((Number) value).longValue());
            } else if (value instanceof BigInteger v) {
                number = v;
            } else if (value instanceof BigDecimal v) {
                try {
                    number = v.toBigIntegerExact();
                } catch (ArithmeticException e) {
                    throw new SQLException("Sybase integer key is fractional");
                }
            } else {
                throw new SQLException("Sybase integer key type changed");
            }
            if (number.compareTo(BigInteger.valueOf(
                    type.equals("tinyint") ? 0 : type.equals("bigint") ? Long.MIN_VALUE : type.equals(
                            "smallint") ? Short.MIN_VALUE : Integer.MIN_VALUE)) < 0
                    || number.compareTo(BigInteger.valueOf(
                    type.equals("tinyint") ? 255 : type.equals("bigint") ? Long.MAX_VALUE : type.equals(
                            "smallint") ? Short.MAX_VALUE : Integer.MAX_VALUE)) > 0) {
                throw new SQLException("Sybase integer key is out of range");
            }
            return;
        }
        if (type.matches("(?:decimal|numeric)\\([0-9]+,[0-9]+\\)")) {
            BigDecimal number;
            if (value instanceof BigDecimal v) {
                number = v;
            } else if (value instanceof BigInteger v) {
                number = new BigDecimal(v);
            } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                number = BigDecimal.valueOf(((Number) value).longValue());
            } else {
                throw new SQLException("Sybase decimal key type changed");
            }
            int scale = c.getMetadataEvidence().numericScale().value(), precision = c.getMetadataEvidence()
                    .numericPrecision().value();
            try {
                number = number.setScale(scale, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException e) {
                throw new SQLException("Sybase decimal key scale would be lost");
            }
            if (number.abs().compareTo(BigDecimal.TEN.pow(precision - scale)) >= 0) {
                throw new SQLException("Sybase decimal key is out of range");
            }
            return;
        }
        if (type.equals("datetime")) {
            var time = civil(value);
            if (time == null || time.getYear() < 1753 || !datetimeTick(time.getNano())) {
                throw new SQLException("Sybase datetime would round this value");
            }
            return;
        }
        if (type.equals("bigdatetime")) {
            var time = civil(value);
            if (time == null || time.getYear() < 1 || time.getYear() > 9999 || time.getNano() % 1_000 != 0) {
                throw new SQLException("Sybase bigdatetime would round this value");
            }
            return;
        }
        if (type.equals("bigtime")) {
            var time = clock(value);
            if (time == null || time.getNano() % 1_000 != 0) {
                throw new SQLException("Sybase bigtime would round this value");
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
                throw new SQLException("Exact Sybase date input required");
            }
            return;
        }
        if (type.matches("(?:uni)?(?:var)?char\\([1-9][0-9]*\\)")) {
            if (!(value instanceof String text)) {
                throw new SQLException("Sybase text key type changed");
            }
            int length = Integer.parseInt(type.substring(type.indexOf('(') + 1, type.indexOf(')')));
            int chars = type.startsWith("uni") ? text.length() : text.getBytes(
                    java.nio.charset.StandardCharsets.ISO_8859_1).length;
            if (chars > length) {
                throw new SQLException("Sybase text key exceeds its domain");
            }
            return;
        }
        throw new SQLException("Sybase cursor value has no exact codec");
    }

    /** ASE datetime 的 1/300 秒刻度：驱动返回 floor(k*1e9/300) 纳秒，并非按毫秒对齐。 */
    public static boolean datetimeTick(int nanos) {
        long k = Math.round(nanos * 300.0d / 1_000_000_000.0d);
        return k >= 0 && k <= 299 && (long) Math.floor(k * 1_000_000_000L / 300.0d) == nanos;
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

    /** 适合驱动绑定的参数值，bigtime 使用日期 0001-01-01 上的微秒 Timestamp。 */
    public static Object bindable(Object value, String typeName) {
        String base = typeName.contains("(") ? typeName.substring(0, typeName.indexOf('(')) : typeName;
        if (value instanceof LocalDateTime time) {
            return java.sql.Timestamp.valueOf(time);
        }
        if (value instanceof LocalDate date) {
            return java.sql.Date.valueOf(date);
        }
        if (value instanceof LocalTime time && base.equals("bigtime")) {
            return java.sql.Timestamp.valueOf(LocalDate.of(1, 1, 1).atTime(time));
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
            throw new SQLException("Sybase date value is unreadable");
        }
        if (type.equals("datetime") || type.equals("bigdatetime") || type.equals("smalldatetime")) {
            java.sql.Timestamp value = r.getTimestamp(index);
            return value == null ? null : value.toLocalDateTime();
        }
        if (type.equals("bigtime")) {
            java.sql.Timestamp value = r.getTimestamp(index);
            return value == null ? null : value.toLocalDateTime().toLocalTime();
        }
        if (type.matches("(?:decimal|numeric)\\([0-9]+,[0-9]+\\)")) {
            return r.getBigDecimal(index);
        }
        if (type.startsWith("uni") || type.matches("(?:var)?char\\([0-9]+\\)")) {
            String value = r.getString(index);
            if (value == null) {
                return null;
            }
            if (value.length() * 2L > maxCellBytes) {
                throw new SQLException("Sybase text cell exceeds its byte budget");
            }
            return value;
        }
        return cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions.normalize(r.getObject(index),
                maxCellBytes);
    }
}
