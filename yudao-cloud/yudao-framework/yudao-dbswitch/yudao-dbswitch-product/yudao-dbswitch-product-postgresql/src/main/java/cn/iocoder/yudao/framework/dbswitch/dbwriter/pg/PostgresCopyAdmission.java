package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresTransactionRoute;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.PostgreSqlUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仅为一次原生 PostgreSQL 二进制 COPY 操作准入，不代表整个产品认证。
 * 仓库声明仍为 DECLARED；写入器还需要已测试的驱动构件、
 * 真实服务器和会话事实，以及受支持的物理列布局，调用方布尔值不能授予准入。
 * 不可变观测结果不保留连接、凭据或行值。
 */
final class PostgresCopyAdmission {

    // PG18 配置仅证明普通 COPY 批次；PG16 事务回执和游标
    // 属于独立契约，不能从本次准入推断。
    private static final Set<Integer> SERVER_VERSIONS = Set.of(160015, 180004);

    private static final String SESSION_SQL = "SELECT pg_catalog.version(), "
            + "pg_catalog.current_setting('server_version_num'), pg_catalog.current_setting('client_encoding'), "
            + "pg_catalog.current_setting('integer_datetimes'), " + PostgresTransactionRoute.COLUMNS
            + ", (pg_catalog.current_schemas(true))[1]" + PostgresTransactionRoute.FROM;

    private static final String COLUMNS_SQL = """
            SELECT c.oid::text, c.relfilenode::text, c.relkind::text, c.relpersistence::text,
                   c.relispartition, c.relrowsecurity, a.attname, t.typname, n.nspname,
                   a.atttypmod, a.attndims, t.typtype::text, a.attgenerated::text, t.oid::text,
                   t.typelem::text, e.typname, en.nspname, e.typtype::text
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid
            JOIN pg_catalog.pg_type t ON t.oid=a.atttypid
            JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
            LEFT JOIN pg_catalog.pg_type e ON e.oid=t.typelem
            LEFT JOIN pg_catalog.pg_namespace en ON en.oid=e.typnamespace
            WHERE c.oid=pg_catalog.to_regclass(?) AND a.attnum>0 AND NOT a.attisdropped
            ORDER BY a.attnum
            """;

    private static final Map<String, Set<Integer>> JDBC_TYPES = Map.ofEntries(
            Map.entry("int2", Set.of(Types.SMALLINT)), Map.entry("int4", Set.of(Types.INTEGER)),
            Map.entry("int8", Set.of(Types.BIGINT)), Map.entry("bool", Set.of(Types.BOOLEAN, Types.BIT)),
            Map.entry("numeric", Set.of(Types.NUMERIC, Types.DECIMAL)),
            Map.entry("float4", Set.of(Types.REAL, Types.FLOAT)), Map.entry("float8", Set.of(Types.DOUBLE)),
            Map.entry("text", Set.of(Types.VARCHAR, Types.LONGVARCHAR)), Map.entry("varchar", Set.of(Types.VARCHAR)),
            Map.entry("bpchar", Set.of(Types.CHAR)), Map.entry("bytea", Set.of(Types.BINARY, Types.VARBINARY)),
            Map.entry("date", Set.of(Types.DATE)), Map.entry("time", Set.of(Types.TIME)),
            Map.entry("timestamp", Set.of(Types.TIMESTAMP)), Map.entry("uuid", Set.of(Types.OTHER)),
            Map.entry("jsonb", Set.of(Types.OTHER)));

    private record Session(int serverVersion, String routeFingerprint) {

    }

    private record ArrayType(String arrayOid, String elementOid, String elementName) {

    }

    private static final Map<String, ArrayType> ARRAY_TYPES = Map.of(
            "_int2", new ArrayType("1005", "21", "int2"), "_int4", new ArrayType("1007", "23", "int4"),
            "_int8", new ArrayType("1016", "20", "int8"), "_bool", new ArrayType("1000", "16", "bool"),
            "_text", new ArrayType("1009", "25", "text"), "_numeric", new ArrayType("1231", "1700", "numeric"));

    private record Column(String nativeType, int typeModifier, String nativeOid, String elementName,
                          String elementOid, int declarationDimensions) {

    }

    private final String schema;

    private final String table;

    private final String reference;

    private final Session session;

    private final String relationOid;

    private final Map<String, Column> columns;

    /**
     * 初始化 {@code PostgresCopyAdmission}。
     */
    private PostgresCopyAdmission(String schema, String table, String reference, Session session,
            String relationOid, Map<String, Column> columns) {
        this.schema = schema;
        this.table = table;
        this.reference = reference;
        this.session = session;
        this.relationOid = relationOid;
        this.columns = Map.copyOf(columns);
    }

    /**
     * 采集当前对象身份、结构或运行状态的快照。
     */
    static PostgresCopyAdmission capture(Connection connection, String schema, String table, List<String> fields)
            throws SQLException {
        String reference = PostgreSqlUtils.quoteIdentifier(schema) + '.' + PostgreSqlUtils.quoteIdentifier(table);
        var session = session(connection);
        Set<String> requested = fields == null || fields.isEmpty() ? null : Set.copyOf(fields);
        if (requested != null && requested.size() != fields.size()) {
            throw unsupported();
        }
        var columns = new LinkedHashMap<String, Column>();
        String relation = null;
        try (var query = connection.prepareStatement(COLUMNS_SQL)) {
            query.setQueryTimeout(5);
            query.setMaxRows(1601);
            query.setString(1, reference);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (!"r".equals(rows.getString(3)) || !"p".equals(rows.getString(4))
                            || rows.getBoolean(5) || rows.getBoolean(6)
                            || rows.getString(1) == null || !rows.getString(1).matches("[1-9][0-9]*")
                            || rows.getString(2) == null || !rows.getString(2).matches("[1-9][0-9]*")) {
                        throw unsupported();
                    }
                    if (relation == null) {
                        relation = rows.getString(1);
                    } else if (!relation.equals(rows.getString(1))) {
                        throw unsupported();
                    }
                    String name = rows.getString(7);
                    if (requested != null && !requested.contains(name)) {
                        continue;
                    }
                    String nativeType = rows.getString(8);
                    var arrayType = ARRAY_TYPES.get(nativeType);
                    if (!"pg_catalog".equals(rows.getString(9))
                            || !"b".equals(rows.getString(12)) || !"".equals(rows.getString(13))
                            || arrayType == null && (!JDBC_TYPES.containsKey(nativeType) || rows.getInt(11) != 0)) {
                        throw unsupported();
                    }
                    String nativeOid = rows.getString(14);
                    String elementName = null, elementOid = null;
                    if (arrayType != null) {
                        elementName = rows.getString(16);
                        elementOid = rows.getString(15);
                        if (rows.getInt(11) != 1 || rows.getInt(10) != -1 || !arrayType.arrayOid().equals(nativeOid)
                                || !arrayType.elementOid().equals(elementOid) || !arrayType.elementName()
                                .equals(elementName)
                                || !"pg_catalog".equals(rows.getString(17)) || !"b".equals(rows.getString(18))) {
                            throw unsupported();
                        }
                    }
                    // UUID 不是对任意 OTHER 类型或字符串的授权；其固定二进制布局
                    // 仅属于内置标量，绝不能用于域、数组或同名类型。
                    if ("uuid".equals(nativeType) && (!"2950".equals(nativeOid) || rows.getInt(10) != -1)) {
                        throw unsupported();
                    }
                    if ("jsonb".equals(nativeType) && (!"3802".equals(nativeOid) || rows.getInt(10) != -1)) {
                        throw unsupported();
                    }
                    if (columns.put(name, new Column(nativeType, rows.getInt(10), nativeOid, elementName,
                            elementOid, rows.getInt(11))) != null) {
                        throw unsupported();
                    }
                }
            }
        }
        if (relation == null || columns.isEmpty() || columns.size() > 1600
                || requested != null && !columns.keySet().equals(requested)) {
            throw unsupported();
        }
        return new PostgresCopyAdmission(schema, table, reference, session, relation, columns);
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

    /**
     * 检查 JDBC 返回的列布局与当前读取协议一致。
     */
    void requireJdbcLayout(Map<String, Integer> jdbcTypes) throws SQLException {
        for (var column : columns.entrySet()) {
            Integer jdbc = jdbcTypes == null ? null : jdbcTypes.get(column.getKey());
            if (jdbc == null || (column.getValue().elementName() != null
                    ? jdbc != Types.ARRAY : !JDBC_TYPES.get(column.getValue().nativeType()).contains(jdbc))) {
                throw unsupported();
            }
        }
    }

    /**
     * 判断UUID。
     */
    boolean isUuid(String field) {
        var column = columns.get(field);
        return column != null && "uuid".equals(column.nativeType()) && "2950".equals(column.nativeOid());
    }

    /**
     * 判断JSONB。
     */
    boolean isJsonb(String field) {
        var column = columns.get(field);
        return column != null && "jsonb".equals(column.nativeType()) && "3802".equals(column.nativeOid());
    }

    /** 来自同一固定驱动构件的确切 PGobject，包括隔离的驱动加载器。 */
    static boolean isTestedPgObject(Object value) {
        if (value == null || !"org.postgresql.util.PGobject".equals(value.getClass().getName())) {
            return false;
        }
        try {
            Class<?> driver = Class.forName("org.postgresql.Driver", false, value.getClass().getClassLoader());
            Class<?> pgObject = Class.forName("org.postgresql.util.PGobject", false, driver.getClassLoader());
            var carrierSource = pgObject.getProtectionDomain().getCodeSource();
            var driverSource = driver.getProtectionDomain().getCodeSource();
            return value.getClass() == pgObject && pgObject.getClassLoader() == driver.getClassLoader()
                    && carrierSource != null && driverSource != null
                    && carrierSource.getLocation().equals(driverSource.getLocation())
                    && PostgresCopyDriverProfile.testedArtifact(driver);
        } catch (ClassNotFoundException | LinkageError | RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * 返回当前列数组元素的原生类型名称。
     */
    String arrayElement(String field) {
        var column = columns.get(field);
        return column == null ? null : column.elementName();
    }

    /** 在执行器自主管理的事务内，对 DDL 加锁并重新观测，然后才能打开任何 COPY API。 */
    void verifyBatch(Connection connection, List<String> fields) throws SQLException {
        if (connection.getAutoCommit() || connection.isReadOnly() || Thread.currentThread().isInterrupted()) {
            throw unsupported();
        }
        if (!columns.keySet().containsAll(fields)) {
            throw unsupported();
        }
        try (var lock = connection.createStatement()) {
            lock.setQueryTimeout(5);
            lock.execute("LOCK TABLE ONLY " + reference + " IN ROW EXCLUSIVE MODE");
        }
        var current = capture(connection, schema, table, fields);
        if (!session.equals(current.session) || !relationOid.equals(current.relationOid)
                || fields.stream().anyMatch(name -> !columns.get(name).equals(current.columns.get(name)))) {
            throw unsupported();
        }
    }

    /**
     * 返回会话。
     */
    private static Session session(Connection connection) throws SQLException {
        try {
            var metadata = connection.getMetaData();
            PostgresCopyDriverProfile.verify(connection);
            try (var query = connection.createStatement()) {
                query.setQueryTimeout(5);
                query.setMaxRows(2);
                try (var rows = query.executeQuery(SESSION_SQL)) {
                    if (!rows.next()) {
                        throw unsupported();
                    }
                    int version = Integer.parseInt(rows.getString(2));
                    int major = version / 10000, minor = version % 10000;
                    if (!SERVER_VERSIONS.contains(version) || metadata.getDatabaseMajorVersion() != major
                            || metadata.getDatabaseMinorVersion() != minor || rows.getString(1) == null
                            || !(rows.getString(1).startsWith("PostgreSQL " + major + '.' + minor + " ")
                            || rows.getString(1).startsWith("PostgreSQL " + major + '.' + minor + ", "))
                            || !"UTF8".equals(rows.getString(3)) || !"on".equals(rows.getString(4))) {
                        throw unsupported();
                    }
                    // 纯 CREATE 计划接受内置类型名；应先从目录解析它们，
                    // 避免用户或临时模式中的同名域或类型将其遮蔽。
                    if (!"pg_catalog".equals(rows.getString(11))) {
                        throw unsupported();
                    }
                    var observed = new Session(version, PostgresTransactionRoute.fingerprint(rows, 5));
                    if (rows.next()) {
                        throw unsupported();
                    }
                    return observed;
                }
            }
        } catch (SQLException | LinkageError | RuntimeException invalid) {
            // 元数据或驱动错误可能包含连接 URL，不暴露厂商细节或原因。
            throw unsupported();
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    static SQLFeatureNotSupportedException unsupported() {
        return new SQLFeatureNotSupportedException(
                "PostgreSQL WRITE_COPY runtime profile, session or column layout is not verified", "0A000");
    }
}
