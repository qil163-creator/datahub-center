package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** openGauss 7 RC3 A 模式普通堆表重放证据，不使用 PostgreSQL 16 目录。 */
public final class OpengaussCursorMetadata {

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

    public record Shape(String identity, String fingerprint, Map<String, Column> columns, List<String> keys) {

        /**
         * 初始化 {@code Shape}。
         */
        public Shape {
            columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
            keys = List.copyOf(keys);
        }
    }

    /**
     * 初始化 {@code OpengaussCursorMetadata}。
     */
    private OpengaussCursorMetadata() {
    }

    /**
     * 按openGauss方言转义并引用标识符。
     */
    public static String quote(String n) {
        return OpengaussRelationIdentity.quote(n);
    }

    /**
     * 生成当前操作引用的限定关系名。
     */
    public static String reference(String s, String t) {
        return quote(s) + '.' + quote(t);
    }

    /**
     * 获取当前操作所需的数据库或资源锁。
     */
    public static void lock(Connection c, String s, String t, boolean writing, int timeout) throws SQLException {
        if (c.getAutoCommit()) {
            throw unsupported();
        }
        try (var p = c.createStatement()) {
            p.setQueryTimeout(timeout);
            p.execute("LOCK TABLE " + reference(s, t) + (writing ? " IN ROW EXCLUSIVE MODE" : " IN ACCESS SHARE MODE"));
        }
    }

    /**
     * 根据当前输入构建并返回 {@code Shape}。
     */
    public static Shape inspect(Connection c, String schema, String table, int timeout) throws SQLException {
        String reference = reference(schema, table), identity = OpengaussRelationIdentity.physicalFingerprint(c, schema,
                table, timeout);
        try (var p = c.createStatement()) {
            p.setQueryTimeout(timeout);
            try (var r = p.executeQuery(
                    "SELECT pg_catalog.current_setting('server_encoding'),pg_catalog.current_setting('client_encoding')")) {
                if (!r.next() || !"UTF8".equals(r.getString(1)) || !"UTF8".equals(r.getString(2)) || r.next()) {
                    throw unsupported();
                }
            }
        }
        var columns = new LinkedHashMap<String, Column>();
        String sql = "SELECT a.attnum,a.attname,t.typname,a.atttypmod,a.attnotnull,a.attndims,a.atthasdef,tn.nspname,t.typtype,"
                + "d.adgencol,d.adsrc_on_update FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid=a.atttypid "
                + "JOIN pg_catalog.pg_namespace tn ON tn.oid=t.typnamespace LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum "
                + "WHERE a.attrelid=CAST(? AS pg_catalog.regclass) AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum";
        try (var p = prepare(c, sql, timeout, reference); var r = p.executeQuery()) {
            while (r.next()) {
                if (columns.size() >= 1024 || r.getInt(6) != 0 || r.getBoolean(7) || !"pg_catalog".equals(
                        r.getString(8)) || !"b".equals(r.getString(9)) || r.getString(10) != null || r.getString(
                        11) != null) {
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
        try (var p = prepare(c, "SELECT 1 FROM pg_catalog.pg_trigger WHERE tgrelid=CAST(? AS pg_catalog.regclass)",
                timeout, reference); var r = p.executeQuery()) {
            if (r.next()) {
                throw unsupported();
            }
        }
        var keys = new ArrayList<String>();
        var constraints = new ArrayList<String>();
        sql = "SELECT co.contype,co.convalidated,co.condeferrable,co.condeferred,co.consoft,co.condisable,co.conkey,"
                + "i.indisvalid,i.indisready,i.indisusable,i.indisenable,i.indimmediate,i.indisvisible,i.indkey::pg_catalog.text,"
                + "i.indexprs IS NULL,i.indpred IS NULL,am.amname,i.indnatts=i.indnkeyatts,i.indnkeyatts,i.indclass::pg_catalog.text,i.indcollation::pg_catalog.text,i.indoption::pg_catalog.text "
                + "FROM pg_catalog.pg_constraint co LEFT JOIN pg_catalog.pg_index i ON i.indexrelid=co.conindid "
                + "LEFT JOIN pg_catalog.pg_class ix ON ix.oid=i.indexrelid LEFT JOIN pg_catalog.pg_am am ON am.oid=ix.relam "
                + "WHERE co.conrelid=CAST(? AS pg_catalog.regclass) ORDER BY co.conname";
        try (var p = prepare(c, sql, timeout, reference); var r = p.executeQuery()) {
            while (r.next()) {
                if (constraints.size() >= 256 || !Set.of("p", "u").contains(r.getString(1)) || !r.getBoolean(
                        2) || r.getBoolean(3) || r.getBoolean(4) || r.getBoolean(5) || r.getBoolean(6)) {
                    throw unsupported();
                }
                for (int i = 8; i <= 13; i++) {
                    if (!r.getBoolean(i) || r.wasNull()) {
                        throw unsupported();
                    }
                }
                if (!r.getBoolean(15) || !r.getBoolean(16) || !"btree".equals(r.getString(17)) || !r.getBoolean(
                        18) || r.getInt(19) < 1 || r.getInt(19) > 32) {
                    throw unsupported();
                }
                var numbers = new ArrayList<Integer>();
                Array array = r.getArray(7);
                if (array == null) {
                    throw unsupported();
                }
                try {
                    Object raw = array.getArray();
                    if (!(raw instanceof Object[] values) || values.length != r.getInt(19)) {
                        throw unsupported();
                    }
                    for (Object value : values) {
                        if (!(value instanceof Number n) || n.intValue() < 1) {
                            throw unsupported();
                        }
                        numbers.add(n.intValue());
                    }
                } finally {
                    array.free();
                }
                var names = new ArrayList<String>();
                for (int number : numbers) {
                    var column = columns.values().stream().filter(x -> x.number() == number).findFirst()
                            .orElseThrow(OpengaussCursorMetadata::unsupported);
                    names.add(column.name());
                }
                if (!r.getString(14).equals(String.join(" ", numbers.stream().map(Object::toString).toList()))) {
                    throw unsupported();
                }
                if ("p".equals(r.getString(1))) {
                    if (!keys.isEmpty()) {
                        throw unsupported();
                    }
                    keys.addAll(names);
                    verifyOrdering(c, columns, names, r.getString(20), r.getString(21), r.getString(22), timeout);
                }
                constraints.add(
                        r.getString(1) + names + ":" + r.getString(20) + ":" + r.getString(21) + ":" + r.getString(22));
            }
        }
        if (keys.isEmpty() || !identity.equals(
                OpengaussRelationIdentity.physicalFingerprint(c, schema, table, timeout))) {
            throw unsupported();
        }
        return new Shape(identity,
                DigestUtil.sha256Hex("OPENGAUSS_REPLAY_SCHEMA_V1\0" + columns.values() + '\0' + constraints), columns,
                keys);
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
            numeric(column);
            if (!column.notNull() || !collationIds[i].equals("0") || !flags[i].equals("0") || !classIds[i].matches(
                    "[1-9][0-9]{0,9}")) {
                throw unsupported();
            }
            try (var p = prepare(c,
                    "SELECT o.opcdefault,n.nspname,t.typname,am.amname FROM pg_catalog.pg_opclass o JOIN pg_catalog.pg_namespace n ON n.oid=o.opcnamespace JOIN pg_catalog.pg_type t ON t.oid=o.opcintype JOIN pg_catalog.pg_am am ON am.oid=o.opcmethod WHERE o.oid=CAST(? AS pg_catalog.oid)",
                    timeout, classIds[i]); var r = p.executeQuery()) {
                if (!r.next() || !r.getBoolean(1) || !"pg_catalog".equals(r.getString(2)) || !column.type()
                        .equals(r.getString(3)) || !"btree".equals(r.getString(4)) || r.next()) {
                    throw unsupported();
                }
            }
        }
    }

    /**
     * 返回当前契约。
     */
    public static CursorKeyContract contract(Shape shape, List<String> keys) throws SQLException {
        if (!shape.keys().equals(keys)) {
            throw unsupported();
        }
        var domains = new ArrayList<CursorKeyContract.Domain>();
        for (String key : keys) {
            domains.add(numeric(shape.columns().get(key)));
        }
        return new CursorKeyContract(ProductTypeEnum.OPENGAUSS,
                DigestUtil.sha256Hex("OPENGAUSS_EXACT_NUMERIC_KEYS_V1\0" + domains), domains);
    }

    /**
     * 按当前值域约定解析或转换数值。
     */
    public static CursorKeyContract.Domain numeric(Column c) throws SQLException {
        return switch (c.type()) {
            case "int2" -> CursorKeyContract.Domain.integer(16, false);
            case "int4" -> CursorKeyContract.Domain.integer(32, false);
            case "int8" -> CursorKeyContract.Domain.integer(64, false);
            case "numeric" -> {
                if (c.modifier() < 4 || c.precision() < 1 || c.precision() > 1000 || c.scale() > c.precision()) {
                    throw unsupported();
                }
                yield CursorKeyContract.Domain.decimal(c.precision(), c.scale(), false);
            }
            default -> throw unsupported();
        };
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
                numeric(c);
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
            default -> throw unsupported();
        };
    }

    /**
     * 生成游标键列用于查询或重放的原生类型声明。
     */
    public static String keyType(Column c) throws SQLException {
        numeric(c);
        return c.type().equals("numeric") ? "numeric(" + c.precision() + "," + c.scale() + ")" : c.type();
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Object read(ResultSet r, int i, Column c) throws SQLException {
        return switch (jdbcType(c)) {
            case Types.NUMERIC -> r.getBigDecimal(i);
            case Types.TIMESTAMP -> r.getTimestamp(i);
            case Types.TIME -> r.getObject(i, LocalTime.class);
            case Types.DATE -> r.getDate(i);
            default -> r.getObject(i);
        };
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
            var domain = numeric(c).numeric();
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
            if (t == null || t.getNano() % pow10(9 - c.scale()) != 0) {
                throw unsupported();
            }
            return Timestamp.valueOf(t);
        }
        if (type == Types.TIME) {
            LocalTime t = value instanceof LocalTime v ? v : value instanceof Time v ? v.toLocalTime() : null;
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
            throw new SQLException("openGauss A-mode cannot preserve empty or zero-containing text");
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
    private static PreparedStatement prepare(Connection c, String sql, int timeout, String value) throws SQLException {
        var p = c.prepareStatement(sql);
        try {
            p.setQueryTimeout(timeout);
            p.setString(1, value);
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
        return new SQLException("openGauss replay catalog, type or exact value is not verified");
    }
}
