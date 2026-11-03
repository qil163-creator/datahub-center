package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumNativeTables;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Greenplum 7.1.0 堆表的证据：采用哈希分布策略及顺序精确的数值
 * 主键。GP 7.1.0 基于 PostgreSQL 12.12，但 SERIALIZABLE 会归为其可重复读
 * 快照隔离，目录增加了回移植的自增和生成列，而 {@code pg_attrdef}
 * 仅保留 {@code adbin}；此处绝不复用 PostgreSQL 16 的目录假设。
 */
public final class GreenplumCursorMetadata {

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
                        List<String> distribution) {

        /**
         * 初始化 {@code Shape}。
         */
        public Shape {
            columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
            keys = List.copyOf(keys);
            distribution = List.copyOf(distribution);
        }
    }

    private static final String VERSION_PREFIX = "PostgreSQL 12.12 (Greenplum Database 7.1.0 build dev)";

    private static final String SERVER_VERSION = "7.1.0 build dev";

    /**
     * 初始化 {@code GreenplumCursorMetadata}。
     */
    private GreenplumCursorMetadata() {
    }

    /**
     * 按Greenplum方言转义并引用标识符。
     */
    public static String quote(String name) {
        return GreenplumNativeTables.quote(name);
    }

    /**
     * 生成当前操作引用的限定关系名。
     */
    public static String reference(String schema, String table) {
        return quote(schema) + '.' + quote(table);
    }

    /**
     * 获取当前操作所需的数据库或资源锁。
     */
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

    /** 通过绑定的模式名和表名（不使用 regclass 文本）获取原生身份，并强制验证堆表和哈希配置。 */
    public static String identity(Connection c, String schema, String table, int timeout) throws SQLException {
        String sql = "SELECT pg_catalog.version(),pg_catalog.current_setting('gp_server_version'),s.system_identifier::pg_catalog.text,"
                + "pg_catalog.current_database(),d.oid::pg_catalog.text,current_user,n.nspname,c.relname,c.oid::pg_catalog.text,"
                + "c.relfilenode::pg_catalog.text,p.policytype::pg_catalog.text,p.numsegments::pg_catalog.text,p.distkey::pg_catalog.text,"
                + "a.amname,c.relkind='r' AND c.relpersistence='p' AND c.relfilenode>0 AND NOT c.relispartition "
                + "AND NOT c.relhassubclass AND NOT c.relhasrules AND NOT c.relrowsecurity AND c.relpartbound IS NULL "
                + "AND a.amname='heap' AND p.policytype='p' AND p.distkey::pg_catalog.text ~ '^[1-9][0-9]*( [1-9][0-9]*)*$' "
                + "AND p.numsegments=(SELECT count(*) FROM pg_catalog.gp_segment_configuration g WHERE g.content>=0 AND g.role='p' AND g.status='u') "
                + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid) "
                + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_policy pol WHERE pol.polrelid=c.oid) "
                + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "JOIN pg_catalog.pg_database d ON d.datname=pg_catalog.current_database() JOIN pg_catalog.pg_am a ON a.oid=c.relam "
                + "JOIN pg_catalog.gp_distribution_policy p ON p.localoid=c.oid CROSS JOIN pg_catalog.pg_control_system() s "
                + "WHERE n.nspname=? AND c.relname=?";
        try (var p = prepare(c, sql, timeout, schema, table); var r = p.executeQuery()) {
            if (!r.next()) {
                throw unsupported();
            }
            var fields = new ArrayList<>(List.of("GREENPLUM_7_1_HEAP_HASH_V1"));
            for (int index = 1; index <= 14; index++) {
                String value = r.getString(index);
                if (value == null || value.isBlank() || value.length() > 1024 || value.indexOf('\0') >= 0) {
                    throw unsupported();
                }
                fields.add(value);
            }
            if (!fields.get(1).startsWith(VERSION_PREFIX) || !SERVER_VERSION.equals(fields.get(2))
                    || !fields.get(3).matches("[1-9][0-9]{0,19}") || !fields.get(5).matches("[1-9][0-9]{0,9}")
                    || !fields.get(9).matches("[1-9][0-9]{0,9}") || !fields.get(10).matches("[1-9][0-9]{0,9}")
                    || !"p".equals(fields.get(11)) || !fields.get(12).matches("[1-9][0-9]{0,4}")
                    || !fields.get(13).matches("[1-9][0-9]*( [1-9][0-9]*)*") || !"heap".equals(fields.get(14))
                    || !r.getBoolean(15) || r.wasNull() || r.next()) {
                throw unsupported();
            }
            return DigestUtil.sha256Hex(String.join("\0", fields));
        }
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
                + "WHERE a.attrelid=(SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname=? AND c.relname=?) AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum";
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
                "SELECT 1 FROM pg_catalog.pg_trigger WHERE tgrelid=(SELECT c.oid FROM pg_catalog.pg_class c "
                        + "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?)",
                timeout, schema, table);
             var r = p.executeQuery()) {
            if (r.next()) {
                throw unsupported();
            }
        }
        var keys = new ArrayList<String>();
        var constraints = new ArrayList<String>();
        sql = "SELECT co.contype,co.convalidated,co.condeferrable,co.condeferred,co.conkey::pg_catalog.text,"
                + "i.indisvalid,i.indisready,i.indislive,i.indimmediate,i.indexprs IS NULL,i.indpred IS NULL,am.amname,"
                + "i.indnatts=i.indnkeyatts,i.indnkeyatts,i.indclass::pg_catalog.text,i.indcollation::pg_catalog.text,i.indoption::pg_catalog.text,"
                + "pg_catalog.pg_get_constraintdef(co.oid) "
                + "FROM pg_catalog.pg_constraint co LEFT JOIN pg_catalog.pg_index i ON i.indexrelid=co.conindid "
                + "LEFT JOIN pg_catalog.pg_class ix ON ix.oid=i.indexrelid LEFT JOIN pg_catalog.pg_am am ON am.oid=ix.relam "
                + "WHERE co.conrelid=(SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname=? AND c.relname=?) ORDER BY co.conname";
        try (var p = prepare(c, sql, timeout, schema, table); var r = p.executeQuery()) {
            while (r.next()) {
                if (constraints.size() >= 256 || !Set.of("p", "u", "c").contains(r.getString(1)) || !r.getBoolean(2)
                        || r.getBoolean(3) || r.getBoolean(4)) {
                    throw unsupported();
                }
                if ("c".equals(r.getString(1))) {
                    // CHECK 约束不能涉及游标键或 ON CONFLICT 仲裁键，仅对已验证的约束定义生成指纹。
                    String definition = r.getString(18);
                    if (definition == null || definition.isBlank() || definition.length() > 4096 || definition.indexOf(
                            '\0') >= 0) {
                        throw unsupported();
                    }
                    constraints.add("c:" + definition);
                    continue;
                }
                for (int i = 6; i <= 11; i++) {
                    if (!r.getBoolean(i) || r.wasNull()) {
                        throw unsupported();
                    }
                }
                if (!r.getBoolean(13) || !"btree".equals(r.getString(12)) || r.getInt(14) < 1 || r.getInt(14) > 32) {
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
                if (numbers.size() != r.getInt(14)) {
                    throw unsupported();
                }
                var names = new ArrayList<String>();
                for (int number : numbers) {
                    var column = columns.values().stream().filter(x -> x.number() == number).findFirst()
                            .orElseThrow(GreenplumCursorMetadata::unsupported);
                    names.add(column.name());
                }
                if ("p".equals(r.getString(1))) {
                    if (!keys.isEmpty()) {
                        throw unsupported();
                    }
                    keys.addAll(names);
                    verifyOrdering(c, columns, names, r.getString(15), r.getString(16), r.getString(17), timeout);
                }
                constraints.add(
                        r.getString(1) + names + ":" + r.getString(15) + ":" + r.getString(16) + ":" + r.getString(17));
            }
        }
        if (keys.isEmpty() || !identity.equals(identity(c, schema, table, timeout))) {
            throw unsupported();
        }
        var distribution = new ArrayList<String>();
        try (var p = prepare(c,
                "SELECT p.policytype::pg_catalog.text,p.distkey::pg_catalog.text FROM pg_catalog.gp_distribution_policy p "
                        + "WHERE p.localoid=(SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                        + "WHERE n.nspname=? AND c.relname=?)", timeout, schema, table); var r = p.executeQuery()) {
            if (!r.next() || !"p".equals(r.getString(1)) || r.getString(2) == null || !r.getString(2)
                    .matches("[1-9][0-9]*( [1-9][0-9]*)*")) {
                throw unsupported();
            }
            String policy = r.getString(1);
            String distKeys = r.getString(2);
            if (r.next()) {
                throw unsupported();
            }
            distribution.add(policy);
            distribution.add(distKeys);
        }
        return new Shape(identity, DigestUtil.sha256Hex(
                "GREENPLUM_REPLAY_SCHEMA_V1\0" + columns.values() + '\0' + constraints + '\0' + distribution), columns,
                keys, distribution);
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
            if (!column.notNull() || !"0".equals(collationIds[i]) || !"0".equals(flags[i]) || !classIds[i].matches(
                    "[1-9][0-9]{0,9}")) {
                throw unsupported();
            }
            try (var p = prepare(c, "SELECT o.opcdefault,n.nspname,t.typname,am.amname FROM pg_catalog.pg_opclass o "
                            + "JOIN pg_catalog.pg_namespace n ON n.oid=o.opcnamespace JOIN pg_catalog.pg_type t ON t.oid=o.opcintype "
                            + "JOIN pg_catalog.pg_am am ON am.oid=o.opcmethod WHERE o.oid=CAST(? AS pg_catalog.oid)", timeout,
                    classIds[i]);
                 var r = p.executeQuery()) {
                if (!r.next() || !r.getBoolean(1) || !"pg_catalog".equals(r.getString(2)) || !column.type()
                        .equals(r.getString(3))
                        || !"btree".equals(r.getString(4)) || r.next()) {
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
        return new CursorKeyContract(ProductTypeEnum.GREENPLUM,
                DigestUtil.sha256Hex("GREENPLUM_EXACT_NUMERIC_KEYS_V1\0" + domains), domains);
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
            case "timestamptz" -> Types.TIMESTAMP_WITH_TIMEZONE;
            default -> throw unsupported();
        };
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Object read(ResultSet r, int i, Column c) throws SQLException {
        return switch (jdbcType(c)) {
            case Types.NUMERIC -> r.getBigDecimal(i);
            case Types.TIMESTAMP -> r.getObject(i, LocalDateTime.class);
            case Types.TIMESTAMP_WITH_TIMEZONE -> r.getObject(i, java.time.OffsetDateTime.class);
            case Types.TIME -> readTime(r, i);
            case Types.DATE -> r.getObject(i, LocalDate.class);
            default -> r.getObject(i);
        };
    }

    /** GP TIME 存储微秒；pgjdbc 默认的 java.sql.Time 载体会截断为毫秒，且无法表示 24:00:00。 */
    private static LocalTime readTime(ResultSet rows, int index) throws SQLException {
        String text = rows.getString(index);
        if (text == null) {
            return null;
        }
        if (text.startsWith("24:")) {
            throw new SQLException("Greenplum TIME 24:00:00 has no exact JDBC carrier");
        }
        try {
            return LocalTime.parse(text);
        } catch (RuntimeException invalid) {
            throw new SQLException("Greenplum TIME text is not representable");
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
            LocalTime t = value instanceof LocalTime v ? v : value instanceof java.sql.Time v ? v.toLocalTime() : null;
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
            throw new SQLException("Greenplum cannot preserve zero-containing text");
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
        return new SQLException("Greenplum replay catalog, distribution, key or exact value is not verified");
    }
}
