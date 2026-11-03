package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.*;

/**
 * 已验证的 OceanBase CE 4.3.5.6 MySQL 租户通过 OceanBase Connector/J 2.4.12 提供的
 * 目录依据，用于类型化游标读取和幂等 UPSERT 重放。每项检查均在调用方实际连接及
 * 事务中运行；拒绝 Oracle 模式租户、其他服务器或驱动版本以及列存表。
 * 在 REPEATABLE READ 事务内，目录视图与数据按同一快照读取。
 */
public final class OceanbaseCursorMetadata {

    public static final String VERSION = "5.7.25-OceanBase_CE-v4.3.5.6";

    public static final String DRIVER = "OceanBase Connector/J", DRIVER_VERSION = "2.4.12";

    private static final int MAX_COLUMNS = 2000, MAX_TABLETS = 1024;

    /**
     * 初始化 {@code OceanbaseCursorMetadata}。
     */
    private OceanbaseCursorMetadata() {
    }

    public record Tenant(String tenantId, String serverUuid, String switchoverEpoch) {

        /**
         * 返回身份信息。
         */
        public String identity() {
            return String.join("\0", "OCEANBASE_MYSQL_TENANT_V1", VERSION, tenantId, serverUuid, switchoverEpoch);
        }
    }

    public record Column(String name, int ordinal, String columnType, String dataType, boolean nullable, Long maxChars,
                         Long maxBytes, Integer precision, Integer scale, Integer temporalScale, String charset,
                         String collation, String extra, String generated, String defaultValue) {

        /**
         * 按当前值域约定解析或转换无符号数值。
         */
        public boolean unsigned() {
            return columnType.toLowerCase(Locale.ROOT).endsWith(" unsigned") || columnType.toLowerCase(Locale.ROOT)
                    .contains(" unsigned ");
        }

        /**
         * 判断生成列。
         */
        public boolean isGenerated() {
            String flags = extra == null ? "" : extra.toUpperCase(Locale.ROOT);
            return generated != null && !generated.isEmpty() || flags.contains("VIRTUAL GENERATED") || flags.contains(
                    "STORED GENERATED");
        }

        /**
         * 返回类型。
         */
        public String type() {
            return dataType.toLowerCase(Locale.ROOT);
        }

        /** 民用时间默认值及 ON UPDATE 时钟按会话时区求值；TIMESTAMP 存储绝对时刻本身。 */
        public boolean usesSessionClock() {
            String rule = (defaultValue + " " + extra).toLowerCase(Locale.ROOT);
            return !type().equals("timestamp") && (rule.contains("current_timestamp") || rule.contains(
                    "on update") || rule.contains("now(")
                    || rule.contains("localtime") || rule.contains("curdate") || rule.contains(
                    "curtime") || rule.contains("sysdate"));
        }

        /** 完整原生声明；任何变更均使已观测该列的计划失效。 */
        public String catalog() {
            return String.join("\u0001", name, Integer.toString(ordinal), columnType, dataType,
                    Boolean.toString(nullable), String.valueOf(maxChars),
                    String.valueOf(maxBytes), String.valueOf(precision), String.valueOf(scale),
                    String.valueOf(temporalScale), String.valueOf(charset),
                    String.valueOf(collation), String.valueOf(extra), String.valueOf(generated),
                    String.valueOf(defaultValue));
        }
    }

    /** 在同一连接上观测到的物理关系、有序目录及精确有序主键。 */
    public record Shape(Tenant tenant, String schema, String table, String physical, Map<String, Column> columns,
                        List<String> primaryKey, Map<String, List<String>> uniqueIndexes,
                        Set<String> unsafeUniqueIndexes) {

        /**
         * 初始化 {@code Shape}。
         */
        public Shape {
            columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
            primaryKey = List.copyOf(primaryKey);
            uniqueIndexes = Collections.unmodifiableMap(new LinkedHashMap<>(uniqueIndexes));
            unsafeUniqueIndexes = Set.copyOf(unsafeUniqueIndexes);
        }

        /** 关系的租户、物理 tablet、每个列和每个唯一索引。 */
        public String identity() {
            var material = new StringBuilder("OCEANBASE_RELATION_V1\0").append(tenant.identity()).append('\0')
                    .append(physical);
            for (var column : columns.values()) {
                material.append('\0').append(column.catalog());
            }
            material.append('\0').append(primaryKey);
            uniqueIndexes.forEach((name, keys) -> material.append('\0').append(name).append(keys)
                    .append(unsafeUniqueIndexes.contains(name)));
            return DigestUtil.sha256Hex(material.toString());
        }
    }

    /**
     * 按OceanBase方言转义并引用标识符。
     */
    public static String quote(String name) {
        return '`' + name.replace("`", "``") + '`';
    }

    /**
     * 生成当前操作引用的限定关系名。
     */
    public static String reference(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }

    /** 实际服务器、模式、驱动、一致性、UTF-8 会话及 SQL 模式。写入器还要求严格 DML。 */
    public static void requireSession(Connection connection, int timeout, boolean writer) throws SQLException {
        var metadata = connection.getMetaData();
        if (!DRIVER.equals(metadata.getDriverName()) || !DRIVER_VERSION.equals(metadata.getDriverVersion())) {
            throw unsupported("OceanBase driver version is not verified for typed cursor/UPSERT");
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery(
                    "SELECT VERSION(),@@ob_compatibility_mode,@@ob_read_consistency,@@lower_case_table_names,@@sql_mode,"
                            + "@@character_set_client,@@character_set_connection,@@character_set_results")) {
                if (!rows.next()) {
                    throw unsupported("OceanBase session is unavailable");
                }
                if (!"MYSQL".equals(rows.getString(2))) {
                    throw unsupported(
                            "Only verified OceanBase MySQL-mode tenants are supported; Oracle mode is refused");
                }
                Set<String> modes = new HashSet<>(Arrays.asList(
                        Objects.requireNonNullElse(rows.getString(5), "").toUpperCase(Locale.ROOT).split(",")));
                if (!VERSION.equals(rows.getString(1)) || !"STRONG".equals(rows.getString(3)) || !"1".equals(
                        rows.getString(4))
                        || modes.contains("NO_BACKSLASH_ESCAPES") || modes.contains("PAD_CHAR_TO_FULL_LENGTH")
                        || writer && !modes.contains("STRICT_ALL_TABLES") && !modes.contains("STRICT_TRANS_TABLES")
                        || !"utf8mb4".equals(rows.getString(6)) || !"utf8mb4".equals(
                        rows.getString(7)) || !"utf8mb4".equals(rows.getString(8)) || rows.next()) {
                    throw unsupported(
                            "OceanBase version, read consistency, identifier case, character set or SQL mode is not verified");
                }
            }
        }
    }

    /** 即使报告自动提交，池中连接仍可能持有显式事务。 */
    public static void requireIdle(Connection connection, int timeout) throws SQLException {
        if (!"0".equals(transaction(connection, timeout))) {
            throw unsupported("OceanBase connection already has an active transaction");
        }
    }

    /**
     * 返回事务。
     */
    public static String transaction(Connection connection, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery(
                    "SELECT TRANS_ID FROM oceanbase.GV$OB_SESSION WHERE ID=CONNECTION_ID()")) {
                if (!rows.next()) {
                    throw unsupported("OceanBase session transaction is unavailable");
                }
                String id = rows.getString(1);
                if (id == null || !id.matches("0|[1-9][0-9]{0,19}") || rows.next()) {
                    throw unsupported("OceanBase session transaction is ambiguous");
                }
                return id;
            }
        }
    }

    /**
     * 返回当前执行租户。
     */
    public static Tenant tenant(Connection connection, int timeout) throws SQLException {
        String tenantId, epoch;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery(
                    "SELECT TENANT_ID,TENANT_TYPE,COMPATIBILITY_MODE,STATUS,IN_RECYCLEBIN,LOCKED,TENANT_ROLE,"
                            + "SWITCHOVER_STATUS,SWITCHOVER_EPOCH,COMPATIBLE,RESTORE_DATA_MODE FROM oceanbase.DBA_OB_TENANTS")) {
                if (!rows.next()) {
                    throw unsupported("OceanBase tenant is unavailable");
                }
                tenantId = rows.getString(1);
                epoch = rows.getString(9);
                if (!id(tenantId) || epoch == null || !epoch.matches("0|[1-9][0-9]{0,18}") || !"USER".equals(
                        rows.getString(2))
                        || !"MYSQL".equals(rows.getString(3)) || !"NORMAL".equals(rows.getString(4)) || !"NO".equals(
                        rows.getString(5))
                        || !"NO".equals(rows.getString(6)) || !"PRIMARY".equals(rows.getString(7)) || !"NORMAL".equals(
                        rows.getString(8))
                        || !"4.3.5.6".equals(rows.getString(10)) || !"NORMAL".equals(
                        rows.getString(11)) || rows.next()) {
                    throw unsupported("OceanBase tenant identity/state is not verified");
                }
            }
            try (var rows = statement.executeQuery(
                    "SELECT VALUE,SCOPE FROM oceanbase.DBA_OB_SYS_VARIABLES WHERE NAME='server_uuid'")) {
                if (!rows.next()) {
                    throw unsupported("OceanBase persisted tenant UUID is unavailable");
                }
                String uuid = rows.getString(1);
                if (uuid == null || !uuid.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") || !"GLOBAL_ONLY".equals(
                        rows.getString(2)) || rows.next()) {
                    throw unsupported("OceanBase persisted tenant UUID is ambiguous");
                }
                return new Tenant(tenantId, uuid, epoch);
            }
        }
    }

    /** 对精确关系名称的租户、行存 tablet、列和键进行单次一致观测。 */
    public static Shape inspect(Connection connection, String schema, String table, int timeout) throws SQLException {
        if (schema == null || table == null || schema.isEmpty() || table.isEmpty() || schema.indexOf(
                '\0') >= 0 || table.indexOf('\0') >= 0
                || Set.of("oceanbase", "mysql", "information_schema", "sys", "__recyclebin", "__public")
                .contains(schema.toLowerCase(Locale.ROOT))) {
            throw unsupported("OceanBase relation name is invalid or belongs to a system schema");
        }
        try (var statement = connection.prepareStatement(
                "SELECT TABLE_SCHEMA,TABLE_NAME,ENGINE,TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !schema.equals(rows.getString(1)) || !table.equals(
                        rows.getString(2)) || !"InnoDB".equals(rows.getString(3))
                        || !"BASE TABLE".equals(rows.getString(4)) || rows.next()) {
                    throw unsupported("OceanBase cursor/UPSERT requires one exactly named base table");
                }
            }
        }
        var tenant = tenant(connection, timeout);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("SHOW CREATE TABLE " + reference(schema, table))) {
                String ddl = rows.next() ? rows.getString(2) : null;
                if (ddl == null || !ddl.stripLeading().startsWith("CREATE TABLE ") || !ddl.contains(
                        ") ORGANIZATION INDEX ")
                        || ddl.contains("COLUMN GROUP") || rows.next()) {
                    throw unsupported("OceanBase views, temporary and column-store tables are not verified");
                }
            }
        }
        String physical = physical(connection, schema, table, timeout);
        var columns = new LinkedHashMap<String, Column>();
        try (var statement = connection.prepareStatement(
                "SELECT COLUMN_NAME,ORDINAL_POSITION,COLUMN_TYPE,DATA_TYPE,IS_NULLABLE,CHARACTER_MAXIMUM_LENGTH,"
                        + "CHARACTER_OCTET_LENGTH,NUMERIC_PRECISION,NUMERIC_SCALE,DATETIME_PRECISION,CHARACTER_SET_NAME,COLLATION_NAME,EXTRA,"
                        + "GENERATION_EXPRESSION,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(MAX_COLUMNS + 1);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var column = new Column(rows.getString(1), rows.getInt(2), rows.getString(3), rows.getString(4),
                            "YES".equals(rows.getString(5)),
                            rows.getObject(6, Long.class), rows.getObject(7, Long.class),
                            rows.getObject(8, Integer.class), rows.getObject(9, Integer.class),
                            rows.getObject(10, Integer.class), rows.getString(11), rows.getString(12),
                            rows.getString(13), rows.getString(14), rows.getString(15));
                    if (columns.size() >= MAX_COLUMNS || column.name() == null || column.columnType() == null || column.dataType() == null
                            || column.ordinal() != columns.size() + 1 || !Set.of("YES", "NO")
                            .contains(rows.getString(5))
                            || columns.putIfAbsent(column.name(), column) != null) {
                        throw unsupported("OceanBase column catalog is ambiguous or exceeds its bound");
                    }
                }
            }
        }
        if (columns.isEmpty()) {
            throw unsupported("OceanBase column catalog is not visible");
        }
        var primary = new ArrayList<String>();
        try (var statement = connection.prepareStatement(
                "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=? AND TABLE_NAME=?"
                        + " AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(33);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (primary.size() >= 32 || !columns.containsKey(rows.getString(1)) || primary.contains(
                            rows.getString(1))) {
                        throw unsupported("OceanBase primary key catalog is ambiguous");
                    } else {
                        primary.add(rows.getString(1));
                    }
                }
            }
        }
        Map<String, List<String>> unique = new TreeMap<>();
        Set<String> unsafe = new TreeSet<>();
        try (var statement = connection.prepareStatement(
                "SELECT INDEX_NAME,COLUMN_NAME,SEQ_IN_INDEX,SUB_PART,EXPRESSION,INDEX_TYPE FROM information_schema.STATISTICS"
                        + " WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND NON_UNIQUE=0 ORDER BY INDEX_NAME,SEQ_IN_INDEX")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(4097);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    String index = rows.getString(1), column = rows.getString(2);
                    if (++count > 4096 || index == null) {
                        throw unsupported("OceanBase unique index catalog exceeds its bound");
                    }
                    var keys = unique.computeIfAbsent(index, ignored -> new ArrayList<>());
                    if (column == null || !columns.containsKey(column) || rows.getInt(
                            3) != keys.size() + 1 || rows.getObject(4) != null
                            || rows.getString(5) != null || !"BTREE".equals(rows.getString(6)) || columns.get(column)
                            .nullable()) {
                        unsafe.add(index);
                    }
                    keys.add(column);
                }
            }
        }
        if (!primary.isEmpty() && !primary.equals(unique.get("PRIMARY"))) {
            throw unsupported("OceanBase primary key and unique index catalogs disagree");
        }
        if (!physical.equals(physical(connection, schema, table, timeout))) {
            throw unsupported("OceanBase relation changed during inspection");
        }
        return new Shape(tenant, schema, table, physical, columns, primary, unique, unsafe);
    }

    /** TABLE_ID 在 TRUNCATE 后保留，但 tablet 代际不保留；二者与分区一同绑定。 */
    private static String physical(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        var tablets = new TreeSet<String>();
        var partitions = new TreeSet<String>();
        String tableId = null;
        try (var statement = connection.prepareStatement(
                "SELECT DISTINCT TABLE_ID,TABLE_TYPE,PARTITION_NAME,SUBPARTITION_NAME,TABLET_ID,OBJECT_ID"
                        + " FROM oceanbase.DBA_OB_TABLE_LOCATIONS WHERE DATABASE_NAME=? AND TABLE_NAME=? AND INDEX_NAME IS NULL AND DATA_TABLE_ID IS NULL LIMIT 1025")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(MAX_TABLETS + 1);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1), partition = location(rows.getString(3)), subpartition = location(
                            rows.getString(4));
                    if (tablets.size() >= MAX_TABLETS || !id(id) || !"USER TABLE".equals(rows.getString(2)) || !id(
                            rows.getString(5))
                            || !id(rows.getString(6)) || tableId != null && !tableId.equals(id) || !partitions.add(
                            partition + "\0" + subpartition)) {
                        throw unsupported("OceanBase native table/tablet identity is incomplete or ambiguous");
                    }
                    tableId = id;
                    tablets.add(partition + "\0" + subpartition + "\0" + rows.getString(6) + "\0" + rows.getString(5));
                }
            }
        }
        if (tablets.isEmpty()) {
            throw unsupported("OceanBase native tablets are not visible");
        }
        var expected = new TreeSet<String>();
        try (var statement = connection.prepareStatement(
                "SELECT PARTITION_NAME,SUBPARTITION_NAME FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? LIMIT 1025")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(MAX_TABLETS + 1);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (expected.size() >= MAX_TABLETS || !expected.add(
                            Objects.requireNonNullElse(rows.getString(1), "") + "\0" + Objects.requireNonNullElse(
                                    rows.getString(2), ""))) {
                        throw unsupported("OceanBase partition catalog exceeds its bound or is ambiguous");
                    }
                }
            }
        }
        if (!expected.equals(partitions)) {
            throw unsupported("OceanBase native tablet partition coverage is incomplete");
        }
        return "OCEANBASE_ROW_TABLETS_V1\0" + schema + "\0" + table + "\0" + tableId + "\0" + String.join("\0",
                tablets);
    }

    /** 有序键依据：精确数值值域可跨产品重放，其他键仅在相同原生比较契约下重放。 */
    public static CursorKeyContract keyContract(Shape shape, List<String> keys) throws SQLException {
        var material = new StringBuilder("OCEANBASE_CURSOR_KEY_V1\0").append(VERSION);
        var domains = new ArrayList<CursorKeyContract.Domain>();
        for (String key : keys) {
            var column = shape.columns().get(key);
            if (column == null || column.nullable() || column.isGenerated()) {
                throw unsupported("OceanBase cursor key requires complete non-null ordinary columns");
            }
            domains.add(domain(column));
            material.append('\0').append(column.columnType()).append('\0').append(column.charset()).append('\0')
                    .append(column.collation());
        }
        return new CursorKeyContract(ProductTypeEnum.OCEANBASE, DigestUtil.sha256Hex(material.toString()), domains);
    }

    /**
     * 根据当前类型及元数据证据确定允许的值域。
     */
    public static CursorKeyContract.Domain domain(Column column) throws SQLException {
        return switch (column.type()) {
            case "tinyint" -> CursorKeyContract.Domain.integer(8, column.unsigned());
            case "smallint" -> CursorKeyContract.Domain.integer(16, column.unsigned());
            case "mediumint" -> CursorKeyContract.Domain.integer(24, column.unsigned());
            case "int" -> CursorKeyContract.Domain.integer(32, column.unsigned());
            case "bigint" -> CursorKeyContract.Domain.integer(64, column.unsigned());
            case "decimal" -> {
                if (column.precision() == null || column.scale() == null || column.precision() < 1 || column.scale() < 0 || column.scale() > column.precision()) {
                    throw unsupported("OceanBase decimal key domain is unavailable");
                }
                yield CursorKeyContract.Domain.decimal(column.precision(), column.scale(), column.unsigned());
            }
            case "date", "datetime", "timestamp" -> CursorKeyContract.Domain.nativeOnly();
            case "char", "varchar" -> {
                if (column.collation() == null || column.charset() == null) {
                    throw unsupported("OceanBase text key collation is unavailable");
                }
                yield CursorKeyContract.Domain.nativeOnly();
            }
            default -> throw unsupported("OceanBase cursor key type is not verified");
        };
    }

    /**
     * 返回当前位置。
     */
    private static String location(String value) {
        return value == null || "NULL".equals(value) ? "" : value;
    }

    /**
     * 返回编号。
     */
    private static boolean id(String value) {
        return value != null && value.matches("[1-9][0-9]{0,18}");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    public static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
