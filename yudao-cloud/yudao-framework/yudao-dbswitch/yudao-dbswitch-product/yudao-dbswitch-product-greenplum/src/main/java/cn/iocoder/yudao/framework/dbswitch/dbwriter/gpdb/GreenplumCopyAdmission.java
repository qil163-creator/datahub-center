package cn.iocoder.yudao.framework.dbswitch.dbwriter.gpdb;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.util.GreenplumCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresTransactionRoute;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresCopyDriverProfile;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.nativecopy.PostgresCopySession;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition;
import cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumNativeTables;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.*;
import java.util.*;

/** 单次操作的 GP 7.1.0 堆表和哈希分布二进制 COPY 证据，不能推断清单已获认证。 */
final class GreenplumCopyAdmission {

    private record Type(int oid, int jdbc) {

    }

    private static final Map<String, Type> TYPES = Map.ofEntries(
            Map.entry("int2", new Type(21, Types.SMALLINT)), Map.entry("int4", new Type(23, Types.INTEGER)),
            Map.entry("int8", new Type(20, Types.BIGINT)), Map.entry("numeric", new Type(1700, Types.NUMERIC)),
            Map.entry("float4", new Type(700, Types.REAL)), Map.entry("float8", new Type(701, Types.DOUBLE)),
            Map.entry("bool", new Type(16, Types.BIT)), Map.entry("text", new Type(25, Types.VARCHAR)),
            Map.entry("varchar", new Type(1043, Types.VARCHAR)), Map.entry("bpchar", new Type(1042, Types.CHAR)),
            Map.entry("bytea", new Type(17, Types.BINARY)), Map.entry("date", new Type(1082, Types.DATE)),
            Map.entry("time", new Type(1083, Types.TIME)), Map.entry("timestamp", new Type(1114, Types.TIMESTAMP)));

    private record Column(String type, int modifier, boolean notNull) {

    }

    private final String schema, table, route, identity;

    private final Map<String, Column> columns;

    /**
     * 初始化 {@code GreenplumCopyAdmission}。
     */
    private GreenplumCopyAdmission(String schema, String table, String route, String identity,
            Map<String, Column> columns) {
        this.schema = schema;
        this.table = table;
        this.route = route;
        this.identity = identity;
        this.columns = Map.copyOf(columns);
    }

    /**
     * 检查当前运行时环境及驱动是否满足既定协议。
     */
    static void verifyRuntime(Connection connection) throws SQLException {
        if (!connection.getAutoCommit() || connection.isReadOnly() || Thread.currentThread().isInterrupted()) {
            throw unsupported();
        }
        session(connection);
    }

    /** 原生计划替换目标前，必须完成存储方式和二进制编码准入。 */
    static void preflightNativeTarget(NativeTableDefinition source, Map<String, String> mapping) throws SQLException {
        try {
            var definition = GreenplumNativeTables.decode(source);
            if (!"heap".equals(definition.storage()) || !"p".equals(definition.distribution())
                    || definition.distributionKeys().isEmpty() || definition.columns().size() > 1024
                    || mapping == null || !mapping.keySet().equals(new HashSet<>(definition.fields()))
                    || new HashSet<>(mapping.values()).size() != mapping.size()) {
                throw unsupported();
            }
            mapping.values().forEach(GreenplumNativeTables::quote);
            for (var column : definition.columns()) {
                if (!column.type().matches("(?:smallint|integer|bigint|boolean|real|double precision|text|bytea|date|"
                        + "numeric(?:\\([0-9]{1,4}(?:,[0-9]{1,4})?\\))?|character(?: varying)?(?:\\([0-9]{1,7}\\))?|"
                        + "(?:timestamp|time)(?:\\([0-6]\\))? without time zone)")) {
                    throw unsupported();
                }
            }
        } catch (RuntimeException invalid) {
            throw unsupported();
        }
    }

    /**
     * 校验并取得数据库关系。
     */
    void requireRelation(String schema, String table) throws SQLException {
        if (!this.schema.equals(schema) || !this.table.equals(table)) {
            throw unsupported();
        }
    }

    /**
     * 返回字段集合。
     */
    List<String> fields() {
        return List.copyOf(columns.keySet());
    }

    /**
     * 返回会话。
     */
    private static String session(Connection connection) throws SQLException {
        PostgresCopyDriverProfile.verify(connection);
        PostgresCopySession.prepare(connection); // 解析全部协议方法，但不启动 COPY。
        try (var query = connection.createStatement()) {
            query.setQueryTimeout(5);
            query.setMaxRows(2);
            try (var rows = query.executeQuery(
                    "SELECT pg_catalog.version(),pg_catalog.current_setting('gp_server_version'),"
                            + "pg_catalog.current_setting('server_encoding'),pg_catalog.current_setting('client_encoding'),"
                            + "pg_catalog.current_setting('integer_datetimes')," + PostgresTransactionRoute.COLUMNS
                            + PostgresTransactionRoute.FROM)) {
                if (!rows.next() || !rows.getString(1)
                        .startsWith("PostgreSQL 12.12 (Greenplum Database 7.1.0 build dev)")
                        || !"7.1.0 build dev".equals(rows.getString(2)) || !"UTF8".equals(rows.getString(3))
                        || !"UTF8".equals(rows.getString(4)) || !"on".equals(rows.getString(5))) {
                    throw unsupported();
                }
                String route = PostgresTransactionRoute.fingerprint(rows, 6);
                if (rows.next()) {
                    throw unsupported();
                }
                return route;
            }
        }
    }

    /**
     * 采集当前对象身份、结构或运行状态的快照。
     */
    static GreenplumCopyAdmission capture(Connection connection, String schema, String table,
            List<String> fields) throws SQLException {
        if (fields == null || fields.isEmpty() || fields.size() > 1024 || new HashSet<>(
                fields).size() != fields.size()) {
            throw unsupported();
        }
        String route = session(connection);
        String identity = GreenplumCursorMetadata.identity(connection, schema, table, 5);
        var columns = new LinkedHashMap<String, Column>();
        String sql = "SELECT a.attname,t.typname,t.oid,a.atttypmod,a.attnotnull,a.attndims,a.attidentity,a.attgenerated,"
                + "n.nspname,t.typtype,EXISTS(SELECT 1 FROM pg_catalog.pg_trigger tr WHERE tr.tgrelid=c.oid)"
                + " FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace ns ON ns.oid=c.relnamespace"
                + " JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid JOIN pg_catalog.pg_type t ON t.oid=a.atttypid"
                + " JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace"
                + " WHERE ns.nspname=? AND c.relname=? AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum";
        try (var query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(5);
            query.setMaxRows(1025);
            query.setString(1, schema);
            query.setString(2, table);
            try (var rows = query.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 1024 || rows.getBoolean(11)) {
                        throw unsupported();
                    }
                    if (!fields.contains(rows.getString(1))) {
                        continue;
                    }
                    Type type = TYPES.get(rows.getString(2));
                    if (type == null || type.oid() != rows.getInt(3) || rows.getInt(6) != 0
                            || !"".equals(rows.getString(7)) || !"".equals(rows.getString(8))
                            || !"pg_catalog".equals(rows.getString(9)) || !"b".equals(rows.getString(10))) {
                        throw unsupported();
                    }
                    if (columns.put(rows.getString(1),
                            new Column(rows.getString(2), rows.getInt(4), rows.getBoolean(5))) != null) {
                        throw unsupported();
                    }
                }
            }
        }
        if (!columns.keySet().equals(new HashSet<>(fields))) {
            throw unsupported();
        }
        return new GreenplumCopyAdmission(schema, table, route, identity, columns);
    }

    /**
     * 检查 JDBC 返回的列布局与当前读取协议一致。
     */
    void requireJdbcLayout(Map<String, Integer> jdbc) throws SQLException {
        for (var entry : columns.entrySet()) {
            if (!Objects.equals(TYPES.get(entry.getValue().type()).jdbc(), jdbc.get(entry.getKey()))) {
                throw unsupported();
            }
        }
    }

    /**
     * 校验批次。
     */
    void verifyBatch(Connection connection, List<String> fields) throws SQLException {
        if (connection.getAutoCommit() || connection.isReadOnly() || Thread.currentThread().isInterrupted()) {
            throw unsupported();
        }
        GreenplumCursorMetadata.lock(connection, schema, table, true, 5);
        var current = capture(connection, schema, table, fields);
        if (!route.equals(current.route) || !identity.equals(current.identity)
                || fields.stream().anyMatch(name -> !Objects.equals(columns.get(name), current.columns.get(name)))) {
            throw unsupported();
        }
    }

    /**
     * 按目标类型约定转换当前输入值。
     */
    Object convert(String field, Object value) {
        try {
            Column column = columns.get(field);
            if (column == null || value == null && column.notNull()) {
                throw unsupported();
            }
            Object converted = JdbcValueConversions.forJdbcType(value, TYPES.get(column.type()).jdbc());
            if (converted == null) {
                return null;
            }
            if (converted instanceof BigDecimal decimal) {
                long integerDigits = (long) decimal.precision() - decimal.scale();
                long encodedDigits = decimal.scale() > 0 ? decimal.precision() + (4 - decimal.scale() % 4) % 4 : integerDigits;
                if (decimal.scale() < -131067 || decimal.scale() > 16383 || integerDigits > 131068 || (encodedDigits + 3) / 4 > 32767) {
                    throw unsupported();
                }
                if (column.modifier() >= 4) {
                    int precision = (column.modifier() - 4) >>> 16, scale = (column.modifier() - 4) & 65535;
                    if (scale > precision || decimal.setScale(scale, RoundingMode.UNNECESSARY)
                            .precision() > precision) {
                        throw unsupported();
                    }
                }
            } else if (converted instanceof String text) {
                if (text.indexOf('\0') >= 0) {
                    throw unsupported();
                }
                java.nio.charset.StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text));
                if (!"text".equals(column.type()) && column.modifier() >= 4
                        && text.codePointCount(0, text.length()) > column.modifier() - 4) {
                    throw unsupported();
                }
            } else if (converted instanceof LocalDate date) {
                if (date.getYear() < 1 || date.getYear() > 9999) {
                    throw unsupported();
                }
            } else if (converted instanceof LocalDateTime timestamp) {
                if (timestamp.getYear() < 1 || timestamp.getYear() > 9999 || !timeFits(timestamp.getNano(),
                        column.modifier())) {
                    throw unsupported();
                }
            } else if (converted instanceof LocalTime time) {
                if (!timeFits(time.getNano(), column.modifier())) {
                    throw unsupported();
                }
            } else if (converted instanceof Float floatValue && !Float.isFinite(floatValue)
                    || converted instanceof Double doubleValue && !Double.isFinite(doubleValue)) {
                throw unsupported();
            }
            return converted;
        } catch (Exception invalid) {
            throw new BatchRejectedException(
                    "Greenplum COPY input cannot be represented exactly by the admitted native column");
        }
    }

    /**
     * 检查时间值的小数部分能否由声明精度精确表示。
     */
    private static boolean timeFits(int nanos, int modifier) {
        int scale = modifier < 0 ? 6 : modifier;
        return scale >= 0 && scale <= 6 && nanos % (int) Math.pow(10, 9 - scale) == 0;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    static SQLFeatureNotSupportedException unsupported() {
        return new SQLFeatureNotSupportedException(
                "Greenplum WRITE_COPY runtime profile, session or column layout is not verified", "0A000");
    }
}
