package cn.iocoder.yudao.framework.dbswitch.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HexFormat;

/**
 * PostgreSQL 实际关系身份，而非业务配置中的未解析表名。使用服务器自己的标识符解析规则，
 * 包含当前库/角色/接入端点与表 OID；不记录 URL、凭据或业务数据。
 * 当前仅证明普通表，视图、分区表、外部表及启用行级安全的表明确拒绝。
 */
public final class PostgresRelationIdentity {

    private static final String STORAGE_SQL = """
            SELECT d.oid::text, c.oid::text, n.nspname, c.relname,
                   pg_catalog.pg_relation_filenode(c.oid)::text,
                   c.relkind::text, c.relpersistence::text, c.relrowsecurity,
                   c.relispartition,
                   EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhparent=c.oid OR i.inhrelid=c.oid),
                   pg_catalog.format('%I.%I', n.nspname, c.relname)
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            JOIN pg_catalog.pg_database d ON d.datname=pg_catalog.current_database()
            WHERE c.oid=pg_catalog.to_regclass(?)
            """;

    private static final String SQL = "SELECT current_database(), d.oid::bigint, current_user, "
            + "inet_server_addr()::text, inet_server_port(), n.nspname, c.relname, c.oid::bigint, "
            + "c.relkind::text, c.relrowsecurity "
            + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
            + "JOIN pg_catalog.pg_database d ON d.datname=current_database() "
            + "WHERE c.oid=pg_catalog.to_regclass(?)";

    /**
     * 初始化 {@code PostgresRelationIdentity}。
     */
    private PostgresRelationIdentity() {
    }

    /**
     * PG16 普通持久表的 OID + 当前物理文件身份。与旧关系指纹分离，不改变旧凭证字节。
     * null 表示未验证的关系类别；不是恢复/克隆代际、字段值或完整 DDL 版本证明。
     * readLock=true 时先按服务端安全引用锁定并复验，避免旧 MVCC catalog 冒充当前存储。
     */
    public static String storageFingerprint(Connection connection, String reference,
            boolean readLock) throws SQLException {
        if (connection == null || reference == null || reference.isBlank() || reference.length() > 4096) {
            throw new IllegalArgumentException("A bounded relation reference is required");
        }
        if (readLock && connection.getAutoCommit()) {
            throw new SQLException("Relation lock requires owned transaction");
        }
        Storage before = storage(connection, reference);
        if (before == null) {
            return null;
        }
        if (readLock) {
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                // qualified 来自 pg_catalog.format(%I)，从不拼接调用方的 SQL 文本。
                statement.execute("LOCK TABLE ONLY " + before.qualified() + " IN ACCESS SHARE MODE");
            }
            if (!before.equals(storage(connection, reference))) {
                throw new SQLException("Relation storage changed");
            }
        }
        return before.fingerprint();
    }

    private record Storage(String fingerprint, String qualified) {

    }

    /**
     * 返回当前存储。
     */
    private static Storage storage(Connection connection, String reference) throws SQLException {
        try (var statement = connection.prepareStatement(STORAGE_SQL)) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            statement.setString(1, reference);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Relation storage cannot be resolved");
                }
                String file = rows.getString(5);
                if (!"r".equals(rows.getString(6)) || !"p".equals(rows.getString(7))
                        || rows.getBoolean(8) || rows.getBoolean(9) || rows.getBoolean(10)
                        || file == null || !file.matches("[1-9][0-9]*")) {
                    return null;
                }
                String identity = String.join("\0", "PG_RELATION_STORAGE_V1",
                        rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), file);
                Storage result = new Storage(cn.hutool.crypto.digest.DigestUtil.sha256Hex(identity),
                        rows.getString(11));
                if (result.qualified() == null || rows.next()) {
                    throw new SQLException("Relation storage is ambiguous");
                }
                return result;
            }
        }
    }

    /**
     * 拒绝当前实现未支持的 {@code fingerprint} 操作。
     */
    public static String fingerprint(Connection connection, String relationReference) throws SQLException {
        if (connection == null || relationReference == null || relationReference.isBlank()) {
            throw new IllegalArgumentException("PostgreSQL connection and relation reference are required");
        }
        try (PreparedStatement statement = connection.prepareStatement(SQL)) {
            statement.setQueryTimeout(5);
            statement.setString(1, relationReference);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("PostgreSQL relation cannot be resolved");
                }
                String kind = rows.getString(9);
                if (!"r".equals(kind) || rows.getBoolean(10) || rows.getString(4) == null) {
                    throw new SQLFeatureNotSupportedException(
                            "Relation kind, RLS or connection route is not verifiable");
                }
                String identity = String.join("\u0000", rows.getString(1), Long.toString(rows.getLong(2)),
                        rows.getString(3), rows.getString(4), Integer.toString(rows.getInt(5)),
                        rows.getString(6), rows.getString(7), Long.toString(rows.getLong(8)), kind);
                if (rows.next()) {
                    throw new SQLException("PostgreSQL relation identity is ambiguous");
                }
                try {
                    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(identity.getBytes(StandardCharsets.UTF_8)));
                } catch (NoSuchAlgorithmException impossible) {
                    throw new IllegalStateException("SHA-256 is unavailable", impossible);
                }
            }
        }
    }
}
