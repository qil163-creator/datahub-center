package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * UXsinoDB 2.1.0.3 普通永久堆表重放证据；服务器报告 PostgreSQL 10.0
 * 兼容，因此此处仅使用 ux_catalog 的 PG10 目录形态，绝不复用 PostgreSQL 16 或其他
 * 产品假设。目录位于 {@code ux_catalog}，不存在 pg_catalog；
 * 函数使用 {@code ux_} 前缀，例如 {@code ux_get_constraintdef}、{@code ux_collation_actual_version}，
 * 标识符为普通名称，未加引号时保存为小写；{@code current_user} 使用裸
 * 关键字，服务器会拒绝带模式限定的形式。
 */
public final class UxdbCursorMetadata {

    public record Column(int number, String name, String type, int modifier, boolean notNull, long collation) {

        /**
         * 返回精度。
         */
        int precision() {
            return type.equals("numeric") ? (modifier - 4) >>> 16 : 0;
        }

        /**
         * 返回小数位数。
         */
        int scale() {
            return type.equals("numeric") ? (modifier - 4) & 65535 : type.equals("timestamp") || type.equals(
                    "time") ? (modifier < 0 ? 6 : modifier) : 0;
        }
    }

    public record Shape(String identity, String fingerprint, Map<String, Column> columns, List<String> keys,
                        List<List<String>> uniques, String profile) {

        /**
         * 初始化 {@code Shape}。
         */
        public Shape {
            columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
            keys = List.copyOf(keys);
            uniques = List.copyOf(uniques);
        }
    }

    /**
     * 初始化 {@code UxdbCursorMetadata}。
     */
    private UxdbCursorMetadata() {
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    public static String quote(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.UXDB, name);
    }

    /**
     * 生成当前操作引用的限定关系名。
     */
    public static String reference(String schema, String table) {
        return quote(schema) + '.' + quote(table);
    }

    /** 读取器锁：ACCESS SHARE 在快照期间阻止 TRUNCATE 和 ALTER，但允许并发 DML。 */
    public static void lock(Connection c, String schema, String table, boolean writing,
            int timeout) throws SQLException {
        if (c.getAutoCommit()) {
            throw unsupported();
        }
        try (var p = c.createStatement()) {
            p.setQueryTimeout(timeout);
            p.execute("LOCK TABLE " + reference(schema,
                    table) + (writing ? " IN ROW EXCLUSIVE MODE" : " IN ACCESS SHARE MODE"));
        }
    }

    /** 通过绑定的普通名称获取原生身份，表关系指纹证明 2.1.0.3 的堆表版本。 */
    public static String identity(Connection c, String schema, String table, int timeout) throws SQLException {
        ordinary(schema);
        ordinary(table);
        try (var statement = c.prepareStatement("SELECT 1")) {
            statement.setQueryTimeout(timeout);
        }
        return UxdbRelationIdentity.fingerprint(c, schema + "." + table);
    }

    /**
     * 读取 UXDB 目标表的结构、键列及可重放值域证据。
     */
    public static Shape inspect(Connection c, String schema, String table, int timeout) throws SQLException {
        return inspect(c, schema, table, timeout, false);
    }

    /** COPY 不要求排序键；其有限标量编码独立于游标准入。 */
    public static Shape inspectCopy(Connection c, String schema, String table, int timeout) throws SQLException {
        return inspect(c, schema, table, timeout, true);
    }

    /**
     * 读取 UXDB 目标表的结构、键列及可重放值域证据。
     */
    private static Shape inspect(Connection c, String schema, String table, int timeout,
            boolean copy) throws SQLException {
        String identity = identity(c, schema, table, timeout);
        try (var p = prepare(c,
                "SELECT ux_catalog.current_setting('server_encoding'),ux_catalog.current_setting('client_encoding')",
                timeout)) {
            try (var r = p.executeQuery()) {
                if (!r.next() || !"UTF8".equals(r.getString(1)) || !"UTF8".equals(r.getString(2)) || r.next()) {
                    throw unsupported();
                }
            }
        }
        var columns = new LinkedHashMap<String, Column>();
        String sql = "SELECT a.attnum,a.attname,t.typname,a.atttypmod,a.attnotnull,a.attndims,a.atthasdef,a.attidentity,a.attcollation,"
                + "tn.nspname,t.typtype FROM ux_catalog.ux_attribute a JOIN ux_catalog.ux_type t ON t.oid=a.atttypid "
                + "JOIN ux_catalog.ux_namespace tn ON tn.oid=t.typnamespace "
                + "WHERE a.attrelid=(SELECT cl.oid FROM ux_catalog.ux_class cl JOIN ux_catalog.ux_namespace n ON n.oid=cl.relnamespace "
                + "WHERE n.nspname=? AND cl.relname=?) AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum";
        try (var p = prepare(c, sql, timeout, schema, table); var r = p.executeQuery()) {
            while (r.next()) {
                // UXDB 10.0 没有生成列；自增标识和默认值仍会使列不适用于精确重放。
                if (columns.size() >= 1024 || r.getInt(6) != 0 || !copy && r.getBoolean(7) || !"".equals(r.getString(8))
                        || !"ux_catalog".equals(r.getString(10)) || !"b".equals(r.getString(11))) {
                    throw unsupported();
                }
                var column = new Column(r.getInt(1), r.getString(2), r.getString(3), r.getInt(4), r.getBoolean(5),
                        r.getLong(9));
                quote(column.name());
                if (copy) {
                    copyJdbcType(column);
                } else {
                    jdbcType(column);
                }
                if (columns.put(column.name(), column) != null) {
                    throw unsupported();
                }
            }
        }
        if (columns.isEmpty()) {
            throw unsupported();
        }
        try (var p = prepare(c,
                "SELECT 1 FROM ux_catalog.ux_trigger WHERE tgrelid=(SELECT cl.oid FROM ux_catalog.ux_class cl "
                        + "JOIN ux_catalog.ux_namespace n ON n.oid=cl.relnamespace WHERE n.nspname=? AND cl.relname=?)",
                timeout, schema, table);
             var r = p.executeQuery()) {
            if (r.next()) {
                throw unsupported();
            }
        }
        var keys = new ArrayList<String>();
        var uniques = new ArrayList<List<String>>();
        var constraints = new ArrayList<String>();
        sql = "SELECT co.contype,co.convalidated,co.condeferrable,co.condeferred,co.conkey::ux_catalog.text,"
                + "i.indisvalid,i.indisready,i.indimmediate,i.indexprs IS NULL,i.indpred IS NULL,am.amname,"
                + "i.indnatts,i.indclass::ux_catalog.text,i.indcollation::ux_catalog.text,i.indoption::ux_catalog.text,co.consrc "
                + "FROM ux_catalog.ux_constraint co LEFT JOIN ux_catalog.ux_index i ON i.indexrelid=co.conindid "
                + "LEFT JOIN ux_catalog.ux_class ix ON ix.oid=i.indexrelid LEFT JOIN ux_catalog.ux_am am ON am.oid=ix.relam "
                + "WHERE co.conrelid=(SELECT cl.oid FROM ux_catalog.ux_class cl JOIN ux_catalog.ux_namespace n ON n.oid=cl.relnamespace "
                + "WHERE n.nspname=? AND cl.relname=?) ORDER BY co.conname";
        try (var p = prepare(c, sql, timeout, schema, table); var r = p.executeQuery()) {
            while (r.next()) {
                if (constraints.size() >= 256 || !Set.of("p", "u", "c").contains(r.getString(1)) || !r.getBoolean(2)
                        || r.getBoolean(3) || r.getBoolean(4)) {
                    throw unsupported();
                }
                if ("c".equals(r.getString(1))) {
                    String definition = r.getString(16);
                    if (definition == null || definition.isBlank() || definition.length() > 4096 || definition.indexOf(
                            '\0') >= 0) {
                        throw unsupported();
                    }
                    constraints.add("c:" + definition);
                    continue;
                }
                for (int i = 6; i <= 10; i++) {
                    if (!r.getBoolean(i) || r.wasNull()) {
                        throw unsupported();
                    }
                }
                if (!"btree".equals(r.getString(11)) || r.getInt(12) < 1 || r.getInt(12) > 32) {
                    throw unsupported();
                }
                String keyText = r.getString(5);
                if (keyText == null || !keyText.matches("\\{(?:[1-9][0-9]*(,[1-9][0-9]*)*)?\\}")) {
                    throw unsupported();
                }
                var numbers = java.util.Arrays.stream(
                                keyText.substring(1, keyText.length() - 1).isEmpty() ? new String[0]
                                        : keyText.substring(1, keyText.length() - 1).split(",")).map(Integer::parseInt)
                        .toList();
                if (numbers.size() != r.getInt(12)) {
                    throw unsupported();
                }
                var names = new ArrayList<String>();
                for (int number : numbers) {
                    var column = columns.values().stream().filter(x -> x.number() == number).findFirst()
                            .orElseThrow(UxdbCursorMetadata::unsupported);
                    names.add(column.name());
                }
                if ("p".equals(r.getString(1))) {
                    if (!keys.isEmpty()) {
                        throw unsupported();
                    }
                    keys.addAll(names);
                    uniques.add(List.copyOf(names));
                    if (!copy) {
                        verifyOrdering(c, columns, names, r.getString(13), r.getString(14), r.getString(15), timeout);
                    }
                } else {
                    uniques.add(List.copyOf(names));
                }
                constraints.add(
                        r.getString(1) + names + ":" + r.getString(13) + ":" + r.getString(14) + ":" + r.getString(15));
            }
        }
        if (!copy && keys.isEmpty() || !identity.equals(identity(c, schema, table, timeout))) {
            throw unsupported();
        }
        String profile = profile(c, schema, table, columns, timeout);
        return new Shape(identity, DigestUtil.sha256Hex(
                "UXDB_REPLAY_SCHEMA_V1\0" + columns.values() + '\0' + constraints + '\0' + profile),
                columns, keys, uniques, profile);
    }

    /**
     * 检查排序键与当前游标读取顺序是否一致。
     */
    private static void verifyOrdering(Connection c, Map<String, Column> columns, List<String> names, String classes,
            String collations, String options, int timeout) throws SQLException {
        String[] classIds = classes.split(" "), collationIds = collations.split(" "), flags = options.split(" ");
        if (classIds.length != names.size() || collationIds.length != names.size() || flags.length != names.size()) {
            throw unsupported();
        }
        for (int i = 0; i < names.size(); i++) {
            var column = columns.get(names.get(i));
            key(column);
            if (!column.notNull() || !flags[i].equals("0") || !classIds[i].matches("[1-9][0-9]{0,9}")) {
                throw unsupported();
            }
            try (var p = c.prepareStatement(
                    "SELECT o.opcdefault,n.nspname,t.typname,am.amname FROM ux_catalog.ux_opclass o "
                            + "JOIN ux_catalog.ux_namespace n ON n.oid=o.opcnamespace JOIN ux_catalog.ux_type t ON t.oid=o.opcintype "
                            + "JOIN ux_catalog.ux_am am ON am.oid=o.opcmethod WHERE o.oid=CAST(? AS ux_catalog.oid)")) {
                p.setQueryTimeout(timeout);
                p.setLong(1, Long.parseLong(classIds[i]));
                try (var r = p.executeQuery()) {
                    if (!r.next() || !r.getBoolean(1) || !"ux_catalog".equals(r.getString(2)) || !"btree".equals(
                            r.getString(4))) {
                        throw unsupported();
                    }
                    // varchar 和 bpchar 列使用默认文本操作符类，其 opcintype 为 "text"。
                    if (!column.type().equals(r.getString(3))
                            && !(Set.of("varchar", "bpchar").contains(column.type()) && "text".equals(
                            r.getString(3)))) {
                        throw unsupported();
                    }
                    if (r.next()) {
                        throw unsupported();
                    }
                }
            }
            // 文本键必须保留自身列的排序规则，不能使用数值零 OID；配置检查对其生成哈希。
            if (textual(column)) {
                if (collationIds[i].equals("0") || !collationIds[i].equals(String.valueOf(column.collation()))) {
                    throw unsupported();
                }
            } else if (!collationIds[i].equals("0")) {
                throw unsupported();
            }
        }
    }

    /** 所有源端和目标会话共享已验证的比较配置：UTF8 与数据库排序规则。 */
    private static String profile(Connection c, String schema, String table, Map<String, Column> columns,
            int timeout) throws SQLException {
        String sql = "SELECT ux_catalog.current_setting('server_encoding'),ux_catalog.current_setting('client_encoding'),"
                + "d.datcollate,d.datctype FROM ux_catalog.ux_database d WHERE d.datname=ux_catalog.current_database()";
        StringBuilder material = new StringBuilder("UXDB_PROFILE_V1");
        try (var p = prepare(c, sql, timeout); var r = p.executeQuery()) {
            if (!r.next() || !"UTF8".equals(r.getString(1)) || !"UTF8".equals(r.getString(2))) {
                throw unsupported();
            }
            for (int i = 1; i <= 4; i++) {
                material.append('\0').append(r.getString(i));
            }
            if (r.next()) {
                throw unsupported();
            }
        }
        var textNames = new ArrayList<String>();
        for (var column : columns.values()) {
            if (textual(column)) {
                textNames.add(column.name());
            }
        }
        Collections.sort(textNames);
        for (String name : textNames) {
            try (var p = prepare(c,
                    "SELECT co.collname,n.nspname,co.collprovider,co.collcollate,co.collctype,co.collversion "
                            + "FROM ux_catalog.ux_attribute a JOIN ux_catalog.ux_collation co ON co.oid=a.attcollation "
                            + "JOIN ux_catalog.ux_namespace n ON n.oid=co.collnamespace "
                            + "WHERE a.attrelid=(SELECT cl.oid FROM ux_catalog.ux_class cl JOIN ux_catalog.ux_namespace ns ON ns.oid=cl.relnamespace "
                            + "WHERE ns.nspname=? AND cl.relname=?) AND a.attname=?", timeout, schema, table, name);
                 var r = p.executeQuery()) {
                if (!r.next()) {
                    throw unsupported();
                }
                material.append('\0').append(name);
                for (int i = 1; i <= 6; i++) {
                    String value = r.getString(i);
                    // provider 'd'（数据库默认值）使 collcollate 和 collctype 为空，两者的拼写仍纳入配置。
                    // provider 'd'（数据库默认值）使 collcollate 和 collctype 为空，collversion 为 null，两者拼写仍纳入配置。
                    if (i <= 5 && (value == null || value.length() > 512 || value.indexOf(
                            '\0') >= 0 || i <= 3 && value.isBlank())
                            || i == 6 && value != null && (value.length() > 512 || value.indexOf('\0') >= 0)) {
                        throw unsupported();
                    }
                    material.append('\0').append(value);
                }
                if (r.next()) {
                    throw unsupported();
                }
            }
        }
        return DigestUtil.sha256Hex(material.toString());
    }

    /** 已验证的唯一仲裁键集合：主键，以及所有已验证且不可延迟的唯一约束。 */
    public static CursorKeyContract contract(Shape shape, List<String> keys) throws SQLException {
        if (shape.uniques().stream().noneMatch(keys::equals)) {
            throw unsupported();
        }
        var domains = new ArrayList<CursorKeyContract.Domain>();
        for (String key : keys) {
            domains.add(key(shape.columns().get(key)));
        }
        return new CursorKeyContract(ProductTypeEnum.UXDB,
                DigestUtil.sha256Hex("UXDB_EXACT_KEYS_V1\0" + domains + '\0' + shape.profile()), domains);
    }

    /** 精确数值域；已验证比较配置下，文本键和时间键仍仅限原生路径。 */
    public static CursorKeyContract.Domain key(Column c) throws SQLException {
        if (!c.notNull()) {
            throw unsupported();
        }
        return switch (c.type()) {
            case "int2" -> CursorKeyContract.Domain.integer(16, false);
            case "int4" -> CursorKeyContract.Domain.integer(32, false);
            case "int8" -> CursorKeyContract.Domain.integer(64, false);
            case "numeric" -> numericDomain(c);
            case "varchar", "bpchar", "text" -> {
                if (c.modifier() < 5 && !c.type().equals("text")) {
                    throw unsupported();
                }
                yield CursorKeyContract.Domain.nativeOnly();
            }
            case "date" -> CursorKeyContract.Domain.nativeOnly();
            case "timestamp" -> {
                if (c.scale() < 0 || c.scale() > 6) {
                    throw unsupported();
                }
                yield CursorKeyContract.Domain.nativeOnly();
            }
            default -> throw unsupported();
        };
    }

    /** 列的数值域，不要求非空；可空载荷列也使用此定义。 */
    private static CursorKeyContract.Domain numericDomain(Column c) throws SQLException {
        if (c.modifier() < 4 || c.precision() < 1 || c.precision() > 1000 || c.scale() > c.precision()) {
            throw unsupported();
        }
        return CursorKeyContract.Domain.decimal(c.precision(), c.scale(), false);
    }

    /** 任意数值列用于精确值验证的边界，与是否可空无关。 */
    private static CursorKeyContract.Numeric domain(Column c) throws SQLException {
        return switch (c.type()) {
            case "int2" -> CursorKeyContract.Domain.integer(16, false).numeric();
            case "int4" -> CursorKeyContract.Domain.integer(32, false).numeric();
            case "int8" -> CursorKeyContract.Domain.integer(64, false).numeric();
            case "numeric" -> numericDomain(c).numeric();
            default -> throw unsupported();
        };
    }

    /** 水位只能是数值列或时间列；任何读取前即拒绝文本水位。 */
    public static void watermark(Column c) throws SQLException {
        key(c);
        if (textual(c)) {
            throw unsupported();
        }
    }

    /**
     * 返回当前文本。
     */
    private static boolean textual(Column c) {
        return Set.of("varchar", "bpchar", "text").contains(c.type());
    }

    /**
     * 确定当前列或值对应的 JDBC 类型。
     */
    public static int jdbcType(Column c) throws SQLException {
        return switch (c.type()) {
            case "int2" -> Types.SMALLINT;
            case "int4" -> Types.INTEGER;
            case "int8" -> Types.BIGINT;
            case "numeric" -> {
                numericDomain(c);
                yield Types.NUMERIC;
            }
            case "varchar", "bpchar" -> {
                if (c.modifier() < 5) {
                    throw unsupported();
                }
                yield Types.VARCHAR;
            }
            case "text" -> Types.VARCHAR;
            case "bytea" -> Types.VARBINARY;
            case "bool" -> Types.BOOLEAN;
            case "date" -> Types.DATE;
            case "timestamp" -> {
                if (c.scale() < 0 || c.scale() > 6) {
                    throw unsupported();
                }
                yield Types.TIMESTAMP;
            }
            case "time" -> {
                if (c.scale() < 0 || c.scale() > 6) {
                    throw unsupported();
                }
                yield Types.TIME;
            }
            case "timestamptz" -> Types.TIMESTAMP_WITH_TIMEZONE;
            default -> throw unsupported();
        };
    }

    /** 原版 UXDB JDBC 2.1.2.3p 标量元数据，独立于类型化游标载体验证。 */
    public static int copyJdbcType(Column c) throws SQLException {
        return switch (c.type()) {
            case "float4" -> Types.REAL;
            case "float8" -> Types.DOUBLE;
            case "bool" -> Types.BIT;
            case "bytea" -> Types.BINARY;
            case "bpchar" -> {
                jdbcType(c);
                yield Types.CHAR;
            }
            case "timestamptz" -> throw unsupported();
            default -> jdbcType(c);
        };
    }

    /**
     * 复制当前值并保留受支持类型的精确表示。
     */
    public static Object exactCopy(Object value, Column c) throws SQLException {
        copyJdbcType(c);
        if (value == null) {
            if (c.notNull()) {
                throw unsupported();
            }
            return null;
        }
        if (c.type().equals("float4")) {
            if (!(value instanceof Float f) || !Float.isFinite(f)) {
                throw unsupported();
            }
            return value;
        }
        if (c.type().equals("float8")) {
            if (!(value instanceof Double || value instanceof Float) || !Double.isFinite(
                    ((Number) value).doubleValue())) {
                throw unsupported();
            }
            return ((Number) value).doubleValue();
        }
        Object converted = exact(value, c);
        if (c.type().equals("date")) {
            LocalDate date = ((java.sql.Date) converted).toLocalDate();
            if (date.getYear() < 1 || date.getYear() > 9999) {
                throw unsupported();
            }
        }
        return converted;
    }

    /**
     * 生成游标键列用于查询或重放的原生类型声明。
     */
    public static String keyType(Column c) throws SQLException {
        key(c);
        return c.type().equals("numeric") ? "numeric(" + c.precision() + "," + c.scale() + ")" : c.type();
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Object read(ResultSet r, int i, Column c) throws SQLException {
        return switch (jdbcType(c)) {
            case Types.NUMERIC -> r.getBigDecimal(i);
            case Types.TIMESTAMP -> r.getObject(i, LocalDateTime.class);
            case Types.TIMESTAMP_WITH_TIMEZONE -> r.getObject(i, OffsetDateTime.class);
            case Types.TIME -> readTime(r, i);
            case Types.DATE -> r.getObject(i, LocalDate.class);
            default -> r.getObject(i);
        };
    }

    /** UXDB TIME 存储微秒并接受 24:00:00，但驱动将其转换为 23:59:59.999999999，
     *  无法精确承载原值，因此予以拒绝，不进行隐式改写。 */
    private static LocalTime readTime(ResultSet rows, int index) throws SQLException {
        String text = rows.getString(index);
        if (text == null) {
            return null;
        }
        if (text.startsWith("24:")) {
            throw new SQLException("UXDB TIME 24:00:00 has no exact JDBC carrier");
        }
        try {
            return LocalTime.parse(text);
        } catch (RuntimeException invalid) {
            throw new SQLException("UXDB TIME text is not representable");
        }
    }

    /**
     * 比较实际回读定义与当前计划定义是否完全一致。
     */
    public static Object exact(Object value, Column c) throws SQLException {
        if (value == null) {
            if (c.notNull()) {
                throw unsupported();
            }
            return null;
        }
        int type = jdbcType(c);
        if (Set.of(Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC).contains(type)) {
            BigDecimal n;
            if (value instanceof BigDecimal v) {
                n = v;
            } else if (value instanceof BigInteger v) {
                n = new BigDecimal(v);
            } else if (value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof Byte) {
                n = BigDecimal.valueOf(((Number) value).longValue());
            } else {
                throw unsupported();
            }
            var domain = domain(c);
            try {
                n = n.setScale(domain.scale(), RoundingMode.UNNECESSARY);
            } catch (ArithmeticException e) {
                throw unsupported();
            }
            if (n.compareTo(domain.minimum()) < 0 || n.compareTo(domain.maximum()) > 0) {
                throw unsupported();
            }
            return n;
        }
        if (type == Types.TIMESTAMP) {
            LocalDateTime t = value instanceof Timestamp v ? v.toLocalDateTime() : value instanceof LocalDateTime v ? v : null;
            if (t == null || t.getYear() < 1 || t.getYear() > 9999 || t.getNano() % pow10(9 - c.scale()) != 0) {
                throw unsupported();
            }
            return Timestamp.valueOf(t);
        }
        if (type == Types.TIMESTAMP_WITH_TIMEZONE) {
            if (!(value instanceof OffsetDateTime)) {
                throw unsupported();
            }
            return value;
        }
        if (type == Types.TIME) {
            LocalTime t = value instanceof LocalTime v ? v : value instanceof java.sql.Time v && c.scale() <= 3 ? v.toLocalTime()
                    .withNano((int) Math.floorMod(v.getTime(), 1000L) * 1_000_000) : null;
            if (t == null || t.getNano() % pow10(9 - c.scale()) != 0) {
                throw unsupported();
            }
            return t;
        }
        if (type == Types.DATE) {
            if (value instanceof java.sql.Date) {
                return value;
            }
            if (value instanceof LocalDate d) {
                return java.sql.Date.valueOf(d);
            }
            throw unsupported();
        }
        if (type == Types.BOOLEAN) {
            if (!(value instanceof Boolean)) {
                throw unsupported();
            }
            return value;
        }
        if (type == Types.VARBINARY) {
            if (!(value instanceof byte[])) {
                throw unsupported();
            }
            return value;
        }
        if (!(value instanceof String text) || text.indexOf('\0') >= 0) {
            throw new SQLException("UXDB cannot preserve zero-containing text");
        }
        try {
            java.nio.charset.StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text));
        } catch (java.nio.charset.CharacterCodingException e) {
            throw unsupported();
        }
        if (!c.type().equals("text") && text.codePointCount(0, text.length()) > c.modifier() - 4) {
            throw unsupported();
        }
        return text;
    }

    /**
     * 检查当前对象是否为当前路径支持的普通表或列。
     */
    private static void ordinary(String name) throws SQLException {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw unsupported();
        }
    }

    /**
     * 计算给定整数次幂的十进制基数。
     */
    private static int pow10(int power) {
        int n = 1;
        for (int i = 0; i < power; i++) {
            n *= 10;
        }
        return n;
    }

    /**
     * 创建带查询超时和参数绑定的目录查询语句。
     */
    private static PreparedStatement prepare(Connection c, String sql, int timeout,
            String... values) throws SQLException {
        var p = c.prepareStatement(sql);
        try {
            p.setQueryTimeout(timeout);
            for (int i = 0; i < values.length; i++) {
                p.setString(i + 1, values[i]);
            }
            return p;
        } catch (SQLException e) {
            p.close();
            throw e;
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLException unsupported() {
        return new SQLException("UXDB replay catalog, collation, key or exact value is not verified");
    }
}
