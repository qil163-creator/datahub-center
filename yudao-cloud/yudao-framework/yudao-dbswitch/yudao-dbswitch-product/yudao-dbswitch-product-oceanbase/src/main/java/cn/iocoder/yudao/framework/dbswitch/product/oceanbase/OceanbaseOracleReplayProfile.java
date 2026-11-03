package cn.iocoder.yudao.framework.dbswitch.product.oceanbase;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleCursorMetadata.Column;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleCursorMetadata.Shape;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleReplayProfile;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** 原始 OceanBase 4.2.1.7 Oracle 租户，Connector/J 2.4.12。不继承 Oracle PDB 或驱动的验证依据。
 * 普通非分区表，以及精确 NUMBER、文本、RAW 和民用时间值，使用共享
 * Oracle 语法执行器。拒绝默认值（包括标识生成的 SEQUENCE.NEXTVAL）、生成或隐藏列、
 * 策略及未验证的存储。以调用方身份访问目录，绝不提权。
 * ROW SHARE 保护 SERIALIZABLE 读取器；EXCLUSIVE 使 MERGE 批次串行执行，包括竞争插入。 */
public final class OceanbaseOracleReplayProfile implements OracleReplayProfile {

    public static final OceanbaseOracleReplayProfile INSTANCE = new OceanbaseOracleReplayProfile();

    public static final String VERSION = "4.2.1.7";

    private static final int MAX_COLUMNS = 1000;

    /**
     * 初始化 {@code OceanbaseOracleReplayProfile}。
     */
    private OceanbaseOracleReplayProfile() {
    }

    /**
     * 返回产品名称。
     */
    @Override
    public String productName() {
        return "OceanBase";
    }

    /**
     * 校验并取得会话。
     */
    public static void requireSession(Connection connection, int timeout) throws SQLException {
        if (connection == null || timeout < 1) {
            throw unsupported("Owned connection and positive timeout required");
        }
        var m = connection.getMetaData();
        if (!"OceanBase".equals(m.getDatabaseProductName()) || !VERSION.equals(m.getDatabaseProductVersion())
                || !"OceanBase Connector/J".equals(m.getDriverName()) || !"2.4.12".equals(m.getDriverVersion())) {
            throw unsupported("OceanBase Oracle replay server/driver profile is not verified");
        }
        if (!"ORACLE".equals(variable(connection, "ob_compatibility_mode", timeout))
                || !"STRONG".equals(variable(connection, "ob_read_consistency", timeout))) {
            throw unsupported("OceanBase Oracle replay requires an Oracle tenant and strong reads");
        }
        try (var s = connection.createStatement()) {
            s.setQueryTimeout(timeout);
            s.setMaxRows(2);
            try (var r = s.executeQuery("SELECT BANNER FROM V$VERSION")) {
                if (!r.next() || !Objects.requireNonNullElse(r.getString(1), "").startsWith(
                        "OceanBase 4.2.1.7 (r107020012024061213-2a278db71acb476122dd0467fd8a30c047728e27)") || r.next()) {
                    throw unsupported("OceanBase Oracle replay build is not verified");
                }
            }
        }
        OracleCursorMetadata.requireBinaryComparison(connection, timeout);
    }

    /**
     * 调用方仅提供上方两个固定服务器变量名。
     */
    private static String variable(Connection c, String name, int timeout) throws SQLException {
        // 调用方仅提供上方两个固定服务器变量名。
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.setMaxRows(2);
            try (var r = s.executeQuery("SHOW VARIABLES LIKE '" + name + "'")) {
                if (!r.next() || !name.equals(r.getString(1))) {
                    throw unsupported("OceanBase session variable is unavailable");
                }
                String value = r.getString(2);
                if (value == null || r.next()) {
                    throw unsupported("OceanBase session variable is ambiguous");
                }
                return value;
            }
        }
    }

    /**
     * 准备会话。
     */
    @Override
    public void prepareSession(Connection c, int timeout, boolean writer) throws SQLException {
        requireSession(c, timeout);
        if (writer && c.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw unsupported("OceanBase MERGE requires READ COMMITTED before acquiring its serializing table lock");
        }
        if (!"0".equals(transaction(c, timeout))) {
            throw unsupported("OceanBase connection already has an active transaction");
        }
    }

    /**
     * 获取当前操作所需的数据库或资源锁。
     */
    @Override
    public void lock(Connection c, String schema, String table, int timeout, boolean writer) throws SQLException {
        if (c.getAutoCommit()) {
            throw unsupported("OceanBase table lock requires an owned transaction");
        }
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.execute("LOCK TABLE " + OracleCursorMetadata.relation(schema,
                    table) + (writer ? " IN EXCLUSIVE MODE" : " IN ROW SHARE MODE"));
        }
    }

    /**
     * 返回事务。
     */
    private static String transaction(Connection c, int timeout) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.setMaxRows(2);
            try (var r = s.executeQuery("SELECT TRANS_ID FROM GV$OB_SESSION WHERE ID=SYS_CONTEXT('USERENV','SID')")) {
                if (!r.next()) {
                    throw unsupported("OceanBase transaction identity is unavailable");
                }
                String id = r.getString(1);
                if (id == null || !id.matches("0|[1-9][0-9]{0,19}") || r.next()) {
                    throw unsupported("OceanBase transaction identity is ambiguous");
                }
                return id;
            }
        }
    }

    /**
     * 要求当前读取快照事务仍处于活动状态，并返回其事务身份。
     */
    @Override
    public String snapshotIdentity(Connection c, int timeout) throws SQLException {
        String tx = transaction(c, timeout);
        if ("0".equals(tx)) {
            throw unsupported("OceanBase snapshot transaction is not active");
        }
        return tx;
    }

    private record Tenant(String id, String created, String epoch) {

        /**
         * 返回身份信息。
         */
        String identity() {
            return "OCEANBASE_ORACLE_TENANT_V1\0" + VERSION + '\0' + id + '\0' + created + '\0' + epoch;
        }

        /**
         * 根据给定参数创建 {@code String} 实例。
         */
        String instance() {
            return UUID.nameUUIDFromBytes(identity().getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    /**
     * 返回当前执行租户。
     */
    private static Tenant tenant(Connection c, int timeout) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.setMaxRows(2);
            try (var r = s.executeQuery(
                    "SELECT TENANT_ID,TO_CHAR(SYS_EXTRACT_UTC(CREATE_TIME),'YYYY-MM-DD HH24:MI:SS.FF6'),"
                            + "SWITCHOVER_EPOCH,TENANT_TYPE,COMPATIBILITY_MODE,STATUS,IN_RECYCLEBIN,LOCKED,TENANT_ROLE,SWITCHOVER_STATUS,COMPATIBLE FROM DBA_OB_TENANTS")) {
                if (!r.next()) {
                    throw unsupported("OceanBase tenant identity is unavailable");
                }
                var tenant = new Tenant(r.getString(1), r.getString(2), r.getString(3));
                if (!id(tenant.id()) || tenant.created() == null || !tenant.created()
                        .matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}")
                        || tenant.epoch() == null || !tenant.epoch().matches("0|[1-9][0-9]{0,18}")
                        || !"USER".equals(r.getString(4)) || !"ORACLE".equals(r.getString(5)) || !"NORMAL".equals(
                        r.getString(6))
                        || !"NO".equals(r.getString(7)) || !"NO".equals(r.getString(8)) || !"PRIMARY".equals(
                        r.getString(9))
                        || !"NORMAL".equals(r.getString(10)) || !VERSION.equals(r.getString(11)) || r.next()) {
                    throw unsupported("OceanBase Oracle tenant identity/state is not verified");
                }
                return tenant;
            }
        }
    }

    /** 只读初始化。恢复或克隆保留租户元数据时，仍需部署旋转和隔离。 */
    public static String generationInstance(Connection c, int timeout) throws SQLException {
        if (c == null || timeout < 1 || timeout > 120 || !c.getAutoCommit()) {
            throw unsupported("Generation initialization requires an idle owned connection");
        }
        INSTANCE.prepareSession(c, timeout, false);
        return tenant(c, timeout).instance();
    }

    /**
     * 检查当前资源代次是否与预期代次一致。
     */
    @Override
    public void verifyGeneration(Connection c, SourceGenerationPin pin, int timeout) throws SQLException {
        if (pin == null || !tenant(c, timeout).instance().equals(pin.instanceId())) {
            throw unsupported("Deployed OceanBase generation pin does not name this Oracle tenant");
        }
    }

    /**
     * 根据当前输入构建并返回 {@code Shape}。
     */
    @Override
    public Shape inspect(Connection c, String schema, String table, int timeout) throws SQLException {
        OracleCursorMetadata.relation(schema, table);
        if (Set.of("SYS", "SYSTEM", "PUBLIC").contains(schema.toUpperCase(Locale.ROOT))) {
            throw unsupported("OceanBase replay does not admit system schemas");
        }
        requireSession(c, timeout);
        Tenant tenant = tenant(c, timeout);
        String object, tablet;
        try (var s = c.prepareStatement(
                "SELECT o.OBJECT_ID,o.DATA_OBJECT_ID FROM ALL_OBJECTS o JOIN ALL_TABLES t ON t.OWNER=o.OWNER AND t.TABLE_NAME=o.OBJECT_NAME"
                        + " WHERE o.OWNER=? AND o.OBJECT_NAME=? AND o.OBJECT_TYPE='TABLE' AND o.SUBOBJECT_NAME IS NULL"
                        + " AND o.GENERATED='N' AND o.SECONDARY='N' AND t.TEMPORARY='N' AND t.PARTITIONED='NO' AND t.NESTED='NO'"
                        + " AND t.IOT_TYPE IS NULL AND t.CLUSTER_NAME IS NULL AND t.EXTERNAL='NO'")) {
            target(s, schema, table, timeout, 2);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    throw unsupported("OceanBase replay requires an ordinary nonpartitioned table");
                }
                object = r.getString(1);
                tablet = r.getString(2);
                if (!id(object) || !id(tablet) || r.next()) {
                    throw unsupported("OceanBase physical table identity is ambiguous");
                }
            }
        }
        try (var s = c.prepareStatement(
                "SELECT DISTINCT TABLE_ID,TABLET_ID,OBJECT_ID FROM DBA_OB_TABLE_LOCATIONS WHERE DATABASE_NAME=? AND TABLE_NAME=?"
                        + " AND TABLE_TYPE='USER TABLE' AND INDEX_NAME IS NULL AND DATA_TABLE_ID IS NULL")) {
            target(s, schema, table, timeout, 2);
            try (var r = s.executeQuery()) {
                if (!r.next() || !object.equals(r.getString(1)) || !tablet.equals(r.getString(2)) || !object.equals(
                        r.getString(3)) || r.next()) {
                    throw unsupported("OceanBase native tablet and object identities disagree");
                }
            }
        }
        var columns = new LinkedHashMap<String, Column>();
        int ordinal = 0;
        try (var s = c.prepareStatement("SELECT COLUMN_NAME,COLUMN_ID,DATA_TYPE,DATA_PRECISION,DATA_SCALE,"
                + "CASE WHEN CHAR_USED='C' THEN CHAR_LENGTH ELSE DATA_LENGTH END,CHAR_USED,NULLABLE,DATA_DEFAULT,VIRTUAL_COLUMN,IDENTITY_COLUMN,HIDDEN_COLUMN,"
                + "NVL(COLLATION,'USING_NLS_COMP'),CHARACTER_SET_NAME,DEFAULT_ON_NULL FROM ALL_TAB_COLS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID")) {
            target(s, schema, table, timeout, MAX_COLUMNS + 1);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    String type = r.getString(3);
                    if (type != null && type.matches("TIMESTAMP\\([0-9]\\)")) {
                        type = "TIMESTAMP";
                    }
                    var col = new Column(r.getString(1), r.getInt(2), type, r.getObject(4, Integer.class),
                            r.getObject(5, Integer.class),
                            r.getObject(6, Long.class), r.getString(7), "Y".equals(r.getString(8)), r.getString(9),
                            r.getString(10),
                            r.getString(11), r.getString(12), r.getString(13), r.getString(14), r.getString(15));
                    // 此构建即使对标识生成列也保留 IDENTITY_COLUMN 为 NULL，因此拒绝其 SEQUENCE.NEXTVAL 默认值。
                    if (columns.size() >= MAX_COLUMNS || col.name() == null || col.dataType() == null || col.ordinal() <= ordinal
                            || !Set.of("N", "Y").contains(r.getString(8)) || !"NO".equals(
                            col.virtualColumn()) || !"NO".equals(col.hiddenColumn())
                            || col.dataDefault() != null || col.identity() || "YES".equals(
                            col.defaultOnNull()) || columns.putIfAbsent(col.name(), col) != null) {
                        throw unsupported(
                                "OceanBase replay column is ambiguous, generated, defaulted or exceeds its bound");
                    }
                    ordinal = col.ordinal();
                }
            }
        }
        if (columns.isEmpty()) {
            throw unsupported("OceanBase column catalog is not visible");
        }
        var primary = new ArrayList<String>();
        var unique = new TreeMap<String, List<String>>();
        var unsafe = new TreeSet<String>();
        try (var s = c.prepareStatement(
                "SELECT c.CONSTRAINT_NAME,c.CONSTRAINT_TYPE,cc.COLUMN_NAME,cc.POSITION,c.STATUS,c.DEFERRABLE,c.VALIDATED,"
                        + "i.INDEX_TYPE,i.STATUS,i.UNIQUENESS FROM ALL_CONSTRAINTS c JOIN ALL_CONS_COLUMNS cc ON cc.OWNER=c.OWNER AND cc.CONSTRAINT_NAME=c.CONSTRAINT_NAME"
                        + " JOIN ALL_INDEXES i ON i.OWNER=c.INDEX_OWNER AND i.INDEX_NAME=c.INDEX_NAME WHERE c.OWNER=? AND c.TABLE_NAME=?"
                        + " AND c.CONSTRAINT_TYPE IN ('P','U') ORDER BY c.CONSTRAINT_NAME,cc.POSITION")) {
            target(s, schema, table, timeout, 4097);
            int count = 0;
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    String constraint = r.getString(1), name = r.getString(3);
                    if (++count > 4096 || constraint == null || name == null) {
                        throw unsupported("OceanBase key catalog exceeds its bound");
                    }
                    var keys = unique.computeIfAbsent(constraint, ignored -> new ArrayList<>());
                    var col = columns.get(name);
                    if (col == null || col.nullable() || r.getInt(4) != keys.size() + 1 || !"ENABLED".equals(
                            r.getString(5))
                            || !"NOT DEFERRABLE".equals(r.getString(6)) || !"VALIDATED".equals(r.getString(7))
                            || !"NORMAL".equals(r.getString(8)) || !"VALID".equals(r.getString(9)) || !"UNIQUE".equals(
                            r.getString(10))) {
                        unsafe.add(constraint);
                    }
                    keys.add(name);
                    if ("P".equals(r.getString(2))) {
                        primary.add(name);
                    }
                }
            }
        }
        if (primary.isEmpty() || primary.size() > 32 || new HashSet<>(primary).size() != primary.size()) {
            throw unsupported("OceanBase replay requires an exact ordered primary key");
        }
        try (var s = c.prepareStatement("SELECT 1 FROM ALL_POLICIES WHERE OBJECT_OWNER=? AND OBJECT_NAME=?")) {
            target(s, schema, table, timeout, 1);
            try (var r = s.executeQuery()) {
                if (r.next()) {
                    throw unsupported("OceanBase policies have unverified replay visibility");
                }
            }
        }
        return new Shape(schema, table, "OceanBase Oracle " + VERSION, tenant.id(), tenant.created(), tenant.epoch(),
                object, tablet, columns, primary, unique, unsafe);
    }

    /**
     * 返回身份信息。
     */
    @Override
    public String identity(Shape shape) {
        var material = new StringBuilder("OCEANBASE_ORACLE_TABLET_V1\0").append(shape.banner()).append('\0')
                .append(shape.conName())
                .append('\0').append(shape.conId()).append('\0').append(shape.conDbid()).append('\0')
                .append(shape.schema()).append('\0').append(shape.table())
                .append('\0').append(shape.objectId()).append('\0').append(shape.dataObjectId());
        shape.columns().values().forEach(c -> material.append('\0').append(c.catalog()));
        return DigestUtil.sha256Hex(
                material.append('\0').append(shape.primaryKey()).append('\0').append(shape.uniqueIndexes()).toString());
    }

    /**
     * 返回键集合。
     */
    @Override
    public CursorKeyContract keys(Shape shape, List<String> keys, String comparison) throws SQLException {
        var exact = OracleCursorMetadata.keyContract(shape, keys, comparison);
        return new CursorKeyContract(ProductTypeEnum.OCEANBASE,
                DigestUtil.sha256Hex("OCEANBASE_ORACLE_KEYS_V1\0" + exact.nativeFingerprint()), exact.keys());
    }

    /**
     * 根据原生列类型生成重放载荷的参数转换表达式。
     */
    @Override
    public String payloadParameter(Column column) {
        return switch (column.dataType()) {
            case "DATE" -> "TO_DATE(?,'YYYY-MM-DD HH24:MI:SS')";
            case "TIMESTAMP" ->
                    "TO_TIMESTAMP(?,'YYYY-MM-DD HH24:MI:SS" + (column.scale() == 0 ? "" : ".FF" + column.scale()) + "')";
            default -> "?";
        };
    }

    /**
     * 根据重放列类型将精确载荷转换为 JDBC 绑定值。
     */
    @Override
    public Object payloadParameterValue(Column column, Object exact) throws SQLException {
        return exact != null && Set.of("DATE", "TIMESTAMP").contains(column.dataType())
                ? OracleCursorMetadata.temporalText(column, (java.time.LocalDateTime) exact) : exact;
    }

    /**
     * 确定当前重放载荷使用的 JDBC 参数绑定类型。
     */
    @Override
    public int payloadBindType(Column column) throws SQLException {
        return Set.of("DATE", "TIMESTAMP")
                .contains(column.dataType()) ? Types.VARCHAR : OracleReplayProfile.super.payloadBindType(column);
    }

    /**
     * 按当前产品的精确值域读取或转换字段值。
     */
    @Override
    public Object exactValue(Column column, Object value, boolean key) throws SQLException {
        Object exact = OracleReplayProfile.super.exactValue(column, value, key);
        if (exact instanceof java.math.BigDecimal number && column.precision() == null && number.stripTrailingZeros()
                .precision() > 38) {
            throw unsupported("OceanBase unbounded NUMBER replay admits at most 38 significant digits");
        }
        if (exact instanceof String text && Set.of("CHAR", "NCHAR").contains(column.dataType())) {
            int length = "C".equals(column.charUsed()) || "NCHAR".equals(column.dataType())
                    ? text.codePointCount(0, text.length()) : text.getBytes(StandardCharsets.UTF_8).length;
            if (column.charLength() == null || length != column.charLength()) {
                throw unsupported("OceanBase fixed-width text replay cannot add padding");
            }
        }
        return exact;
    }

    /**
     * 返回目标端。
     */
    private static void target(PreparedStatement s, String schema, String table, int timeout,
            int max) throws SQLException {
        s.setQueryTimeout(timeout);
        s.setMaxRows(max);
        s.setString(1, schema);
        s.setString(2, table);
    }

    /**
     * 返回编号。
     */
    private static boolean id(String value) {
        return value != null && value.matches("[1-9][0-9]{0,19}");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
