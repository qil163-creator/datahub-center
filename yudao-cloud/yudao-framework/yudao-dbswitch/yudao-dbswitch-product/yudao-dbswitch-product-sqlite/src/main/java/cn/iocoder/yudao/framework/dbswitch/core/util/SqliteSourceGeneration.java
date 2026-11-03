package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;

import java.sql.*;
import java.util.UUID;

/** 显式的源端管理员操作；游标读取绝不创建或修复此标记。
 * 标记以 _dbswitch_source_generation 保存在所选文件内，初始化为幂等操作。
 * 原地恢复或复制前，停止全部消费者并等待结束，轮换外部保留的固定标识，
 * 再用返回的标识轮换此标记，然后启用新部署；在没有稳定文件标识的文件系统上，
 * 仅复制标记不能证明恢复的新鲜度。不得授予应用更新或删除此标记的
 * 权限；SQLite 文件级访问仍是部署信任边界。 */
public final class SqliteSourceGeneration {

    public static final String TABLE = "_dbswitch_source_generation";

    /**
     * 初始化 {@code SqliteSourceGeneration}。
     */
    private SqliteSourceGeneration() {
    }

    /**
     * 初始化当前组件的运行资源和内部状态。
     */
    public static SourceGenerationPin initialize(Connection admin, String schema) throws SQLException {
        SourceGenerationPin.validateSchema(schema);
        requireOwned(admin);
        admin.setAutoCommit(false);
        try {
            try (var s = admin.createStatement()) {
                s.setQueryTimeout(10);
                s.execute("CREATE TABLE IF NOT EXISTS " + reference(
                        schema) + "(slot INTEGER PRIMARY KEY CHECK(slot=1),instance_id TEXT NOT NULL,generation TEXT NOT NULL) WITHOUT ROWID");
            }
            SourceGenerationPin proposed = new SourceGenerationPin(schema, UUID.randomUUID().toString(),
                    UUID.randomUUID().toString());
            try (var s = admin.prepareStatement("INSERT INTO " + reference(
                    schema) + "(slot,instance_id,generation) VALUES(1,?,?) ON CONFLICT(slot) DO NOTHING")) {
                s.setQueryTimeout(10);
                s.setString(1, proposed.instanceId());
                s.setString(2, proposed.generation());
                s.executeUpdate();
            }
            var actual = read(admin, schema);
            admin.commit();
            return actual;
        } finally {
            try {
                admin.rollback();
            } finally {
                admin.setAutoCommit(true);
            }
        }
    }

    /**
     * 校验旧代次并切换当前资源的代次标记。
     */
    public static SourceGenerationPin rotate(Connection admin, SourceGenerationPin expected) throws SQLException {
        requireOwned(admin);
        admin.setAutoCommit(false);
        try {
            verify(admin, expected);
            var next = new SourceGenerationPin(expected.schema(), expected.instanceId(), UUID.randomUUID().toString());
            try (var s = admin.prepareStatement("UPDATE " + reference(
                    expected.schema()) + " SET generation=? WHERE slot=1 AND instance_id=? AND generation=?")) {
                s.setQueryTimeout(10);
                s.setString(1, next.generation());
                s.setString(2, expected.instanceId());
                s.setString(3, expected.generation());
                if (s.executeUpdate() != 1) {
                    throw new SQLException("SQLite generation rotation lost ownership");
                }
            }
            admin.commit();
            return next;
        } finally {
            try {
                admin.rollback();
            } finally {
                admin.setAutoCommit(true);
            }
        }
    }

    /**
     * 检查数据库连接、固定代次是否满足当前操作的约定。
     */
    public static void verify(Connection connection, SourceGenerationPin pin) throws SQLException {
        if (pin == null || !pin.equals(read(connection, pin.schema()))) {
            throw new SQLException("SQLite source generation is not the deployed generation");
        }
    }

    /**
     * 读取并校验 SQLite 源代次存储表及其当前状态。
     */
    private static SourceGenerationPin read(Connection connection, String schema) throws SQLException {
        var shape = SqliteCursorMetadata.inspect(connection, schema, TABLE, 10);
        if (!shape.primaryKeys().equals(java.util.List.of("slot")) || shape.columns().size() != 3
                || !shape.columns().keySet().equals(java.util.Set.of("slot", "instance_id", "generation"))) {
            throw new SQLException("SQLite generation marker structure changed");
        }
        for (var column : shape.columns().values()) {
            if (column.hidden() != 0 || !column.notNull()
                    || !column.declaredType().equalsIgnoreCase(column.name().equals("slot") ? "INTEGER" : "TEXT")) {
                throw new SQLException("SQLite generation marker columns changed");
            }
        }
        SqliteCursorMetadata.requireReplaySafe(connection, schema, TABLE, 10);
        try (var s = connection.createStatement()) {
            s.setQueryTimeout(10);
            try (var r = s.executeQuery("SELECT slot,instance_id,generation FROM " + reference(schema) + " LIMIT 2")) {
                if (!r.next() || r.getInt(1) != 1) {
                    throw new SQLException("SQLite source generation marker is absent");
                }
                var pin = new SourceGenerationPin(schema, r.getString(2), r.getString(3));
                if (r.next()) {
                    throw new SQLException("SQLite source generation marker is ambiguous");
                }
                return pin;
            }
        }
    }

    /**
     * 检查当前连接满足调用方独占使用的前置条件。
     */
    private static void requireOwned(Connection connection) throws SQLException {
        if (!connection.getAutoCommit() || !"SQLite".equals(connection.getMetaData().getDatabaseProductName())) {
            throw new SQLException("Owned SQLite administrator connection required");
        }
    }

    /**
     * 生成当前操作引用的限定关系名。
     */
    private static String reference(String schema) {
        return SqliteCursorMetadata.quote(schema) + "." + SqliteCursorMetadata.quote(TABLE);
    }
}
