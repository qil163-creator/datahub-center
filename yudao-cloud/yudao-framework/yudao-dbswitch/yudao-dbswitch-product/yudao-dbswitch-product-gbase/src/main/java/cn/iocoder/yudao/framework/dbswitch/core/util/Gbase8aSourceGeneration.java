package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.List;

/**
 * 显式管理员安装、隔离及旋转的 GBase 8a 数据库代际。EXPRESS 的 PRIMARY KEY 不强制
 * 唯一，因此在一次完整 LOCK TABLES 锁集合内检查实际标记数据。头和历史使用不同表，
 * 避免同一表在单事务中的多次 DML 限制。普通运行时只校验，绝不安装或修复标记。
 * 恢复、TRUNCATE、重载及克隆仍须先隔离并排空消费方，再旋转外部保留的固定信息；
 * 复制标记本身不能检测恢复。既有 v8 外部源 pin 仅在 verify 中保留兼容，目标写入
 * 必须使用 verifyCurrent 的受管 v4 pin。
 */
public final class Gbase8aSourceGeneration {

    /**
     * 初始化 {@code Gbase8aSourceGeneration}。
     */
    private Gbase8aSourceGeneration() {
    }

    private static final ManagedJdbcSourceGeneration PROTOCOL = new ManagedJdbcSourceGeneration(
            new ManagedJdbcSourceGeneration.Backend() {
                /**
                 * 返回当前操作使用的数据库产品。
                 */
                public String product() {
                    return "GBASE8A";
                }

                /**
                 * 核对连接实际产品与当前实现的产品要求。
                 */
                public void verifyProduct(Connection c) throws SQLException {
                    Gbase8aCursorMetadata.verifyProduct(c, 5);
                    if (!"8.6.2.43-R7-free.110605".equals(c.getMetaData().getDatabaseProductVersion())) {
                        throw new SQLException("GBase generation requires its verified server profile");
                    }
                }

                /**
                 * 生成模式与表组成的限定关系名。
                 */
                public String relation(String schema, String table) {
                    return Gbase8aCursorMetadata.reference(schema, markerName(table));
                }

                /**
                 * 返回当前存在性。
                 */
                public boolean exists(Connection c, String schema, String table, int timeout) throws SQLException {
                    namespace(c, schema, timeout);
                    String physical = markerName(table);
                    try (var p = c.prepareStatement(
                            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
                        p.setQueryTimeout(timeout);
                        p.setString(1, schema);
                        p.setString(2, physical);
                        try (var r = p.executeQuery()) {
                            if (!r.next()) {
                                return false;
                            }
                            if (!physical.equals(r.getString(1)) || r.next()) {
                                throw new SQLException("GBase generation installation is ambiguous");
                            }
                            return true;
                        }
                    }
                }

                /**
                 * 获取当前操作所需的数据库或资源锁。
                 */
                public void lock(Connection c, String relation, int timeout) throws SQLException {
                    throw new SQLException("GBase generation must lock both marker relations together");
                }

                /**
                 * 锁定全部。
                 */
                public void lockAll(Connection c, String head, String history, int timeout) throws SQLException {
                    execute(c, "LOCK TABLES " + head + " WRITE," + history + " WRITE", timeout);
                }

                /**
                 * 释放当前操作持有的锁。
                 */
                public void unlock(Connection c, int timeout) throws SQLException {
                    execute(c, "UNLOCK TABLES", timeout);
                }

                /**
                 * 校验源代次存储表结构与当前产品声明一致。
                 */
                public void validate(Connection c, String schema, String table, boolean head,
                        int timeout) throws SQLException {
                    namespace(c, schema, timeout);
                    try (var p = c.prepareStatement(
                            "SELECT ENGINE,TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
                        p.setQueryTimeout(timeout);
                        p.setString(1, schema);
                        p.setString(2, table);
                        try (var r = p.executeQuery()) {
                            if (!r.next() || !"EXPRESS".equalsIgnoreCase(r.getString(1)) || !"BASE TABLE".equals(
                                    r.getString(2)) || r.next()) {
                                throw new SQLException("GBase generation requires one EXPRESS base table");
                            }
                        }
                    }
                    var columns = Gbase8aCursorMetadata.columns(c, schema, table, timeout);
                    var expected = head ? List.of("SINGLETON_ID:int:false", "PROTOCOL_VERSION:int:false",
                            "INSTANCE_ID:varchar:false", "GENERATION:varchar:false", "STATE:varchar:false")
                            : List.of("GENERATION:varchar:false", "INSTANCE_ID:varchar:false",
                            "PREVIOUS_GENERATION:varchar:true");
                    var actual = columns.values().stream().map(v -> v.name() + ":" + v.dataType()
                            .toLowerCase(java.util.Locale.ROOT) + ":" + v.nullable()).toList();
                    if (!expected.equals(actual)) {
                        throw new SQLException("GBase generation columns differ");
                    }
                    for (var column : columns.values()) {
                        if (column.extra() != null && !column.extra().isBlank() || column.defaultValue() != null) {
                            throw new SQLException("GBase generation generated columns or defaults are unsupported");
                        }
                        if (column.dataType().equalsIgnoreCase("varchar") && !column.nativeType()
                                .equalsIgnoreCase(column.name().equals("STATE") ? "varchar(6)" : "varchar(36)")) {
                            throw new SQLException("GBase generation text domain differs");
                        }
                    }
                    try (var p = c.prepareStatement(
                            "SELECT 1 FROM information_schema.TRIGGERS WHERE EVENT_OBJECT_SCHEMA=? AND EVENT_OBJECT_TABLE=? LIMIT 1")) {
                        p.setQueryTimeout(timeout);
                        p.setString(1, schema);
                        p.setString(2, table);
                        try (var r = p.executeQuery()) {
                            if (r.next()) {
                                throw new SQLException("GBase generation markers cannot have triggers");
                            }
                        }
                    }
                    // EXPRESS accepts duplicate PRIMARY KEY values. Real data uniqueness is mandatory and
                    // checked again under the combined administrator/write locks, never inferred from DDL.
                    Gbase8aCursorMetadata.requireUniqueKeys(c, relation(schema, table),
                            List.of(head ? "SINGLETON_ID" : "GENERATION"), timeout);
                }
            });

    /** 显式管理员安装；不能在正常连接或读取流程中自动调用。 */
    public static SourceGenerationPin initialize(Connection connection, String database,
            int timeout) throws SQLException {
        return PROTOCOL.initialize(connection, database, timeout);
    }

    /** 管理员辅助工具：由调用方提供下一个 UUID，使重复旋转得到相同固定信息。 */
    public static SourceGenerationPin rotate(Connection connection, SourceGenerationPin expected, String generation,
            int timeout) throws SQLException {
        if (UUID.fromString(expected.instanceId()).version() != 8) {
            return PROTOCOL.rotate(connection, expected, generation, timeout);
        }
        var next = new SourceGenerationPin(expected.schema(), expected.instanceId(), generation);
        if (next.equals(expected)) {
            throw new SQLException("Rotation requires a fresh generation UUID");
        }
        if (!expected.instanceId().equals(instance(connection, expected.schema(), timeout))) {
            throw new SQLException("GBase 8a database differs from the expected pin");
        }
        return next;
    }

    /** 运行时准入：部署固定信息必须指向此连接实际读取的数据库。 */
    public static void verify(Connection connection, SourceGenerationPin pin, String database,
            int timeout) throws SQLException {
        if (pin != null && UUID.fromString(pin.instanceId()).version() != 8) {
            if (!pin.schema().equals(database)) {
                throw unsupported("Managed GBase generation names another database");
            }
            verifyCurrent(connection, pin, timeout);
            return;
        }
        if (pin == null || !pin.schema().equals(database) || !pin.instanceId()
                .equals(instance(connection, database, timeout))) {
            throw unsupported("Deployed GBase 8a generation pin does not name this database");
        }
    }

    /**
     * 校验当前资源身份与代次，阻止使用已经失效的读写状态。
     */
    public static void fence(Connection c, SourceGenerationPin pin, int timeout) throws SQLException {
        managed(pin);
        PROTOCOL.fence(c, pin, timeout);
    }

    /**
     * 重新读取当前对象事实并校验其与预期一致。
     */
    public static void verifyCurrent(Connection c, SourceGenerationPin pin, int timeout) throws SQLException {
        managed(pin);
        PROTOCOL.verifyCurrent(c, pin, timeout);
    }

    /**
     * 重新核对目标事实后删除当前恢复材料指定的对象。
     */
    public static void drop(Connection c, String schema, int timeout) throws SQLException {
        PROTOCOL.drop(c, schema, timeout);
    }

    /** Safe marker references for a caller's single combined LOCK TABLES statement. */
    public static List<String> references(SourceGenerationPin pin) {
        managed(pin);
        return List.of(Gbase8aCursorMetadata.reference(pin.schema(), markerName(PROTOCOL.marker(pin.schema()))),
                Gbase8aCursorMetadata.reference(pin.schema(), markerName(PROTOCOL.history(pin.schema()))));
    }

    /**
     * 取得源代次标记读取所需的锁。
     */
    public static String markerReadLocks(SourceGenerationPin pin) {
        return String.join(",", references(pin).stream().map(value -> value + " READ").toList());
    }

    /**
     * 检查当前源端配置包含有效的受管部署代次。
     */
    private static void managed(SourceGenerationPin pin) {
        if (pin == null || UUID.fromString(pin.instanceId()).version() != 4) {
            throw new IllegalArgumentException("A managed GBase deployment generation is required");
        }
    }

    /**
     * 在当前数据库连接上执行给定 SQL 语句。
     */
    private static void execute(Connection c, String sql, int timeout) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.execute(sql);
        }
    }

    /**
     * 返回当前源代次标记使用的对象名称。
     */
    private static String markerName(String name) {
        return name.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 检查当前数据库目录中存在指定模式。
     */
    private static void namespace(Connection c, String schema, int timeout) throws SQLException {
        SourceGenerationPin.validateSchema(schema);
        try (var p = c.prepareStatement("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?")) {
            p.setQueryTimeout(timeout);
            p.setString(1, schema);
            try (var r = p.executeQuery()) {
                if (!r.next() || !schema.equals(r.getString(1)) || r.next()) {
                    throw new SQLException("GBase generation namespace must be one exact physical database");
                }
            }
        }
    }

    /**
     * 根据给定参数创建 {@code String} 实例。
     */
    public static String instance(Connection connection, String database, int timeout) throws SQLException {
        if (connection == null || timeout < 1) {
            throw unsupported("GBase 8a generation requires a bounded connection");
        }
        SourceGenerationPin.validateSchema(database);
        String hostname = one(connection, "SELECT @@hostname", timeout);
        if (!hostname.matches("[A-Za-z0-9_$.-]{1,64}")) {
            throw unsupported("GBase 8a server identity is not verified");
        }
        var metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName(), version = metadata.getDatabaseProductVersion();
        if (!"GBase".equalsIgnoreCase(product) || version == null || version.isBlank() || version.length() > 64) {
            throw unsupported("GBase 8a server product or version is not verified");
        }
        boolean present = false;
        try (var statement = connection.prepareStatement(
                "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=? LIMIT 2")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            statement.setString(1, database);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("GBase 8a database is absent or not visible");
                }
                if (!database.equals(rows.getString(1)) || rows.next()) {
                    throw unsupported("GBase 8a database evidence is ambiguous");
                }
                present = true;
            }
        }
        if (!present) {
            throw unsupported("GBase 8a database evidence is unavailable");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.join("\0", "GBASE8A_SOURCE_INSTANCE_V1",
                    hostname, version, database).getBytes(StandardCharsets.UTF_8));
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x80);
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
            String hex = HexFormat.of().formatHex(digest, 0, 16);
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" + hex.substring(
                    16, 20) + "-" + hex.substring(20);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    /**
     * 读取查询结果中的单个标量或唯一记录。
     */
    private static String one(Connection connection, String sql, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery(sql)) {
                if (!rows.next()) {
                    throw unsupported("GBase 8a generation evidence is absent or not visible");
                }
                String value = rows.getString(1);
                if (value == null || value.isBlank() || value.length() > 64 || rows.next()) {
                    throw unsupported("GBase 8a generation evidence is unavailable");
                }
                return value;
            }
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
