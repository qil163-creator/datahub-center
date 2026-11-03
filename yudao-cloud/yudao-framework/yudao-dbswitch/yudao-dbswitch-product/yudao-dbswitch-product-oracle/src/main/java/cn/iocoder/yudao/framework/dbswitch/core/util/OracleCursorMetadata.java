package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 已验证 Oracle AI Database 26ai Free Release 23.26.3.0.0
 * PDB、ojdbc11 23.26.3.0.0 上，强类型游标读取与原生 MERGE UPSERT 的目录凭据。字典读取采用 SYS.ALL_* 及
 * SYS.STANDARD.SYS_CONTEXT；运行时读取器绝不要求 DBA_* 或 V$*。实际会话将文本排序
 * 固定为 BINARY/BINARY，使键集比较与 ORDER BY 共享同一比较
 * 配置，持久化文本游标值仅在相同配置下重放。SERIALIZABLE 在首次读取时建立
 * 快照，读取器另持 ROW SHARE 锁，阻止快照期间的 TRUNCATE 及结构
 * DDL，同时允许并发 DML。Oracle DATE 携带秒精度的本地
 * 时间，并通过 TIMESTAMP 承载类型传输；TIMESTAMP(n) 保留声明的小数精度。NUMBER 键采用
 * 精确小数值域。不存在行值比较，键集边界以等价 OR 展开表示。
 */
public final class OracleCursorMetadata {

    public static final String BANNER_PREFIX = "Oracle AI Database 26ai Free Release 23.26.3.0.0";

    public static final String VERSION_MARK = "Version 23.26.3.0.0";

    public static final String DRIVER = "Oracle JDBC driver", DRIVER_VERSION = "23.26.3.0.0";

    private static final int MAX_COLUMNS = 1000;

    /**
     * 初始化 {@code OracleCursorMetadata}。
     */
    private OracleCursorMetadata() {
    }

    public record Column(String name, int ordinal, String dataType, Integer precision, Integer scale, Long charLength,
                         String charUsed, boolean nullable, String dataDefault, String virtualColumn,
                         String identityColumn,
                         String hiddenColumn, String collation, String characterSet, String defaultOnNull) {

        /**
         * 返回当前虚拟。
         */
        public boolean virtual() {
            return "YES".equals(virtualColumn);
        }

        /**
         * 返回身份信息。
         */
        public boolean identity() {
            return "YES".equals(identityColumn);
        }

        /**
         * 返回当前隐藏。
         */
        public boolean hidden() {
            return "YES".equals(hiddenColumn);
        }

        /**
         * 生成游标键分量所需的 SQL 类型声明。
         */
        public String keySqlType() {
            return switch (dataType) {
                case "NUMBER" -> "NUMBER" + (precision == null ? "" : "(" + precision + "," + scale + ")");
                case "VARCHAR2", "CHAR" ->
                        dataType + "(" + charLength + ("C".equals(charUsed) ? " CHAR" : " BYTE") + ")";
                case "NVARCHAR2", "NCHAR" -> dataType + "(" + charLength + ")";
                case "TIMESTAMP" -> "TIMESTAMP(" + scale + ")";
                default -> dataType;
            };
        }

        /**
         * 返回目录。
         */
        public String catalog() {
            return String.join("\u0001", name, Integer.toString(ordinal), dataType, String.valueOf(precision),
                    String.valueOf(scale),
                    String.valueOf(charLength), String.valueOf(charUsed), Boolean.toString(nullable),
                    String.valueOf(dataDefault),
                    String.valueOf(virtualColumn), String.valueOf(identityColumn), String.valueOf(hiddenColumn),
                    String.valueOf(collation), String.valueOf(characterSet), String.valueOf(defaultOnNull));
        }
    }

    /** 经过验证的堆表关系，以及其有序列目录、主键及唯一索引。 */
    public record Shape(String schema, String table, String banner, String conName, String conId, String conDbid,
                        String objectId, String dataObjectId, Map<String, Column> columns,
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

        /**
         * 返回身份信息。
         */
        public String identity() {
            return "ORACLE_23_26_3_0_0_CURSOR_HEAP_V1\0" + banner + '\0' + conName + '\0' + conId + '\0' + conDbid + '\0'
                    + schema + '\0' + table + '\0' + objectId + '\0' + dataObjectId;
        }
    }

    /**
     * 按Oracle方言转义并引用标识符。
     */
    public static String quote(String name) {
        return OracleSqlNames.quote(name);
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    public static String relation(String schema, String table) {
        return OracleSqlNames.relation(schema, table);
    }

    /** 当前会话上的实际服务端、驱动、版本横幅、BINARY 比较配置及 UTF-8 数据库字符集。 */
    public static void requireSession(Connection connection, int timeout, boolean writer) throws SQLException {
        if (connection == null || timeout < 1) {
            throw unsupported("Oracle replay requires an owned connection and positive timeout");
        }
        var metadata = connection.getMetaData();
        if (!DRIVER.equals(metadata.getDriverName()) || !DRIVER_VERSION.equals(metadata.getDriverVersion())
                || !"Oracle".equals(metadata.getDatabaseProductName()) || metadata.getDatabaseMajorVersion() != 23) {
            throw unsupported("Oracle driver or server version is not verified for typed cursor/UPSERT");
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery(
                    "SELECT (SELECT CASE WHEN COUNT(DISTINCT banner_full) = 1 THEN MIN(banner_full) END "
                            + "FROM sys.v_$version WHERE banner_full LIKE 'Oracle AI Database 26ai Free Release 23.26.3.0.0%' "
                            + "AND INSTR(banner_full, 'Version 23.26.3.0.0') > 0) FROM dual")) {
                if (!rows.next()) {
                    throw unsupported("Oracle server banner is unavailable");
                }
                String banner = rows.getString(1);
                if (banner == null || !banner.startsWith(BANNER_PREFIX) || !banner.contains(
                        VERSION_MARK) || banner.length() > 1024
                        || banner.indexOf('\0') >= 0 || rows.next()) {
                    throw unsupported("Oracle server banner is not the verified Free 23.26.3 release");
                }
            }
        }
        requireBinaryComparison(connection, timeout);
    }

    /** 只有 BINARY/BINARY 比较配置已证明键集边界与 ORDER BY 一致。 */
    public static String requireBinaryComparison(Connection connection, int timeout) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT parameter, value FROM nls_session_parameters "
                + "WHERE parameter IN ('NLS_COMP','NLS_SORT') ORDER BY parameter")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(3);
            try (var rows = statement.executeQuery()) {
                String comp = null, sort = null;
                while (rows.next()) {
                    switch (rows.getString(1)) {
                        case "NLS_COMP" -> comp = rows.getString(2);
                        case "NLS_SORT" -> sort = rows.getString(2);
                        default -> throw unsupported("Oracle NLS session parameter set is unexpected");
                    }
                }
                if (!"BINARY".equals(comp) || !"BINARY".equals(sort)) {
                    throw unsupported("Oracle cursor/UPSERT requires the pinned BINARY comparison profile");
                }
            }
        }
        try (var statement = connection.prepareStatement("SELECT parameter, value FROM nls_database_parameters "
                + "WHERE parameter IN ('NLS_CHARACTERSET','NLS_NCHAR_CHARACTERSET') ORDER BY parameter")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(3);
            try (var rows = statement.executeQuery()) {
                String charset = null, ncharset = null;
                while (rows.next()) {
                    switch (rows.getString(1)) {
                        case "NLS_CHARACTERSET" -> charset = rows.getString(2);
                        case "NLS_NCHAR_CHARACTERSET" -> ncharset = rows.getString(2);
                        default -> throw unsupported("Oracle NLS database parameter set is unexpected");
                    }
                }
                if (!"AL32UTF8".equals(charset) || !"AL16UTF16".equals(ncharset)) {
                    throw unsupported("Oracle cursor/UPSERT requires the UTF-8 database character set");
                }
                return "BINARY:BINARY:AL32UTF8:AL16UTF16";
            }
        }
    }

    /** 固定会话比较配置；后续准入仅接受这些精确值。 */
    public static void pinSession(Connection connection, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.execute("ALTER SESSION SET NLS_COMP='BINARY' NLS_SORT='BINARY'");
        }
    }

    /** ROW SHARE 阻止快照期间的 TRUNCATE 及结构 DDL，同时允许并发 DML。 */
    public static void lock(Connection connection, String schema, String table, int timeout) throws SQLException {
        if (connection.getAutoCommit()) {
            throw new SQLException("Oracle relation lock requires an owned transaction");
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.execute("LOCK TABLE " + relation(schema, table) + " IN ROW SHARE MODE");
        }
    }

    /** 对精确关系名称一致观测 PDB 标识、堆表凭据、列与键。 */
    public static Shape inspect(Connection connection, String schema, String table, int timeout) throws SQLException {
        if (schema == null || table == null || schema.isBlank() || table.isBlank() || schema.indexOf(
                '\0') >= 0 || table.indexOf('\0') >= 0
                || schema.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128 || table.getBytes(
                java.nio.charset.StandardCharsets.UTF_8).length > 128) {
            throw unsupported("Oracle relation name is invalid");
        }
        requireSession(connection, timeout, false);
        var owner = schema.toUpperCase(Locale.ROOT);
        var name = table.toUpperCase(Locale.ROOT);
        String banner, conName, conId, conDbid, objectId, dataObjectId;
        try (var statement = connection.prepareStatement(
                "SELECT (SELECT CASE WHEN COUNT(DISTINCT banner_full) = 1 THEN MIN(banner_full) END "
                        + "FROM sys.v_$version WHERE banner_full LIKE 'Oracle AI Database 26ai Free Release 23.26.3.0.0%' "
                        + "AND INSTR(banner_full, 'Version 23.26.3.0.0') > 0),"
                        + "SYS.STANDARD.SYS_CONTEXT('USERENV','CON_NAME'),SYS.STANDARD.SYS_CONTEXT('USERENV','CON_ID'),"
                        + "SYS.STANDARD.SYS_CONTEXT('USERENV','CON_DBID'),"
                        + "TO_CHAR(o.object_id,'TM9','NLS_NUMERIC_CHARACTERS=''.,'''),TO_CHAR(o.data_object_id,'TM9','NLS_NUMERIC_CHARACTERS=''.,'''),"
                        + "CASE WHEN o.object_type = 'TABLE' AND o.subobject_name IS NULL AND o.generated = 'N' AND o.secondary = 'N'"
                        + " AND o.data_object_id IS NOT NULL AND t.temporary = 'N' AND t.partitioned = 'NO' AND t.iot_type IS NULL AND t.nested = 'NO'"
                        + " AND t.cluster_name IS NULL AND t.duration IS NULL"
                        + " AND NOT EXISTS (SELECT 1 FROM sys.all_external_tables e WHERE e.owner = o.owner AND e.table_name = o.object_name)"
                        + " AND NOT EXISTS (SELECT 1 FROM sys.all_mviews m WHERE m.owner = o.owner AND m.mview_name = o.object_name)"
                        + " AND NOT EXISTS (SELECT 1 FROM sys.all_policies p WHERE p.object_owner = o.owner AND p.object_name = o.object_name)"
                        + " THEN 1 ELSE 0 END"
                        + " FROM sys.all_objects o JOIN sys.all_tables t ON t.owner = o.owner AND t.table_name = o.object_name"
                        + " WHERE o.owner = ? AND o.object_name = ? AND o.object_type = 'TABLE' AND o.subobject_name IS NULL")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            statement.setString(1, owner);
            statement.setString(2, name);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("Oracle cursor relation is absent or not a verified heap");
                }
                banner = rows.getString(1);
                conName = rows.getString(2);
                conId = rows.getString(3);
                conDbid = rows.getString(4);
                objectId = rows.getString(5);
                dataObjectId = rows.getString(6);
                if (banner == null || !banner.startsWith(BANNER_PREFIX) || !banner.contains(
                        VERSION_MARK) || banner.length() > 1024 || banner.indexOf('\0') >= 0
                        || conName == null || !conName.matches("[A-Z][A-Z0-9_$#]{0,127}")
                        || conId == null || !conId.matches("[1-9][0-9]{0,8}")
                        || conDbid == null || !conDbid.matches("[1-9][0-9]{0,19}")
                        || objectId == null || !objectId.matches("[1-9][0-9]{0,17}")
                        || dataObjectId == null || !dataObjectId.matches("[1-9][0-9]{0,17}")
                        || rows.getInt(7) != 1 || rows.wasNull() || rows.next()) {
                    throw unsupported("Oracle version, resolved object or storage is not a verified heap");
                }
            }
        }
        var columns = new LinkedHashMap<String, Column>();
        try (var statement = connection.prepareStatement(
                "SELECT c.column_name,c.column_id,c.data_type,c.data_precision,c.data_scale,c.data_length,"
                        + "c.char_used,c.nullable,c.data_default,c.virtual_column,c.identity_column,c.hidden_column,"
                        + "NVL(c.collation,'USING_NLS_COMP'),c.character_set_name,NVL(c.default_on_null,'NO')"
                        + " FROM sys.all_tab_cols c WHERE c.owner=? AND c.table_name=? AND c.virtual_column='NO' AND c.hidden_column='NO'"
                        + " AND c.segment_column_id IS NOT NULL ORDER BY c.column_id")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(MAX_COLUMNS + 1);
            statement.setString(1, owner);
            statement.setString(2, name);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    // ALL_TAB_COLS 在 DATA_TYPE 中保留精度后缀，如 TIMESTAMP(6)，需规范为基础类型。
                    String dataType = rows.getString(3);
                    if (dataType != null) {
                        int paren = dataType.indexOf('(');
                        if (paren >= 0) {
                            dataType = dataType.substring(0, paren);
                        }
                    }
                    var column = new Column(rows.getString(1), rows.getInt(2), dataType,
                            rows.getObject(4, Integer.class),
                            rows.getObject(5, Integer.class), rows.getObject(6, Long.class), rows.getString(7),
                            "Y".equals(rows.getString(8)),
                            rows.getString(9), rows.getString(10), rows.getString(11), rows.getString(12),
                            rows.getString(13),
                            rows.getString(14), rows.getString(15));
                    // 虚拟列及不可见列不是段列，因此排除；需要这些列的表由值域校验拒绝。
                    if (columns.size() >= MAX_COLUMNS || column.name() == null || column.dataType() == null
                            || column.charLength() == null || column.charLength() < 1
                            || !Set.of("Y", "N").contains(rows.getString(8)) || !Set.of("YES", "NO")
                            .contains(column.virtualColumn())
                            || !Set.of("YES", "NO").contains(column.identityColumn()) || !Set.of("YES", "NO")
                            .contains(column.hiddenColumn())
                            || !Set.of("YES", "NO").contains(column.defaultOnNull())
                            || columns.putIfAbsent(column.name(), column) != null) {
                        throw unsupported("Oracle column catalog is ambiguous or exceeds its bound");
                    }
                }
            }
        }
        if (columns.isEmpty()) {
            throw unsupported("Oracle column catalog is not visible");
        }
        var primary = new ArrayList<String>();
        try (var statement = connection.prepareStatement(
                "SELECT cc.column_name FROM sys.all_constraints c JOIN sys.all_cons_columns cc "
                        + "ON c.owner=cc.owner AND c.constraint_name=cc.constraint_name WHERE c.owner=? AND c.table_name=? AND c.constraint_type='P'"
                        + " AND c.status='ENABLED' ORDER BY cc.position")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(33);
            statement.setString(1, owner);
            statement.setString(2, name);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (primary.size() >= 32 || !columns.containsKey(rows.getString(1)) || primary.contains(
                            rows.getString(1))) {
                        throw unsupported("Oracle primary key catalog is ambiguous");
                    } else {
                        primary.add(rows.getString(1));
                    }
                }
            }
        }
        Map<String, List<String>> unique = new TreeMap<>();
        Set<String> unsafe = new TreeSet<>();
        try (var statement = connection.prepareStatement("SELECT c.constraint_name,cc.column_name,cc.position,"
                + "i.index_type,i.uniqueness,i.index_name,(SELECT COUNT(*) FROM sys.all_ind_expressions e WHERE e.index_owner=i.owner AND e.index_name=i.index_name),"
                + "col.nullable FROM sys.all_constraints c JOIN sys.all_cons_columns cc ON c.owner=cc.owner AND c.constraint_name=cc.constraint_name"
                + " JOIN sys.all_indexes i ON i.owner=c.index_owner AND i.index_name=c.index_name"
                + " JOIN sys.all_tab_columns col ON col.owner=c.owner AND col.table_name=c.table_name AND col.column_name=cc.column_name"
                + " WHERE c.owner=? AND c.table_name=? AND c.constraint_type IN ('P','U') AND c.status='ENABLED'"
                + " ORDER BY c.constraint_name,cc.position")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(4097);
            statement.setString(1, owner);
            statement.setString(2, name);
            try (var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    String constraint = rows.getString(1), column = rows.getString(2);
                    if (++count > 4096 || constraint == null) {
                        throw unsupported("Oracle unique constraint catalog exceeds its bound");
                    }
                    var keys = unique.computeIfAbsent(constraint, ignored -> new ArrayList<>());
                    if (column == null || !columns.containsKey(column) || rows.getInt(3) != keys.size() + 1
                            || !"NORMAL".equals(rows.getString(4)) || !"UNIQUE".equals(rows.getString(5))
                            || rows.getInt(7) != 0 || !"N".equals(rows.getString(8))) {
                        unsafe.add(constraint);
                    }
                    keys.add(column);
                }
            }
        }
        if (primary.isEmpty()) {
            throw unsupported("Oracle cursor/UPSERT requires an enabled primary key");
        }
        if (!unique.containsKey(primaryKeyConstraint(owner, name, connection, timeout)) || !primary.equals(
                unique.get(primaryKeyConstraint(owner, name, connection, timeout)))) {
            throw unsupported("Oracle primary key and unique index catalogs disagree");
        }
        return new Shape(owner, name, banner, conName, conId, conDbid, objectId, dataObjectId, columns, primary, unique,
                unsafe);
    }

    /**
     * 读取目标表主键约束的名称。
     */
    private static String primaryKeyConstraint(String owner, String name, Connection connection,
            int timeout) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT constraint_name FROM sys.all_constraints WHERE owner=? AND table_name=? AND constraint_type='P' AND status='ENABLED'")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            statement.setString(1, owner);
            statement.setString(2, name);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("Oracle primary key constraint is unavailable");
                }
                String constraint = rows.getString(1);
                if (constraint == null || rows.next()) {
                    throw unsupported("Oracle primary key constraint is ambiguous");
                }
                return constraint;
            }
        }
    }

    /** 有序键凭据：精确数值值域可跨产品重放，其他键仅在相同原生契约下重放。 */
    public static CursorKeyContract keyContract(Shape shape, List<String> keys,
            String textOrdering) throws SQLException {
        if (!shape.primaryKey().equals(keys)) {
            throw unsupported("Oracle replay requires the exact ordered non-null primary key");
        }
        var material = new StringBuilder("ORACLE_CURSOR_KEYS_V2\0").append(shape.banner()).append('\0')
                .append(textOrdering);
        var domains = new ArrayList<CursorKeyContract.Domain>();
        for (String key : keys) {
            var column = shape.columns().get(key);
            if (column == null || column.nullable() || column.virtual() || column.identity() || column.hidden()) {
                throw unsupported("Oracle cursor key requires a complete non-null ordinary column");
            }
            domains.add(domain(column));
            material.append('\0').append(keySqlTypeWithEvidence(column));
        }
        return new CursorKeyContract(ProductTypeEnum.ORACLE, DigestUtil.sha256Hex(material.toString()), domains);
    }

    /**
     * 结合排序规则及字符集证据生成键列 SQL 类型。
     */
    private static String keySqlTypeWithEvidence(Column column) throws SQLException {
        String base = column.keySqlType();
        if (textual(column)) {
            if (!"USING_NLS_COMP".equals(column.collation()) || column.characterSet() == null) {
                throw unsupported("Oracle text cursor key has an unverified declared collation or character set");
            }
            return base + ":" + column.characterSet() + ":" + column.collation();
        }
        return base;
    }

    /**
     * 根据当前类型及元数据证据确定允许的值域。
     */
    public static CursorKeyContract.Domain domain(Column column) throws SQLException {
        return switch (column.dataType()) {
            case "NUMBER" -> {
                if (column.precision() == null) {
                    yield CursorKeyContract.Domain.nativeOnly();
                }
                if (column.precision() < 1 || column.precision() > 38 || column.scale() == null
                        || column.scale() < 0 || column.scale() > column.precision()) {
                    yield CursorKeyContract.Domain.nativeOnly();
                }
                yield CursorKeyContract.Domain.decimal(column.precision(), column.scale(), false);
            }
            case "DATE", "TIMESTAMP", "VARCHAR2", "CHAR", "NVARCHAR2", "NCHAR" -> CursorKeyContract.Domain.nativeOnly();
            default -> throw unsupported("Oracle cursor key type is not verified");
        };
    }

    /** 排序列必须非空，且属于已验证的精确值域之一。 */
    public static void ordered(Column column) throws SQLException {
        if (column == null || column.nullable() || column.virtual() || column.identity() || column.hidden()) {
            throw unsupported("Oracle cursor ordering requires a complete non-null ordinary column");
        }
        switch (column.dataType()) {
            case "NUMBER" -> {
                if (column.precision() != null && (column.precision() < 1 || column.precision() > 38
                        || column.scale() == null || column.scale() < 0 || column.scale() > column.precision())) {
                    throw unsupported("Oracle NUMBER ordering domain is unavailable");
                }
            }
            case "TIMESTAMP" -> {
                if (column.scale() == null || column.scale() < 0 || column.scale() > 9) {
                    throw unsupported("Oracle TIMESTAMP ordering precision is unavailable");
                }
            }
            case "DATE" -> {
            }
            case "VARCHAR2", "CHAR", "NVARCHAR2", "NCHAR" -> {
                if (column.charLength() == null || column.charLength() < 1 || column.charLength() > 4000
                        || !"USING_NLS_COMP".equals(column.collation()) || column.characterSet() == null) {
                    throw unsupported("Oracle text ordering domain is not verified");
                }
            }
            default -> throw unsupported(
                    "Oracle cursor ordering requires a verified NUMBER, DATE, TIMESTAMP or text column");
        }
    }

    /**
     * 返回当前文本。
     */
    public static boolean textual(Column column) {
        return Set.of("VARCHAR2", "CHAR", "NVARCHAR2", "NCHAR").contains(column.dataType());
    }

    /** 持久化游标承载类型：时间键作为文本解析的本地值传输，CHAR 共用文本承载类型。 */
    public static int cursorJdbcType(Column column) throws SQLException {
        ordered(column);
        return switch (column.dataType()) {
            case "NUMBER" -> Types.NUMERIC;
            case "DATE", "TIMESTAMP" -> Types.TIMESTAMP;
            case "VARCHAR2", "CHAR" -> Types.VARCHAR;
            default -> Types.NVARCHAR;
        };
    }

    /**
     * 返回游标值。
     */
    public static TypedCursorValue cursorValue(ResultSet rows, int index, Column column) throws SQLException {
        ordered(column);
        Object value = switch (column.dataType()) {
            case "NUMBER" -> rows.getBigDecimal(index);
            case "DATE" -> rows.getObject(index, LocalDateTime.class);
            case "TIMESTAMP" -> rows.getObject(index, LocalDateTime.class);
            case "VARCHAR2", "CHAR", "NVARCHAR2", "NCHAR" -> rows.getString(index);
            default -> throw unsupported("Oracle cursor ordering type is not verified");
        };
        if (value == null) {
            throw new SQLException("Oracle cursor ordering value is null");
        }
        Object exact = key(value, column);
        return TypedCursorValue.of(cursorJdbcType(column), column.keySqlType(), exact);
    }

    /** 任何语句绑定之前，按列值域校验持久化游标值。 */
    public static void validateKey(TypedCursorValue value, Column column) throws SQLException {
        if (value.jdbcType() != cursorJdbcType(column) || !value.typeName().equals(column.keySqlType())) {
            throw new SQLException("Oracle cursor key type changed");
        }
        key(value.jdbcValue(), column);
    }

    /**
     * 返回键。
     */
    public static Object key(Object value, Column column) throws SQLException {
        Object exact = exact(value, column);
        if (exact == null || exact instanceof String text && text.indexOf('\0') >= 0) {
            throw new SQLException("Oracle cursor key has no exact replay identity");
        }
        return exact;
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    public static void bind(PreparedStatement statement, int index, TypedCursorValue value,
            Column column) throws SQLException {
        validateKey(value, column);
        Object exact = value.jdbcValue();
        switch (column.dataType()) {
            case "NUMBER" -> statement.setBigDecimal(index, (BigDecimal) exact);
            case "DATE", "TIMESTAMP" -> statement.setString(index, temporalText(column, (LocalDateTime) exact));
            case "VARCHAR2", "CHAR" -> statement.setString(index, (String) exact);
            default -> statement.setNString(index, (String) exact);
        }
    }

    /** 比较占位符，按当前会话格式保留绑定游标值的所有数字位。 */
    public static String parameter(Column column) throws SQLException {
        ordered(column);
        return switch (column.dataType()) {
            case "DATE" -> "TO_DATE(?,'YYYY-MM-DD HH24:MI:SS')";
            case "TIMESTAMP" ->
                    "TO_TIMESTAMP(?,'YYYY-MM-DD HH24:MI:SS" + (column.scale() == 0 ? "" : ".FF" + column.scale()) + "')";
            default -> "?";
        };
    }

    /** MERGE 派生源键表达式；CAST 固定文本键的比较语义。 */
    public static String keySource(Column column) throws SQLException {
        ordered(column);
        return switch (column.dataType()) {
            case "NUMBER" -> "CAST(? AS NUMBER)";
            case "DATE" -> "TO_DATE(?,'YYYY-MM-DD HH24:MI:SS')";
            case "TIMESTAMP" ->
                    "TO_TIMESTAMP(?,'YYYY-MM-DD HH24:MI:SS" + (column.scale() == 0 ? "" : ".FF" + column.scale()) + "')";
            case "VARCHAR2" -> "CAST(? AS VARCHAR2(" + column.charLength() + ("C".equals(
                    column.charUsed()) ? " CHAR" : " BYTE") + "))";
            case "CHAR" -> "CAST(? AS CHAR(" + column.charLength() + ("C".equals(
                    column.charUsed()) ? " CHAR" : " BYTE") + "))";
            case "NVARCHAR2" -> "CAST(? AS NVARCHAR2(" + column.charLength() + "))";
            default -> "CAST(? AS NCHAR(" + column.charLength() + "))";
        };
    }

    /**
     * 检查键列可排序后生成目标键与源键的相等比较。
     */
    public static String keyMatch(Column column, String target, String source) throws SQLException {
        ordered(column);
        return target + "=" + source;
    }

    /**
     * 按键列精确类型选择预编译参数绑定类型。
     */
    public static int keyBindType(Column column) throws SQLException {
        ordered(column);
        return switch (column.dataType()) {
            case "NUMBER" -> Types.NUMERIC;
            case "DATE", "TIMESTAMP" -> Types.VARCHAR;
            case "VARCHAR2", "CHAR" -> Types.VARCHAR;
            default -> Types.NVARCHAR;
        };
    }

    /** 用于比较参数及派生源转换的时间文本；日期小数位数固定为 0。 */
    public static String temporalText(Column column, LocalDateTime value) throws SQLException {
        int scale = "DATE".equals(column.dataType()) ? 0 : column.scale();
        return DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss" + (scale == 0 ? "" : "." + "S".repeat(scale)))
                .format(value);
    }

    /** 按原生值域准入精确值，不进行任何静默舍入、填充或截断。 */
    public static Object exact(Object value, Column column) throws SQLException {
        if (value == null) {
            if (!column.nullable()) {
                throw new SQLException("Oracle required value is null");
            }
            return null;
        }
        return switch (column.dataType()) {
            case "NUMBER" -> number(value, column);
            case "VARCHAR2", "CHAR", "NVARCHAR2", "NCHAR" -> text(value, column);
            case "DATE", "TIMESTAMP" -> temporal(value, column);
            default -> throw new SQLException("Oracle column type is not verified for exact replay");
        };
    }

    /**
     * 按当前值域约定解析或转换数值。
     */
    private static BigDecimal number(Object value, Column column) throws SQLException {
        BigDecimal number;
        if (value instanceof BigDecimal decimal) {
            number = decimal;
        } else if (value instanceof java.math.BigInteger integer) {
            number = new BigDecimal(integer);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            number = BigDecimal.valueOf(((Number) value).longValue());
        } else {
            throw new SQLException("Oracle exact numeric input required");
        }
        if (column.precision() != null) {
            if (column.scale() == null) {
                throw new SQLException("Oracle NUMBER scale is unavailable");
            }
            try {
                number = number.setScale(column.scale(), java.math.RoundingMode.UNNECESSARY);
            } catch (ArithmeticException invalid) {
                throw new SQLException("Oracle numeric input loses scale");
            }
            BigDecimal limit = BigDecimal.TEN.pow(column.precision() - column.scale());
            if (number.abs().compareTo(limit) >= 0) {
                throw new SQLException("Oracle numeric input exceeds the target domain");
            }
        } else if (number.scale() > 127 || number.precision() - number.scale() > 38) {
            throw new SQLException("Oracle unbounded NUMBER input exceeds its representable domain");
        }
        return number;
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    private static String text(Object value, Column column) throws SQLException {
        if (!(value instanceof String text)) {
            throw new SQLException("Oracle text input is not materialized");
        }
        // Oracle 将空字符串存为 NULL，无法精确重放。
        if (text.isEmpty()) {
            throw new SQLException("Oracle cannot store an empty string exactly");
        }
        if (column.charLength() == null || column.charLength() < 1 || column.charLength() > 4000) {
            throw new SQLException("Oracle text domain is not verified");
        }
        int bytes;
        try {
            bytes = java.nio.charset.StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text))
                    .remaining();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new SQLException("Oracle text contains invalid Unicode");
        }
        int chars = text.codePointCount(0, text.length());
        boolean byChar = "C".equals(column.charUsed()) || "NVARCHAR2".equals(column.dataType()) || "NCHAR".equals(
                column.dataType());
        if (byChar ? chars > column.charLength() : bytes > column.charLength()) {
            throw new SQLException("Oracle text input exceeds the target length");
        }
        return text;
    }

    /**
     * 按当前值域约定解析或转换时间值。
     */
    private static LocalDateTime temporal(Object value, Column column) throws SQLException {
        LocalDateTime time;
        if (value instanceof LocalDateTime local) {
            time = local;
        } else if (value instanceof java.sql.Timestamp timestamp) {
            time = timestamp.toLocalDateTime();
        } else if (value instanceof java.sql.Date date) {
            time = date.toLocalDate().atStartOfDay();
        } else if (value instanceof LocalDate date) {
            time = date.atStartOfDay();
        } else {
            throw new SQLException("Oracle temporal input requires a civil date-time value");
        }
        if (time.getYear() < 1 || time.getYear() > 9999) {
            throw new SQLException("Oracle temporal input is outside the supported range");
        }
        int scale = "DATE".equals(column.dataType()) ? 0 : column.scale();
        if (scale < 0 || scale > 9) {
            throw new SQLException("Oracle temporal precision is unavailable");
        }
        int factor = 1;
        for (int i = 0; i < 9 - scale; i++) {
            factor *= 10;
        }
        if (time.getNano() % factor != 0) {
            throw new SQLException("Oracle temporal input loses precision");
        }
        return time;
    }

    /** UPSERT 目标端准入：触发器、策略、引用动作、标识生成或虚拟生成均不得参与重放。 */
    public static void requireSideEffects(Connection connection, Shape shape, List<String> written, List<String> keys,
            int timeout) throws SQLException {
        // 没有已启用关联约束的唯一索引仍是 MERGE 冲突目标，因此拒绝。
        try (var statement = connection.prepareStatement(
                "SELECT i.index_name FROM sys.all_indexes i WHERE i.table_owner=? AND i.table_name=? AND i.uniqueness='UNIQUE'")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(4097);
            statement.setString(1, shape.schema());
            statement.setString(2, shape.table());
            try (var rows = statement.executeQuery()) {
                var indexes = new TreeSet<String>();
                while (rows.next()) {
                    if (indexes.size() >= 4096 || !indexes.add(rows.getString(1))) {
                        throw unsupported("Oracle unique index catalog exceeds its bound");
                    }
                }
                try (var constraint = connection.prepareStatement(
                        "SELECT i.index_name FROM sys.all_constraints c JOIN sys.all_indexes i "
                                + "ON i.owner=c.index_owner AND i.index_name=c.index_name WHERE c.owner=? AND c.table_name=? AND c.constraint_type IN ('P','U') AND c.status='ENABLED'")) {
                    constraint.setQueryTimeout(timeout);
                    constraint.setMaxRows(4097);
                    constraint.setString(1, shape.schema());
                    constraint.setString(2, shape.table());
                    try (var backed = constraint.executeQuery()) {
                        var backing = new TreeSet<String>();
                        while (backed.next()) {
                            if (backing.size() >= 4096 || !backing.add(backed.getString(1))) {
                                throw unsupported("Oracle unique constraint catalog exceeds its bound");
                            }
                        }
                        if (!indexes.equals(backing)) {
                            throw unsupported(
                                    "Oracle unique indexes without verified constraints are unsafe conflict targets");
                        }
                    }
                }
            }
        }
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sys.all_triggers WHERE table_owner=? AND table_name=? AND status='ENABLED'")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            statement.setString(1, shape.schema());
            statement.setString(2, shape.table());
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw unsupported("Oracle target triggers have unverified replay side effects");
                }
            }
        }
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sys.all_policies WHERE object_owner=? AND object_name=?")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            statement.setString(1, shape.schema());
            statement.setString(2, shape.table());
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw unsupported("Oracle target row-level policies have unverified replay side effects");
                }
            }
        }
        for (var column : shape.columns().values()) {
            if (column.virtual() || column.identity() || column.hidden()) {
                throw unsupported("Oracle target generated columns require an explicit replay plan");
            }
            if ("YES".equals(column.defaultOnNull())) {
                throw unsupported("Oracle target DEFAULT ON NULL columns would replace replayed NULLs");
            }
        }
        var updates = written.stream().filter(name -> !keys.contains(name)).toList();
        if (!updates.isEmpty()) {
            try (var statement = connection.prepareStatement(
                    "SELECT 1 FROM sys.all_constraints child JOIN sys.all_cons_columns cc "
                            + "ON child.owner=cc.owner AND child.constraint_name=cc.constraint_name JOIN sys.all_constraints parent "
                            + "ON parent.owner=child.r_owner AND parent.constraint_name=child.r_constraint_name"
                            + " WHERE parent.owner=? AND parent.table_name=? AND cc.column_name IN (" + String.join(",",
                            java.util.Collections.nCopies(updates.size(), "?")) + ")")) {
                statement.setQueryTimeout(timeout);
                statement.setMaxRows(2);
                statement.setString(1, shape.schema());
                statement.setString(2, shape.table());
                for (int i = 0; i < updates.size(); i++) {
                    statement.setString(3 + i, updates.get(i));
                }
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        throw unsupported(
                                "Oracle child foreign keys on updated columns have unverified referential actions");
                    }
                }
            }
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    public static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
