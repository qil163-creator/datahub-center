package cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * SQL Server 2000 SP3 普通 int/decimal/varchar/nvarchar 表的列验证依据。
 * 使用 8.00.760 旧版目录，绝不使用现代 sys.* 视图。数据库所有者或
 * 服务器管理员必须观测目录。在事实进入仅支持列的跨产品 API 前，
 * 拒绝标识生成、计算列、别名、绑定规则、复制、全文和额外对象。
 * 此处绝不对借用连接执行提交、
 * 回滚、重新配置或关闭。
 *
 * 此固定引擎没有序列对象，也没有单独声明的列字符集。
 * 列的字符语义通过 syscolumns.collation 观测。
 */
public final class SqlServer2000NativeCatalog {

    public static final String METHOD = "SQLSERVER2000_CATALOG_V1";

    public static final String SERVER_VERSION = "8.00.760";

    private static final int MAX_COLUMNS = 1024;

    /**
     * 初始化 {@code SqlServer2000NativeCatalog}。
     */
    private SqlServer2000NativeCatalog() {
    }

    /** 仅进行产品和名称的纯准入检查。通过后，目录读取失败不得回退。 */
    public static boolean admits(Connection connection, String schema, String table) throws SQLException {
        var jdbc = connection.getMetaData();
        return ordinary(schema) && ordinary(table) && "Microsoft SQL Server".equals(jdbc.getDatabaseProductName())
                && SERVER_VERSION.equals(jdbc.getDatabaseProductVersion());
    }

    /**
     * 读取SQL Server 2000原生目录并构建表结构元数据。
     */
    public static TableSchemaMetadata read(Connection connection, String schema, String table, int timeout)
            throws SQLException {
        return read(connection, schema, table, timeout, false);
    }

    /** 查询生成的自动统计信息不改变重放语义，结构克隆仍保持严格校验。 */
    public static TableSchemaMetadata readForReplay(Connection connection, String schema, String table, int timeout)
            throws SQLException {
        return read(connection, schema, table, timeout, true);
    }

    /**
     * 读取SQL Server 2000原生目录并构建表结构元数据。
     */
    private static TableSchemaMetadata read(Connection connection, String schema, String table, int timeout,
            boolean allowAutomaticStatistics) throws SQLException {
        if (timeout < 1 || timeout > 120) {
            throw new IllegalArgumentException("Invalid metadata timeout");
        }
        var jdbc = connection.getMetaData();
        if (!"Microsoft SQL Server".equals(jdbc.getDatabaseProductName())
                || !SERVER_VERSION.equals(jdbc.getDatabaseProductVersion())) {
            throw unsupported("SQL Server 2000 native catalog requires verified server 8.00.760");
        }
        if (!ordinary(schema) || !ordinary(table)) {
            throw unsupported("SQL Server 2000 native catalog requires ordinary physical identifiers");
        }
        String relation = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.SQLSERVER2000, schema, table);
        verifyVisibility(connection, timeout);
        var before = observe(connection, schema, table, timeout, allowAutomaticStatistics);
        var source = new MetadataProvenance(ProductTypeEnum.SQLSERVER2000, "Microsoft SQL Server", SERVER_VERSION,
                METHOD, Instant.now());
        var columns = shell(connection, relation, timeout);
        if (columns.size() != before.columns().size()) {
            throw unsupported("SQL Server 2000 column snapshot changed");
        }
        for (int i = 0; i < columns.size(); i++) {
            var column = columns.get(i);
            var nativeColumn = before.columns().get(i);
            if (!column.getFieldName().equals(nativeColumn.name())) {
                throw unsupported("SQL Server 2000 column order changed during observation");
            }
            boolean character = nativeColumn.type().equals("varchar") || nativeColumn.type().equals("nvarchar");
            long length = nativeColumn.type().equals("nvarchar") ? nativeColumn.length() / 2L : nativeColumn.length();
            String type = switch (nativeColumn.type()) {
                case "decimal" -> "decimal(" + nativeColumn.precision() + "," + nativeColumn.scale() + ")";
                case "varchar", "nvarchar" -> nativeColumn.type() + "(" + length + ")";
                default -> nativeColumn.type();
            };
            column.setNullable(nativeColumn.nullable());
            column.setAutoIncrement(false);
            column.setDefaultValue(nativeColumn.defaultExpression());
            column.setPk(before.primaryKeys().contains(column.getFieldName()));
            column.setMetadataEvidence(new ColumnMetadataEvidence(source, MetadataValue.value(type),
                    optional(nativeColumn.defaultExpression()), MetadataValue.value(nativeColumn.nullable()),
                    character ? MetadataValue.absent() : MetadataValue.value(nativeColumn.precision()),
                    character ? MetadataValue.absent() : MetadataValue.value(nativeColumn.scale()),
                    character ? MetadataValue.value(length) : MetadataValue.absent(),
                    MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(),
                    optional(nativeColumn.collation()), MetadataValue.absent()));
        }
        if (!before.equals(observe(connection, schema, table, timeout, allowAutomaticStatistics))) {
            throw unsupported("SQL Server 2000 catalog changed during observation");
        }
        return new TableSchemaMetadata(columns, before.primaryKeys(), source, MetadataValue.value(true),
                MetadataValue.absent());
    }

    /**
     * 检查当前数据库账号能否完整观测所需对象事实。
     */
    private static void verifyVisibility(Connection connection, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("SELECT CAST(SERVERPROPERTY('ProductVersion') AS VARCHAR(30)),"
                    + "IS_SRVROLEMEMBER('sysadmin'),IS_MEMBER('db_owner')")) {
                if (!rows.next() || !SERVER_VERSION.equals(rows.getString(1))
                        || !(Integer.valueOf(1).equals(nullableInt(rows, 2)) || Integer.valueOf(1)
                        .equals(nullableInt(rows, 3)))
                        || rows.next()) {
                    throw unsupported(
                            "SQL Server 2000 native catalogs require a verified version and database owner visibility");
                }
            }
        }
    }

    private record ColumnObservation(int ordinal, String name, String type, int length, Integer precision,
                                     Integer scale, boolean nullable, String defaultExpression, String collation) {

    }

    private record Observation(int objectId, Timestamp created, List<ColumnObservation> columns,
                               List<String> primaryKeys) {

    }

    /**
     * 读取当前目标对象或运行状态的最新事实。
     */
    private static Observation observe(Connection connection, String schema, String table, int timeout,
            boolean allowAutomaticStatistics) throws SQLException {
        int objectId;
        Timestamp created;
        try (var statement = connection.prepareStatement(TABLE)) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("SQL Server 2000 requires an ordinary visible user table");
                }
                String kind = rows.getString("xtype");
                // 旧版 xtype 为 CHAR(2)；仅规范化观测到的 U 填充，绝不改变物理名称。
                if (!"U".equals(kind) && !"U ".equals(kind)) {
                    throw unsupported("SQL Server 2000 requires an ordinary visible user table");
                }
                objectId = requiredInt(rows, "id");
                created = rows.getTimestamp("crdate");
                if (objectId <= 0 || created == null || requiredInt(rows, "ftcatid") != 0
                        || requiredInt(rows, "replinfo") != 0 || rows.next()) {
                    throw unsupported("SQL Server 2000 special table metadata is unavailable or unsupported");
                }
            }
        }
        var columns = new ArrayList<ColumnObservation>();
        try (var statement = connection.prepareStatement(COLUMNS)) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(MAX_COLUMNS + 1);
            statement.setInt(1, objectId);
            try (var rows = statement.executeQuery()) {
                int lastOrdinal = 0;
                var names = new HashSet<String>();
                while (rows.next()) {
                    int ordinal = requiredInt(rows, "colid");
                    String name = rows.getString("name");
                    String type = rows.getString("type_name");
                    if (columns.size() >= MAX_COLUMNS || ordinal <= lastOrdinal || name == null || !names.add(name)
                            || requiredInt(rows, "xtype") != requiredInt(rows, "xusertype")
                            || requiredInt(rows, "is_identity") != 0 || requiredInt(rows, "iscomputed") != 0
                            || requiredInt(rows, "domain") != 0 || type == null || !Set.of("int", "decimal", "varchar",
                            "nvarchar").contains(type)) {
                        throw unsupported(
                                "SQL Server 2000 alias, identity, generated, rule or column type is unsupported");
                    }
                    lastOrdinal = ordinal;
                    int length = requiredInt(rows, "length");
                    Integer precision = nullableInt(rows, "prec"), scale = nullableInt(rows, "scale");
                    String collation = rows.getString("collation");
                    boolean character = type.equals("varchar") || type.equals("nvarchar");
                    if (character ? length < 1 || length > 8000 || type.equals(
                            "nvarchar") && length % 2 != 0 || collation == null
                            : precision == null || scale == null || collation != null
                            || type.equals(
                            "decimal") && (precision < 1 || precision > 38 || scale < 0 || scale > precision)) {
                        throw unsupported("SQL Server 2000 native type parameters are incomplete");
                    }
                    String expression = rows.getString("default_expression");
                    int defaultObject = requiredInt(rows, "cdefault");
                    if (requiredInt(rows, "opaque_default") != 0 || defaultObject != 0 && expression == null
                            || defaultObject == 0 && expression != null) {
                        throw unsupported("SQL Server 2000 default definition is incomplete");
                    }
                    int nullable = requiredInt(rows, "isnullable");
                    if (nullable != 0 && nullable != 1) {
                        throw unsupported("SQL Server 2000 nullability is unavailable");
                    }
                    columns.add(new ColumnObservation(ordinal, name, type, length, precision, scale,
                            nullable == 1, expression, collation));
                }
            }
        }
        if (columns.isEmpty()) {
            throw unsupported("SQL Server 2000 column catalog is incomplete");
        }
        boolean primaryKeyExpected = rejectAdditionalObjects(connection, objectId, timeout, allowAutomaticStatistics);
        var keys = new ArrayList<String>();
        try (var statement = connection.prepareStatement(KEYS)) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(MAX_COLUMNS + 1);
            statement.setInt(1, objectId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String key = rows.getString("name");
                    if (keys.size() >= columns.size() || requiredInt(rows, "keyno") != keys.size() + 1
                            || requiredInt(rows, "descending") != 0 || keys.contains(key)
                            || columns.stream().noneMatch(column -> column.name().equals(key))) {
                        throw unsupported("SQL Server 2000 primary key catalog is incomplete or unsupported");
                    }
                    keys.add(key);
                }
            }
        }
        if (primaryKeyExpected != !keys.isEmpty()) {
            throw unsupported("SQL Server 2000 primary key index and column observations disagree");
        }
        return new Observation(objectId, created, List.copyOf(columns), List.copyOf(keys));
    }

    /**
     * 拒绝当前目标范围内未被计划包含的额外对象。
     */
    private static boolean rejectAdditionalObjects(Connection connection, int objectId, int timeout,
            boolean allowAutomaticStatistics) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT xtype FROM sysobjects WHERE parent_obj=? AND xtype NOT IN ('PK','D')")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(1);
            statement.setInt(1, objectId);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw unsupported("SQL Server 2000 additional objects require an explicit reconstruction plan");
                }
            }
        }
        int count = 0, observed = 0;
        try (var statement = connection.prepareStatement(INDEXES)) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(MAX_COLUMNS + 1);
            statement.setInt(1, objectId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (++observed > MAX_COLUMNS) {
                        throw unsupported("SQL Server 2000 index observation exceeds its bound");
                    }
                    if (allowAutomaticStatistics && requiredInt(rows, "statistics") == 1
                            && requiredInt(rows, "automatic_statistics") == 1
                            && requiredInt(rows, "primary_key") == 0 && requiredInt(rows, "clustered") == 0) {
                        continue;
                    }
                    if (++count > 1 || requiredInt(rows, "primary_key") != 1 || requiredInt(rows, "clustered") != 1
                            || requiredInt(rows, "statistics") != 0) {
                        throw unsupported(
                                "SQL Server 2000 extra index or statistics require an explicit reconstruction plan");
                    }
                }
            }
        }
        return count == 1;
    }

    /**
     * 按当前安全约束构建 Shell 命令参数。
     */
    private static List<ColumnDescription> shell(Connection connection, String relation,
            int timeout) throws SQLException {
        var columns = new ArrayList<ColumnDescription>();
        try (var statement = connection.prepareStatement("SELECT TOP 0 * FROM " + relation)) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw unsupported("SQL Server 2000 metadata shell returned data");
                }
                var metadata = rows.getMetaData();
                if (metadata.getColumnCount() < 1 || metadata.getColumnCount() > MAX_COLUMNS) {
                    throw unsupported("SQL Server 2000 column count is outside the read budget");
                }
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    var column = new ColumnDescription();
                    column.setDbType(ProductTypeEnum.SQLSERVER2000);
                    column.setFieldName(metadata.getColumnName(i));
                    column.setFieldType(metadata.getColumnType(i));
                    column.setFieldTypeName(metadata.getColumnTypeName(i));
                    column.setFiledTypeClassName(metadata.getColumnClassName(i));
                    column.setDisplaySize(metadata.getColumnDisplaySize(i));
                    column.setPrecisionSize(metadata.getPrecision(i));
                    column.setScaleSize(metadata.getScale(i));
                    column.setSigned(metadata.isSigned(i));
                    columns.add(column);
                }
            }
        }
        return columns;
    }

    /**
     * 读取整数型目录属性并拒绝缺失的必需事实。
     */
    private static int requiredInt(ResultSet rows, String field) throws SQLException {
        Integer value = nullableInt(rows, field);
        if (value == null) {
            throw unsupported("SQL Server 2000 required catalog fact is unavailable");
        }
        return value;
    }

    /**
     * 读取允许为空的整数型目录属性。
     */
    private static Integer nullableInt(ResultSet rows, String field) throws SQLException {
        int value = rows.getInt(field);
        return rows.wasNull() ? null : value;
    }

    /**
     * 读取允许为空的整数型目录属性。
     */
    private static Integer nullableInt(ResultSet rows, int field) throws SQLException {
        int value = rows.getInt(field);
        return rows.wasNull() ? null : value;
    }

    /**
     * 返回当前可选。
     */
    private static MetadataValue<String> optional(String value) {
        return value == null ? MetadataValue.absent() : MetadataValue.value(value);
    }

    /**
     * 检查当前对象是否为当前路径支持的普通表或列。
     */
    private static boolean ordinary(String name) {
        return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String message) {
        return new SQLFeatureNotSupportedException(message, "0A000");
    }

    // 此固定引擎提供的 sp_MSdroparticleconstraints 检查 replinfo 发布位
    // 1 和 128。要求所有标记为零，同时拒绝未知位，不自行解释。
    private static final String TABLE = "SELECT o.id,o.crdate,o.xtype,o.ftcatid,o.replinfo "
            + "FROM sysobjects o JOIN sysusers u ON u.uid=o.uid WHERE u.name=? AND o.name=?";

    private static final String COLUMNS = "SELECT c.colid,c.name,c.xtype,c.xusertype,t.name AS type_name,c.length,c.prec,c.scale,"
            + "c.isnullable,c.iscomputed,c.domain,c.cdefault,c.collation,COLUMNPROPERTY(c.id,c.name,'IsIdentity') AS is_identity,"
            + "d.text AS default_expression,CASE WHEN c.cdefault<>0 AND (d.text IS NULL OR EXISTS(SELECT 1 FROM syscomments x"
            + " WHERE x.id=c.cdefault AND (x.number<>0 OR x.colid<>1))) THEN 1 ELSE 0 END AS opaque_default "
            + "FROM syscolumns c JOIN systypes t ON t.xusertype=c.xusertype LEFT JOIN syscomments d ON d.id=c.cdefault AND d.number=0 AND d.colid=1 "
            + "WHERE c.id=? ORDER BY c.colid";

    private static final String INDEXES = "SELECT CASE WHEN EXISTS(SELECT 1 FROM sysobjects p WHERE p.parent_obj=i.id AND p.xtype='PK' AND p.name=i.name)"
            + " THEN 1 ELSE 0 END AS primary_key,INDEXPROPERTY(i.id,i.name,'IsClustered') AS [clustered],"
            + "INDEXPROPERTY(i.id,i.name,'IsStatistics') AS [statistics],INDEXPROPERTY(i.id,i.name,'IsAutoStatistics') AS automatic_statistics "
            + "FROM sysindexes i WHERE i.id=? AND i.indid>0 ORDER BY i.indid";

    private static final String KEYS = "SELECT c.name,k.keyno,INDEXKEY_PROPERTY(k.id,k.indid,k.keyno,'IsDescending') AS [descending] "
            + "FROM sysindexkeys k JOIN sysindexes i ON i.id=k.id AND i.indid=k.indid JOIN sysobjects p ON p.parent_obj=i.id AND p.xtype='PK' AND p.name=i.name "
            + "JOIN syscolumns c ON c.id=k.id AND c.colid=k.colid WHERE k.id=? ORDER BY k.keyno";
}
