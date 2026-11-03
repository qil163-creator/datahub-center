package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;

/** 原生 ClickHouse 25.8 服务器、数据库和表 UUID，仅限 Atomic 及本地普通 MergeTree。
 * 仅接受普通未加引号标识符；不接受视图、表函数、副本、远程引擎或临时遮蔽表。
 * 要求行策略元数据可观察，且表和数据库均无行策略；仅能读到行不能作为证明。
 * UUID 标识对象，而非数据快照：TRUNCATE 不会生成另一个表 UUID。
 */
public final class ClickhouseRelationIdentity {

    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]{0,62}";

    private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";

    /**
     * 初始化 {@code ClickhouseRelationIdentity}。
     */
    private ClickhouseRelationIdentity() {
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Connection and relation required");
        }
        if (!reference.matches(IDENTIFIER + "(?:\\." + IDENTIFIER + ")?")) {
            throw unsupported("ClickHouse quoted/complex relation names are not verified");
        }
        List<String> session = session(connection);
        String[] names = reference.split("\\.");
        String schema = names.length == 2 ? names[0] : session.get(1);
        String table = names[names.length - 1];
        if (schema == null || !schema.matches(IDENTIFIER)) {
            throw unsupported("ClickHouse default database is not verified");
        }
        requireUnfilteredRelation(connection, schema, table);
        List<String> object = object(connection, schema, table);
        if (!object.equals(object(connection, schema, table)) || !session.equals(session(connection))) {
            throw unsupported("ClickHouse native relation/session changed during identity capture");
        }
        requireUnfilteredRelation(connection, schema, table);
        var fields = new ArrayList<>(List.of("CLICKHOUSE_ATOMIC_MERGETREE_V1"));
        fields.addAll(session);
        fields.addAll(object);
        return DigestUtil.sha256Hex(String.join("\0", fields));
    }

    /**
     * 校验并取得未过滤关系。
     */
    private static void requireUnfilteredRelation(Connection connection, String schema,
            String table) throws SQLException {
        // system.row_policies 可能被权限过滤。未执行此检查时，空结果不能作为证据。
        // 不得为获取指纹授予权限或改变调用方角色、会话。
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("CHECK GRANT SHOW ROW POLICIES ON *.*")) {
                if (!rows.next() || rows.getInt(1) != 1 || rows.wasNull() || rows.next()) {
                    throw unsupported("ClickHouse row-policy visibility is not proven");
                }
            }
        }
        try (var statement = connection.prepareStatement("SELECT id FROM system.row_policies "
                + "WHERE database=? AND (table=? OR table='') LIMIT 1")) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(1);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw unsupported("ClickHouse relations with row policies are not verified");
                }
            }
        }
    }

    /**
     * 返回会话。
     */
    private static List<String> session(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("SELECT toString(serverUUID()), currentDatabase(), currentUser(), "
                    + "toJSONString(arraySort(currentRoles())), version()")) {
                if (!rows.next()) {
                    throw unsupported("ClickHouse session unavailable");
                }
                String server = rows.getString(1), database = rows.getString(2), user = rows.getString(3);
                String roles = rows.getString(4), version = rows.getString(5);
                if (!uuid(server) || database == null || user == null || user.isBlank() || user.indexOf('\0') >= 0
                        || roles == null || roles.length() > 16384 || version == null || !version.startsWith(
                        "25.8.") || rows.next()) {
                    throw unsupported("ClickHouse native session/version is not verified");
                }
                return List.of(server, database, user, roles, "25.8");
            }
        }
    }

    /**
     * 按当前值域约定解析或转换对象。
     */
    private static List<String> object(Connection connection, String schema, String table) throws SQLException {
        String query = "SELECT t.database, t.name, toString(t.uuid), t.engine, t.is_temporary, d.engine, toString(d.uuid) "
                + "FROM system.tables t LEFT JOIN system.databases d ON t.database=d.name "
                + "WHERE (t.database=? AND t.name=?) OR (t.is_temporary=1 AND t.name=?) LIMIT 2";
        try (var statement = connection.prepareStatement(query)) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("ClickHouse relation is absent or not visible");
                }
                String actualSchema = rows.getString(1), actualTable = rows.getString(2), tableId = rows.getString(3);
                String engine = rows.getString(4);
                boolean temporary = rows.getBoolean(5);
                String databaseEngine = rows.getString(6), databaseId = rows.getString(7);
                if (!schema.equals(actualSchema) || !table.equals(actualTable) || temporary || !"MergeTree".equals(
                        engine)
                        || !"Atomic".equals(databaseEngine) || !uuid(tableId) || !uuid(databaseId) || rows.next()) {
                    throw unsupported("Only unambiguous permanent Atomic/MergeTree relations are verified");
                }
                return List.of(schema, table, databaseId, tableId, databaseEngine, engine);
            }
        }
    }

    /**
     * 按当前值域约定解析或转换UUID 值。
     */
    private static boolean uuid(String value) {
        return value != null && !NIL_UUID.equals(value) && value.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
