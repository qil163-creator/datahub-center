package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DM Database Server 64 V8、数据库版本 0x7000d 上一个普通永久行表的原生标识。
 * 此实例以 CASE_SENSITIVE=1 初始化，未加引号的 ASCII 名称按大写存储。
 * DROP/CREATE 会改变对象编号；TRUNCATE 及 ADD COLUMN 等
 * 字典修改会改变 SYS.SYSOBJECTS.INFO4 的低 32 位，已测标量写入和 200KB BLOB 更新则不会。
 * INFO3 必须是此构建中观察到的普通行表标志字。视图、全局临时表、
 * HUGE 表及分区不共享该标志。V$DATABASE 没有 magic 列；仅当每条历史记录一致时，
 * 才纳入打开历史中的 magic 值。不得复用 Oracle、PostgreSQL 或 HighGo 标识。
 */
public final class DmRelationIdentity {

    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]{0,62}";

    private static final String SERVER_BANNER = "DM Database Server 64 V8";

    private static final String VERSION_BANNER = "DB Version: 0x7000d";

    private static final long ORDINARY_INFO3 = 1157425104234283008L;

    private static final String SQL = "SELECT server.BANNER, dbver.BANNER, d.NAME, "
            + "TO_CHAR(d.CREATE_TIME,'YYYY-MM-DD HH24:MI:SS'), (SELECT USER FROM DUAL), "
            + "(SELECT CASE WHEN MIN(h.PRIMARY_DB_MAGIC)=MAX(h.PRIMARY_DB_MAGIC) "
            + "AND MIN(h.CUR_DB_MAGIC)=MIN(h.PRIMARY_DB_MAGIC) "
            + "AND MAX(h.CUR_DB_MAGIC)=MAX(h.PRIMARY_DB_MAGIC) "
            + "AND COUNT(*)=COUNT(h.CUR_DB_MAGIC) AND COUNT(*)=COUNT(h.PRIMARY_DB_MAGIC) "
            + "THEN CAST(MIN(h.PRIMARY_DB_MAGIC) AS VARCHAR(40)) END FROM SYSOPENHISTORY h "
            + "WHERE h.PRIMARY_DB_MAGIC IS NOT NULL OR h.CUR_DB_MAGIC IS NOT NULL), "
            + "s.NAME, CAST(s.ID AS VARCHAR(40)), t.NAME, CAST(t.ID AS VARCHAR(40)), "
            + "CAST(BITAND(t.INFO4,4294967295) AS VARCHAR(40)) "
            + "FROM SYS.SYSOBJECTS t JOIN SYS.SYSOBJECTS s ON s.ID=t.SCHID AND s.TYPE$='SCH' "
            + "CROSS JOIN V$DATABASE d "
            + "JOIN V$VERSION server ON server.BANNER='" + SERVER_BANNER + "' "
            + "JOIN V$VERSION dbver ON dbver.BANNER='" + VERSION_BANNER + "' "
            + "WHERE s.NAME=? AND t.NAME=? AND t.TYPE$='SCHOBJ' AND t.SUBTYPE$='UTAB' AND t.PID=-1 "
            + "AND t.VALID='Y' AND t.INFO8 IS NULL AND t.INFO3=" + ORDINARY_INFO3 + " AND t.INFO4 IS NOT NULL";

    /**
     * 初始化 {@code DmRelationIdentity}。
     */
    private DmRelationIdentity() {
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Connection and relation required");
        }
        if (!reference.matches(IDENTIFIER + "(?:\\." + IDENTIFIER + ")?")) {
            throw unsupported("DM quoted or complex references are not verified");
        }
        String[] parts = reference.split("\\.", -1);
        String table = parts[parts.length - 1].toUpperCase(Locale.ROOT);
        String schema = parts.length == 2 ? parts[0].toUpperCase(Locale.ROOT) : currentSchema(connection);
        List<String> first = identity(connection, schema, table);
        if (!first.equals(identity(connection, schema, table))) {
            throw unsupported("DM native identity changed during capture");
        }
        return DigestUtil.sha256Hex(String.join("\0", first));
    }

    /** 精确物理名称，独立于认证主体；包含表字典代次。 */
    public static String physicalFingerprint(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        if (connection == null || timeout < 1) {
            throw new IllegalArgumentException("Connection and positive timeout required");
        }
        DmSqlNames.relation(schema, table);
        if (schema.length() > 128 || table.length() > 128) {
            throw unsupported("DM physical name exceeds catalog bounds");
        }
        var first = identity(connection, schema, table, timeout);
        if (!first.equals(identity(connection, schema, table, timeout))) {
            throw unsupported("DM physical identity changed during capture");
        }
        var physical = new ArrayList<>(first);
        physical.remove(5); // USER 用于确定可见性，但不属于物理关系标识。
        return DigestUtil.sha256Hex(String.join("\0", physical));
    }

    /**
     * 查询当前数据库会话使用的模式名。
     */
    private static String currentSchema(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID()) FROM DUAL")) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("DM current schema is unavailable");
                }
                String schema = rows.getString(1);
                if (schema == null || !schema.matches(IDENTIFIER) || !schema.equals(
                        schema.toUpperCase(Locale.ROOT)) || rows.next()) {
                    throw unsupported("DM current schema is not an ordinary uppercase name");
                }
                return schema;
            }
        }
    }

    /**
     * 返回身份信息。
     */
    private static List<String> identity(Connection connection, String schema, String table) throws SQLException {
        return identity(connection, schema, table, 5);
    }

    /**
     * 返回身份信息。
     */
    private static List<String> identity(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        try (var statement = connection.prepareStatement(SQL)) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("DM relation is absent or not a verified permanent row table");
                }
                var fields = new ArrayList<>(List.of("DM_8_0x7000d_ROW_V1"));
                for (int index = 1; index <= 11; index++) {
                    String value = rows.getString(index);
                    if (value == null || value.isBlank() || value.length() > 128 || value.indexOf('\0') >= 0) {
                        throw unsupported("DM native identity field is unavailable");
                    }
                    fields.add(value);
                }
                long lowVersion;
                try {
                    lowVersion = Long.parseLong(fields.get(11));
                } catch (NumberFormatException exception) {
                    throw unsupported("DM table dictionary version is not verified");
                }
                if (!SERVER_BANNER.equals(fields.get(1)) || !VERSION_BANNER.equals(fields.get(2))
                        || !fields.get(4).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
                        || !fields.get(5).matches(IDENTIFIER) || !fields.get(6).matches("[1-9][0-9]{0,9}")
                        || !schema.equals(fields.get(7)) || !fields.get(8).matches("[1-9][0-9]{0,9}")
                        || !table.equals(fields.get(9)) || !fields.get(10).matches("[1-9][0-9]{0,9}")
                        || lowVersion < 0 || lowVersion > 4294967295L || rows.next()) {
                    throw unsupported("DM version, database or resolved object is not verified");
                }
                return fields;
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
