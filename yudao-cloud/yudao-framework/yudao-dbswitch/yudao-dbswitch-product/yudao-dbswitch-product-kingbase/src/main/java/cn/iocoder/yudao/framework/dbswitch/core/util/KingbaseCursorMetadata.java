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
 * KingbaseES V008R006C009B0014 在 {@code database_mode=oracle}、{@code enable_ci=on}
 * 配置下的普通堆表重放证据。服务器报告 PostgreSQL 12.1 兼容，因此仅使用 PG12
 * 目录形态；绝不复用 PostgreSQL 16 假设（{@code indislive}、排序规则版本
 * 字段）。标识符使用不区分大小写的普通名称。
 */
public final class KingbaseCursorMetadata {

    public record Column(int number, String name, String type, int modifier, boolean notNull) {

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
     * 初始化 {@code KingbaseCursorMetadata}。
     */
    private KingbaseCursorMetadata() {
    }

    /**
     * 按Kingbase方言转义并引用标识符。
     */
    public static String quote(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.KINGBASE, name);
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

    /** 通过绑定的普通名称获取原生身份，指纹证明版本、模式、大小写配置及堆表存储。 */
    public static String identity(Connection c, String schema, String table, int timeout) throws SQLException {
        ordinary(schema);
        ordinary(table);
        try (var statement = c.prepareStatement("SELECT 1")) {
            statement.setQueryTimeout(timeout);
        }
        return KingbaseRelationIdentity.fingerprint(c, schema + "." + table);
    }

    /**
     * 根据当前输入构建并返回 {@code Shape}。
     */
    public static Shape inspect(Connection c, String schema, String table, int timeout) throws SQLException {
        String identity = identity(c, schema, table, timeout);
        try (var p = prepare(c,
                "SELECT pg_catalog.current_setting('server_encoding'),pg_catalog.current_setting('client_encoding')",
                timeout)) {
            try (var r = p.executeQuery()) {
                if (!r.next() || !"UTF8".equals(r.getString(1)) || !"UTF8".equals(r.getString(2)) || r.next()) {
                    throw unsupported();
                }
            }
        }
        var columns = new LinkedHashMap<String, Column>();
        String sql = "SELECT a.attnum,a.attname,t.typname,a.atttypmod,a.attnotnull,a.attndims,a.atthasdef,a.attidentity,a.attgenerated,"
                + "tn.nspname,t.typtype FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid=a.atttypid "
                + "JOIN pg_catalog.pg_namespace tn ON tn.oid=t.typnamespace "
                + "WHERE a.attrelid=(SELECT cl.oid FROM pg_catalog.pg_class cl JOIN pg_catalog.pg_namespace n ON n.oid=cl.relnamespace "
                + "WHERE n.nspname=? AND cl.relname=?) AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum";
        try (var p = prepare(c, sql, timeout, schema, table); var r = p.executeQuery()) {
            while (r.next()) {
                if (columns.size() >= 1024 || r.getInt(6) != 0 || r.getBoolean(7) || !"".equals(
                        r.getString(8)) || !"".equals(r.getString(9))
                        || !"pg_catalog".equals(r.getString(10)) || !"b".equals(r.getString(11))) {
                    throw unsupported();
                }
                var column = new Column(r.getInt(1), r.getString(2), r.getString(3), r.getInt(4), r.getBoolean(5));
                quote(column.name());
                jdbcType(column);
                if (columns.put(column.name(), column) != null) {
                    throw unsupported();
                }
            }
        }
        if (columns.isEmpty()) {
            throw unsupported();
        }
        try (var p = prepare(c,
                "SELECT 1 FROM pg_catalog.pg_trigger WHERE tgrelid=(SELECT cl.oid FROM pg_catalog.pg_class cl "
                        + "JOIN pg_catalog.pg_namespace n ON n.oid=cl.relnamespace WHERE n.nspname=? AND cl.relname=?)",
                timeout, schema, table);
             var r = p.executeQuery()) {
            if (r.next()) {
                throw unsupported();
            }
        }
        var keys = new ArrayList<String>();
        var uniques = new ArrayList<List<String>>();
        var constraints = new ArrayList<String>();
        sql = "SELECT co.contype,co.convalidated,co.condeferrable,co.condeferred,co.conkey::pg_catalog.text,"
                + "i.indisvalid,i.indisready,i.indimmediate,i.indexprs IS NULL,i.indpred IS NULL,am.amname,"
                + "i.indnatts=i.indnkeyatts,i.indnkeyatts,i.indclass::pg_catalog.text,i.indcollation::pg_catalog.text,i.indoption::pg_catalog.text,"
                + "pg_catalog.pg_get_constraintdef(co.oid) "
                + "FROM pg_catalog.pg_constraint co LEFT JOIN pg_catalog.pg_index i ON i.indexrelid=co.conindid "
                + "LEFT JOIN pg_catalog.pg_class ix ON ix.oid=i.indexrelid LEFT JOIN pg_catalog.pg_am am ON am.oid=ix.relam "
                + "WHERE co.conrelid=(SELECT cl.oid FROM pg_catalog.pg_class cl JOIN pg_catalog.pg_namespace n ON n.oid=cl.relnamespace "
                + "WHERE n.nspname=? AND cl.relname=?) ORDER BY co.conname";
        try (var p = prepare(c, sql, timeout, schema, table); var r = p.executeQuery()) {
            while (r.next()) {
                if (constraints.size() >= 256 || !Set.of("p", "u", "c").contains(r.getString(1)) || !r.getBoolean(2)
                        || r.getBoolean(3) || r.getBoolean(4)) {
                    throw unsupported();
                }
                if ("c".equals(r.getString(1))) {
                    String definition = r.getString(17);
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
                if (!r.getBoolean(12) || !"btree".equals(r.getString(11)) || r.getInt(13) < 1 || r.getInt(13) > 32) {
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
                if (numbers.size() != r.getInt(13)) {
                    throw unsupported();
                }
                var names = new ArrayList<String>();
                for (int number : numbers) {
                    var column = columns.values().stream().filter(x -> x.number() == number).findFirst()
                            .orElseThrow(KingbaseCursorMetadata::unsupported);
                    names.add(column.name());
                }
                if ("p".equals(r.getString(1))) {
                    if (!keys.isEmpty()) {
                        throw unsupported();
                    }
                    keys.addAll(names);
                    uniques.add(List.copyOf(names));
                    verifyOrdering(c, columns, names, r.getString(14), r.getString(15), r.getString(16), timeout);
                } else {
                    uniques.add(List.copyOf(names));
                }
                constraints.add(
                        r.getString(1) + names + ":" + r.getString(14) + ":" + r.getString(15) + ":" + r.getString(16));
            }
        }
        if (keys.isEmpty() || !identity.equals(identity(c, schema, table, timeout))) {
            throw unsupported();
        }
        String profile = profile(c, schema, table, columns, timeout);
        return new Shape(identity, DigestUtil.sha256Hex(
                "KINGBASE_REPLAY_SCHEMA_V1\0" + columns.values() + '\0' + constraints + '\0' + profile),
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
            try (var p = prepare(c, "SELECT o.opcdefault,n.nspname,t.typname,am.amname FROM pg_catalog.pg_opclass o "
                            + "JOIN pg_catalog.pg_namespace n ON n.oid=o.opcnamespace JOIN pg_catalog.pg_type t ON t.oid=o.opcintype "
                            + "JOIN pg_catalog.pg_am am ON am.oid=o.opcmethod WHERE o.oid=CAST(? AS pg_catalog.oid)", timeout,
                    classIds[i]);
                 var r = p.executeQuery()) {
                if (!r.next() || !r.getBoolean(1) || !"pg_catalog".equals(r.getString(2)) || !"btree".equals(
                        r.getString(4))) {
                    throw unsupported();
                }
                // varchar 和 bpchar 列使用默认文本操作符类，其 opcintype 为 "text"。
                if (!column.type().equals(r.getString(3))
                        && !(Set.of("varchar", "bpchar").contains(column.type()) && "text".equals(r.getString(3)))) {
                    throw unsupported();
                }
                if (r.next()) {
                    throw unsupported();
                }
            }
            // 文本键必须保留自身列的排序规则，不能使用数值零 OID；配置检查对其生成哈希。
            if (textual(column) && collationIds[i].equals("0")) {
                throw unsupported();
            }
        }
    }

    /** 全部源端和目标会话共享已验证比较配置：UTF8、Oracle 模式及大小写不敏感开关。 */
    private static String profile(Connection c, String schema, String table, Map<String, Column> columns,
            int timeout) throws SQLException {
        String sql = "SELECT pg_catalog.current_setting('server_encoding'),pg_catalog.current_setting('client_encoding'),"
                + "pg_catalog.current_setting('database_mode'),pg_catalog.current_setting('enable_ci'),"
                + "d.datcollate,d.datctype FROM pg_catalog.pg_database d WHERE d.datname=pg_catalog.current_database()";
        StringBuilder material = new StringBuilder("KINGBASE_PROFILE_V1");
        try (var p = prepare(c, sql, timeout); var r = p.executeQuery()) {
            if (!r.next() || !"UTF8".equals(r.getString(1)) || !"UTF8".equals(r.getString(2)) || !"oracle".equals(
                    r.getString(3))
                    || !"on".equals(r.getString(4))) {
                throw unsupported();
            }
            for (int i = 1; i <= 6; i++) {
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
                    "SELECT co.collname,n.nspname,co.collprovider,co.collisdeterministic,co.collcollate,co.collctype "
                            + "FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_collation co ON co.oid=a.attcollation "
                            + "JOIN pg_catalog.pg_namespace n ON n.oid=co.collnamespace "
                            + "WHERE a.attrelid=(SELECT cl.oid FROM pg_catalog.pg_class cl JOIN pg_catalog.pg_namespace ns ON ns.oid=cl.relnamespace "
                            + "WHERE ns.nspname=? AND cl.relname=?) AND a.attname=?", timeout, schema, table, name);
                 var r = p.executeQuery()) {
                if (!r.next()) {
                    throw unsupported();
                }
                material.append('\0').append(name);
                for (int i = 1; i <= 6; i++) {
                    String value = r.getString(i);
                    // provider 'd'（数据库默认值）使 collcollate 和 collctype 为空，两者的拼写仍纳入配置。
                    if (value == null || value.length() > 512 || value.indexOf(
                            '\0') >= 0 || i <= 4 && value.isBlank()) {
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
        return new CursorKeyContract(ProductTypeEnum.KINGBASE,
                DigestUtil.sha256Hex("KINGBASE_EXACT_KEYS_V1\0" + domains + '\0' + shape.profile()), domains);
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

    /** Kingbase TIME 存储微秒，毫秒精度的 java.sql.Time 无法证明其精度，且不能表示 24:00:00。 */
    private static LocalTime readTime(ResultSet rows, int index) throws SQLException {
        String text = rows.getString(index);
        if (text == null) {
            return null;
        }
        if (text.startsWith("24:")) {
            throw new SQLException("Kingbase TIME 24:00:00 has no exact JDBC carrier");
        }
        try {
            return LocalTime.parse(text);
        } catch (RuntimeException invalid) {
            throw new SQLException("Kingbase TIME text is not representable");
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
        if (!(value instanceof String text) || text.isEmpty() || text.indexOf('\0') >= 0) {
            throw new SQLException("Kingbase oracle mode cannot preserve empty or zero-containing text");
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
        return new SQLException("Kingbase replay catalog, collation, key or exact value is not verified");
    }
}
