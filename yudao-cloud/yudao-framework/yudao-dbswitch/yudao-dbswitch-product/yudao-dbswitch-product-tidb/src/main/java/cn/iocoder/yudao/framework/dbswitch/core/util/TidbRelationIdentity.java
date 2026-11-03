package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** 原生 TiDB 8.5.6 Community/TiKV 集群及表、分区代次，不使用 MySQL INNODB_TABLES。
 * 普通公开门面观测自动提交或当前主节点读取，不改变调用方会话。
 * 独立游标 API 验证显式持有、固定 TSO 的可重复读事务。
 */
public final class TidbRelationIdentity {

    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]{0,62}";

    /**
     * 初始化 {@code TidbRelationIdentity}。
     */
    private TidbRelationIdentity() {
    }

    /**
     * 将不区分大小写的 TiDB 别名解析为目录中的原始写法；复用 fingerprint 的原生
     * 版本、会话、基表及分区校验，不改变调用方状态。
     * 两次观测用于拒绝本次读取期间发生的名称、原生代次或会话变化。
     */
    public static cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity resolveCatalogRelation(
            Connection connection, String schema, String table, int timeout) throws SQLException {
        if (connection == null || schema == null || table == null
                || !schema.matches(IDENTIFIER) || !table.matches(IDENTIFIER)) {
            throw unsupported("TiDB catalog resolution requires separate ordinary physical names");
        }
        if (timeout < 1 || timeout > 60) {
            throw new IllegalArgumentException("Bounded TiDB catalog timeout required");
        }
        String lookupSchema = schema.toLowerCase(Locale.ROOT), lookupTable = table.toLowerCase(Locale.ROOT);
        var before = session(connection, false, timeout);
        var first = observeObject(connection, lookupSchema, lookupTable, timeout);
        var second = observeObject(connection, lookupSchema, lookupTable, timeout);
        if (!first.equals(second) || !before.equals(session(connection, false, timeout))) {
            throw unsupported("TiDB catalog relation/session changed during resolution");
        }
        return first.relation();
    }

    /** 两次核对目录写法与编号；选择器不授权视图及不支持的关系名。 */
    public static List<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription> queryCatalogTableList(
            Connection connection, String schema, int maxTables, int timeout) throws SQLException {
        if (connection == null || schema == null || !schema.matches(IDENTIFIER)) {
            throw unsupported("TiDB catalog table listing requires an ordinary schema");
        }
        if (maxTables < 1 || maxTables > 10_000 || timeout < 1 || timeout > 60) {
            throw new IllegalArgumentException("Bounded TiDB catalog table listing required");
        }
        var before = session(connection, false, timeout);
        String canonicalSchema = catalogSchema(connection, schema, timeout);
        var first = catalogTables(connection, canonicalSchema, maxTables, timeout);
        var second = catalogTables(connection, canonicalSchema, maxTables, timeout);
        if (!first.equals(second) || !canonicalSchema.equals(catalogSchema(connection, schema, timeout))
                || !before.equals(session(connection, false, timeout))) {
            throw unsupported("TiDB catalog table list/session changed during observation");
        }
        var tables = new ArrayList<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription>();
        for (var entry : first) {
            var table = new cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription();
            table.setSchemaName(canonicalSchema);
            table.setTableName(entry.name());
            table.setCatalogObjectId(entry.objectId());
            table.setTableType("TABLE");
            table.setRemarks(entry.remarks());
            tables.add(table);
        }
        return List.copyOf(tables);
    }

    /**
     * 从原生目录读取模式属性。
     */
    private static String catalogSchema(Connection connection, String schema, int timeout) throws SQLException {
        try (var sql = connection.prepareStatement("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA "
                + "WHERE LOWER(SCHEMA_NAME)=? LIMIT 2")) {
            sql.setQueryTimeout(timeout);
            sql.setString(1, schema.toLowerCase(Locale.ROOT));
            try (var rows = sql.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("TiDB catalog schema is absent");
                }
                String name = rows.getString(1);
                if (name == null || !name.matches(IDENTIFIER) || !name.equalsIgnoreCase(schema) || rows.next()) {
                    throw unsupported("TiDB catalog schema is ambiguous or unsupported");
                }
                return name;
            }
        }
    }

    private record CatalogTable(String name, String objectId, String remarks) {

    }

    /**
     * 从原生目录读取表定义集合。
     */
    private static List<CatalogTable> catalogTables(Connection connection, String schema, int maxTables, int timeout)
            throws SQLException {
        try (var sql = connection.prepareStatement("SELECT TABLE_SCHEMA,TABLE_NAME,TIDB_TABLE_ID,TABLE_COMMENT "
                + "FROM information_schema.TABLES WHERE LOWER(TABLE_SCHEMA)=? AND TABLE_TYPE='BASE TABLE' "
                + "AND ENGINE='InnoDB' ORDER BY TABLE_NAME LIMIT ?")) {
            sql.setQueryTimeout(timeout);
            sql.setString(1, schema.toLowerCase(Locale.ROOT));
            sql.setInt(2, maxTables + 1);
            var tables = new ArrayList<CatalogTable>();
            var names = new java.util.HashSet<String>();
            try (var rows = sql.executeQuery()) {
                while (rows.next()) {
                    String actualSchema = rows.getString(1), name = rows.getString(2), id = rows.getString(3);
                    if (!schema.equals(actualSchema) || name == null || !name.matches(IDENTIFIER)
                            || id == null || !id.matches("[1-9][0-9]{0,19}")
                            || !names.add(name.toLowerCase(Locale.ROOT))) {
                        throw unsupported("TiDB catalog table list is ambiguous or unsupported");
                    }
                    tables.add(new CatalogTable(name, id, rows.getString(4)));
                    if (tables.size() > maxTables) {
                        throw new IllegalArgumentException("TiDB table list exceeds the configured bound");
                    }
                }
            }
            return List.copyOf(tables);
        }
    }

    /** 原生产品验证，独立于 MySQL 兼容的 JDBC 产品名称。 */
    public static void verifyProduct(Connection connection, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery("SELECT TIDB_VERSION()")) {
                if (!rows.next() || !verifiedVersion(rows.getString(1)) || rows.next()) {
                    throw unsupported("Unverified TiDB native product");
                }
            }
        }
    }

    /** 仅用于自有游标或 UPSERT 连接；公开当前读取门面保留原有契约。 */
    public static String cursorFingerprint(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.quotePhysicalIdentifier(
                cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.TIDB, schema);
        cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.quotePhysicalIdentifier(
                cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.TIDB, table);
        schema = schema.toLowerCase(Locale.ROOT);
        table = table.toLowerCase(Locale.ROOT);
        cursorTimestamp(connection, timeout, connection.getAutoCommit());
        var session = session(connection, true, timeout);
        var object = object(connection, schema, table, timeout);
        if (!object.equals(object(connection, schema, table, timeout)) || !session.equals(
                session(connection, true, timeout))) {
            throw unsupported("TiDB cursor relation/session changed during observation");
        }
        var fields = new ArrayList<>(List.of("TIDB_CURSOR_PHYSICAL_V1"));
        fields.addAll(session);
        fields.addAll(object);
        return DigestUtil.sha256Hex(String.join("\0", fields));
    }

    /** 绝不在活跃事务之上执行 START TRANSACTION，否则可能隐式提交其他调用方的写入。 */
    public static String beginOwnedSnapshot(Connection connection, int timeout) throws SQLException {
        if (connection.getAutoCommit() || !"0".equals(cursorTimestamp(connection, timeout, true))) {
            throw unsupported("TiDB snapshot requires an owned unstarted transaction");
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT");
        }
        return cursorTimestamp(connection, timeout, false);
    }

    /**
     * 校验并取得独占快照。
     */
    public static void requireOwnedSnapshot(Connection connection, String expected, int timeout) throws SQLException {
        if (connection.getAutoCommit() || !java.util.Objects.equals(expected,
                cursorTimestamp(connection, timeout, false))) {
            throw unsupported("TiDB cursor snapshot changed");
        }
    }

    /** 管理及当前读取准入，同时检测 JDBC autoCommit=true 背后的 SQL BEGIN。 */
    public static void requireIdleSession(Connection connection, int timeout) throws SQLException {
        verifyProduct(connection, timeout);
        if (!connection.getAutoCommit() || !"0".equals(cursorTimestamp(connection, timeout, true))) {
            throw unsupported("TiDB generation requires an idle autocommit connection");
        }
    }

    /**
     * 按当前游标协议编码或解析时间戳键值。
     */
    private static String cursorTimestamp(Connection connection, int timeout,
            boolean allowUnstarted) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery(
                    "SELECT @@tidb_enable_metadata_lock,@@transaction_isolation,@@tidb_disable_txn_auto_retry,"
                            + "@@tidb_current_ts,@@tidb_snapshot,@@tidb_read_staleness,@@tidb_replica_read,@@tidb_enable_external_ts_read,@@tx_read_ts")) {
                if (!rows.next()) {
                    throw unsupported("TiDB cursor session unavailable");
                }
                String timestamp = rows.getString(4);
                if (!"1".equals(rows.getString(1)) || !"REPEATABLE-READ".equals(rows.getString(2)) || !"1".equals(
                        rows.getString(3))
                        || timestamp == null || !(allowUnstarted && timestamp.equals("0") || timestamp.matches(
                        "[1-9][0-9]{0,19}"))
                        || !"".equals(rows.getString(5)) || !"0".equals(rows.getString(6)) || !"leader".equals(
                        rows.getString(7))
                        || !"0".equals(rows.getString(8)) || !"".equals(rows.getString(9)) || rows.next()) {
                    throw unsupported(
                            "TiDB cursor requires metadata locking, RR, current leader reads and disabled transaction auto-retry");
                }
                return timestamp;
            }
        }
    }

    /**
     * 读取并检查当前服务端版本属于已验证范围。
     */
    private static boolean verifiedVersion(String version) {
        return version != null && version.startsWith("Release Version: v8.5.6\n")
                && version.contains("\nEdition: Community\n") && version.endsWith("Store: tikv");
    }

    /**
     * 返回当前游标键使用的排序及相等比较协议。
     */
    public static String keyComparisonProfile(Connection connection, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery(
                    "SELECT VARIABLE_VALUE FROM mysql.tidb WHERE VARIABLE_NAME='new_collation_enabled'")) {
                if (!rows.next()) {
                    throw unsupported("TiDB collation implementation is unavailable");
                }
                String value = rows.getString(1);
                if (value == null || !(value.equalsIgnoreCase("True") || value.equalsIgnoreCase(
                        "False")) || rows.next()) {
                    throw unsupported("TiDB collation implementation is ambiguous");
                }
                return "TIDB_NEW_COLLATION=" + value.toLowerCase(Locale.ROOT);
            }
        }
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Connection and relation required");
        }
        if (!reference.matches(IDENTIFIER + "(?:\\." + IDENTIFIER + ")?")) {
            throw unsupported("TiDB complex/quoted relation names are not verified");
        }
        List<String> session = session(connection);
        String[] names = reference.split("\\.");
        String schema = (names.length == 2 ? names[0] : session.get(1)).toLowerCase(Locale.ROOT);
        String table = names[names.length - 1].toLowerCase(Locale.ROOT);
        if (!schema.matches(IDENTIFIER)) {
            throw unsupported("TiDB default schema unavailable");
        }
        List<String> object = object(connection, schema, table);
        if (!object.equals(object(connection, schema, table)) || !session.equals(session(connection))) {
            throw unsupported("TiDB relation/session changed during identity capture");
        }
        var fields = new ArrayList<>(List.of("TIDB_TIKV_V1"));
        fields.addAll(session);
        fields.addAll(object);
        return DigestUtil.sha256Hex(String.join("\0", fields));
    }

    /**
     * 返回会话。
     */
    private static List<String> session(Connection connection) throws SQLException {
        return session(connection, false, 5);
    }

    /**
     * 返回会话。
     */
    private static List<String> session(Connection connection, boolean cursorProfile, int timeout) throws SQLException {
        String database, user, role;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("SELECT TIDB_VERSION(),DATABASE(),CURRENT_USER(),CURRENT_ROLE(),"
                    + "@@lower_case_table_names,@@tidb_snapshot,@@tidb_read_staleness,@@tidb_replica_read,"
                    + "@@tidb_enable_external_ts_read,@@tx_read_ts,@@tidb_current_ts,@@autocommit")) {
                if (!rows.next()) {
                    throw unsupported("TiDB session unavailable");
                }
                String version = rows.getString(1);
                database = rows.getString(2);
                user = rows.getString(3);
                role = rows.getString(4);
                int caseMode = rows.getInt(5);
                boolean transaction = cursorProfile && !connection.getAutoCommit();
                boolean timestamp = transaction ? rows.getString(11) != null && rows.getString(11)
                        .matches("[1-9][0-9]{0,19}") : "0".equals(rows.getString(11));
                if (rows.wasNull() || caseMode != 2 || !verifiedVersion(version)
                        || user == null || user.isBlank() || user.indexOf('\0') >= 0
                        || role == null || role.length() > 16384 || role.indexOf('\0') >= 0
                        || !"".equals(rows.getString(6)) || !"0".equals(rows.getString(7))
                        || !"leader".equals(rows.getString(8)) || !"0".equals(rows.getString(9))
                        || !"".equals(rows.getString(10)) || !timestamp
                        || !(transaction ? "0" : "1").equals(rows.getString(12)) || rows.next()) {
                    throw unsupported("TiDB native version/session/current-read mode is not verified");
                }
                database = database == null ? "" : database.toLowerCase(Locale.ROOT);
            }
            // v8.5.6 暴露只读原生集群编号；不得根据端点、启动时间或版本推断标识。
            try (var rows = statement.executeQuery(
                    "SELECT VARIABLE_VALUE FROM mysql.tidb WHERE VARIABLE_NAME='cluster_id'")) {
                if (!rows.next()) {
                    throw unsupported("TiDB native cluster identity unavailable");
                }
                String cluster = rows.getString(1);
                if (cluster == null || !cluster.matches("[1-9][0-9]{0,19}") || rows.next()) {
                    throw unsupported("TiDB native cluster identity ambiguous");
                }
                return List.of(cluster, database, user, role, "8.5.6", "2", "leader",
                        cursorProfile ? "CURSOR_CURRENT_RR" : "AUTOCOMMIT_CURRENT");
            }
        }
    }

    /**
     * 按当前值域约定解析或转换对象。
     */
    private static List<String> object(Connection connection, String schema, String table) throws SQLException {
        return object(connection, schema, table, 5);
    }

    /**
     * 按当前值域约定解析或转换对象。
     */
    private static List<String> object(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        return observeObject(connection, schema, table, timeout).fingerprintFields();
    }

    private record ObservedObject(List<String> fingerprintFields,
                                  cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity relation) {

    }

    /**
     * 观测对象。
     */
    private static ObservedObject observeObject(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery(
                    "SHOW CREATE TABLE `" + schema.replace("`", "``") + "`.`" + table.replace("`", "``") + "`")) {
                if (!rows.next()) {
                    throw unsupported("TiDB relation unavailable");
                }
                String ddl = rows.getString(2);
                if (ddl == null || !ddl.stripLeading().toUpperCase(Locale.ROOT)
                        .startsWith("CREATE TABLE ") || rows.next()) {
                    throw unsupported("TiDB views and local/global temporary tables are not verified");
                }
            }
        }
        String tableId, catalogSchema, catalogTable;
        try (var statement = connection.prepareStatement(
                "SELECT TABLE_SCHEMA,TABLE_NAME,TABLE_TYPE,ENGINE,TIDB_TABLE_ID"
                        + " FROM information_schema.TABLES WHERE LOWER(TABLE_SCHEMA)=? AND LOWER(TABLE_NAME)=? LIMIT 2")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !schema.equalsIgnoreCase(rows.getString(1)) || !table.equalsIgnoreCase(
                        rows.getString(2))
                        || !"BASE TABLE".equals(rows.getString(3)) || !"InnoDB".equals(rows.getString(4))) {
                    throw unsupported("TiDB native base table identity unavailable");
                }
                catalogSchema = rows.getString(1);
                catalogTable = rows.getString(2);
                tableId = rows.getString(5);
                if (!validId(tableId) || rows.next()) {
                    throw unsupported("TiDB native table ID ambiguous");
                }
                if (!catalogSchema.matches(IDENTIFIER) || !catalogTable.matches(IDENTIFIER)) {
                    throw unsupported("TiDB catalog physical names are outside the verified subset");
                }
            }
        }
        var partitions = new TreeSet<String>();
        int count = 0;
        boolean unpartitioned = false;
        try (var statement = connection.prepareStatement(
                "SELECT TABLE_SCHEMA,TABLE_NAME,PARTITION_NAME,SUBPARTITION_NAME,TIDB_PARTITION_ID"
                        + " FROM information_schema.PARTITIONS WHERE LOWER(TABLE_SCHEMA)=? AND LOWER(TABLE_NAME)=? LIMIT 1025")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(1025);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (++count > 1024 || !schema.equalsIgnoreCase(rows.getString(1)) || !table.equalsIgnoreCase(
                            rows.getString(2))
                            || rows.getString(4) != null) {
                        throw unsupported("TiDB partition identity incomplete or exceeds its bound");
                    }
                    String name = rows.getString(3), id = rows.getString(5);
                    if (name == null && id == null && count == 1) {
                        unpartitioned = true;
                        continue;
                    }
                    if (unpartitioned || name == null || !validId(id) || !partitions.add(id)) {
                        throw unsupported("TiDB partition generation ambiguous");
                    }
                }
            }
        }
        if (count == 0) {
            throw unsupported("TiDB partition metadata not visible");
        }
        var fields = new ArrayList<>(List.of(schema, table, tableId, unpartitioned ? "UNPARTITIONED" : "PARTITIONED"));
        fields.addAll(partitions);
        return new ObservedObject(List.copyOf(fields),
                new cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity(
                        cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.TIDB,
                        catalogSchema, catalogTable, tableId));
    }

    /**
     * 校验当前标识符是否满足长度及字符限制。
     */
    private static boolean validId(String id) {
        return id != null && id.matches("[1-9][0-9]{0,18}");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
