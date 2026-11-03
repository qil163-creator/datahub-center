package cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.util.UxdbCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;

import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** 单次操作的 UXDB 2.1.0.3 和原版 2.1.2.3p 文本 COPY 证据，不代表清单认证。 */
final class UxdbCopyAdmission {

    private static final String HASH = "5e80fd4ceb270033e394ed5a1d94ed99dcfd44e777896a421f9f0b102c277c4e";

    private static final ClassValue<Boolean> TESTED_DRIVER = new ClassValue<>() {
        /**
         * 根据当前规则和输入字段计算结果值。
         */
        @Override
        protected Boolean computeValue(Class<?> driver) {
            try {
                var source = driver.getProtectionDomain().getCodeSource();
                if (source == null || !"file".equals(source.getLocation().getProtocol())) {
                    return false;
                }
                Path file = Path.of(source.getLocation().toURI());
                if (!Files.isRegularFile(file) || Files.size(file) > 16 * 1024 * 1024) {
                    return false;
                }
                var hash = MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    for (int size; (size = input.read(buffer)) != -1; ) {
                        hash.update(buffer, 0, size);
                    }
                }
                return HASH.equals(HexFormat.of().formatHex(hash.digest()));
            } catch (Exception | LinkageError invalid) {
                return false;
            }
        }
    };

    private final String schema, table;

    private final UxdbCursorMetadata.Shape shape;

    /**
     * 初始化 {@code UxdbCopyAdmission}。
     */
    private UxdbCopyAdmission(String schema, String table, UxdbCursorMetadata.Shape shape) {
        this.schema = schema;
        this.table = table;
        this.shape = shape;
    }

    /**
     * 返回当前产品使用的受控原生 API 入口。
     */
    static Object nativeApi(Connection connection) throws SQLException {
        try {
            var metadata = connection.getMetaData();
            if (!"Uxdb".equals(metadata.getDatabaseProductName()) || !"Uxdb JDBC Driver".equals(
                    metadata.getDriverName())
                    || !"2.1.2.3p".equals(metadata.getDriverVersion())) {
                throw unsupported();
            }
            Object nativeConnection = DriverNativeAccess.unwrap(connection, "com.uxsino.uxdb.UXConnection");
            if (nativeConnection == null) {
                throw unsupported();
            }
            ClassLoader loader = nativeConnection.getClass().getClassLoader();
            Class<?> driver = Class.forName("com.uxsino.uxdb.Driver", false, loader);
            Class<?> api = Class.forName("com.uxsino.uxdb.UXConnection", false, loader);
            if (!api.isInstance(nativeConnection) || !TESTED_DRIVER.get(driver)) {
                throw unsupported();
            }
            Object manager = api.getMethod("getCopyAPI").invoke(nativeConnection);
            Class<?> managerClass = Class.forName("com.uxsino.uxdb.copy.CopyManager", false, loader);
            if (!managerClass.isInstance(manager) || managerClass.getMethod("copyIn", String.class,
                    java.io.InputStream.class, int.class).getReturnType() != long.class) {
                throw unsupported();
            }
            return manager;
        } catch (Exception | LinkageError invalid) {
            throw unsupported();
        }
    }

    /**
     * 返回会话。
     */
    static void session(Connection connection) throws SQLException {
        if (connection.isReadOnly() || Thread.currentThread().isInterrupted()) {
            throw unsupported();
        }
        nativeApi(connection);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery(
                    "SELECT ux_catalog.version(),ux_catalog.current_setting('server_version'),"
                            + "ux_catalog.current_setting('server_encoding'),ux_catalog.current_setting('client_encoding')")) {
                if (!rows.next() || !rows.getString(1).startsWith("UXsinoDB 2.1.0.3 on ") || !"10.0".equals(
                        rows.getString(2))
                        || !"UTF8".equals(rows.getString(3)) || !"UTF8".equals(rows.getString(4)) || rows.next()) {
                    throw unsupported();
                }
            }
        }
    }

    /**
     * 解析当前配置对应的数据库运行时。
     */
    static void runtime(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw unsupported();
        }
        session(connection);
    }

    /**
     * 采集当前对象身份、结构或运行状态的快照。
     */
    static UxdbCopyAdmission capture(Connection c, String schema, String table,
            List<String> fields) throws SQLException {
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]{0,62}") || table == null || !table.matches(
                "[a-z_][a-z0-9_]{0,62}")
                || fields == null || fields.isEmpty() || fields.size() > 1024 || new HashSet<>(
                fields).size() != fields.size()) {
            throw unsupported();
        }
        session(c);
        var shape = UxdbCursorMetadata.inspectCopy(c, schema, table, 5);
        if (!shape.columns().keySet().equals(new HashSet<>(fields))) {
            throw unsupported();
        }
        return new UxdbCopyAdmission(schema, table, shape);
    }

    /**
     * 检查 JDBC 返回的列布局与当前读取协议一致。
     */
    void requireJdbcLayout(Map<String, Integer> jdbc) throws SQLException {
        for (var column : shape.columns().values()) {
            if (!Objects.equals(jdbc.get(column.name()), UxdbCursorMetadata.copyJdbcType(column))) {
                throw unsupported();
            }
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
        return List.copyOf(shape.columns().keySet());
    }

    /**
     * 校验批次。
     */
    void verifyBatch(Connection c, List<String> fields) throws SQLException {
        if (c.getAutoCommit()) {
            throw unsupported();
        }
        UxdbCursorMetadata.lock(c, schema, table, true, 5);
        var current = capture(c, schema, table, fields);
        if (!shape.identity().equals(current.shape.identity()) || !shape.fingerprint()
                .equals(current.shape.fingerprint())) {
            throw unsupported();
        }
    }

    /**
     * 按目标类型约定转换当前输入值。
     */
    Object convert(String name, Object value) {
        try {
            var column = shape.columns().get(name);
            if (column == null) {
                throw unsupported();
            }
            return JdbcValueConversions.forJdbcType(UxdbCursorMetadata.exactCopy(value, column),
                    UxdbCursorMetadata.copyJdbcType(column));
        } catch (Exception invalid) {
            throw new BatchRejectedException(
                    "UXDB COPY value cannot be represented exactly by the admitted native column");
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    static SQLFeatureNotSupportedException unsupported() {
        return new SQLFeatureNotSupportedException(
                "UXDB WRITE_COPY runtime profile, session or column layout is not verified", "0A000");
    }
}
