package cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ShenTong V8.0.251217 的列及主键观测。借用连接，绝不
 * 提交、回滚、改变 schema 或持有事务。原生视图包含
 * 负序号系统列和原生类型 OID；二者均不是 JDBC 业务列。
 * 标识生成、生成表达式、字符语义和额外对象仍为 UNKNOWN。
 */
public final class OscarNativeCatalog {

    public static final String METHOD = "OSCAR_CATALOG_V1";

    public static final String SERVER_VERSION = "神通数据库管理系统V8.0.251217 for Linux(x86 64bit) "
            + "(2000 connections) (license invalid after 180 days)";

    public static final String SERVER_BUILD = "神通数据库管理系统V8.0.251217 for Linux(x86 64bit) (2000 connections)";

    /** The server decrements the license suffix at midnight; it is not a different engine build. */
    public static boolean verifiedVersion(String version) {
        if (version == null) {
            return false;
        }
        var match = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(SERVER_BUILD)
                + " \\(license invalid after ([1-9][0-9]{0,2}) days\\)").matcher(version);
        return match.matches() && Integer.parseInt(match.group(1)) <= 180;
    }

    private static final int MAX_COLUMNS = 2000;

    private static final String COLUMNS = """
            SELECT COLUMN_NAME,DATA_TYPE,TYPE_NAME,COLUMN_SIZE,DECIMAL_DIGITS,NULLABLE,REMARKS,COLUMN_DEF,ORDINAL_POSITION
            FROM INFO_SCHEM.V_SYS_COLUMNS
            WHERE TABLE_SCHEM=? AND TABLE_NAME=? AND ORDINAL_POSITION>0 ORDER BY ORDINAL_POSITION
            """;

    private static final String KEYS = """
            SELECT COLUMN_NAME,KEY_SEQ FROM INFO_SCHEM.V_SYS_PRIMARY_KEYS
            WHERE TABLE_SCHEM=? AND TABLE_NAME=? ORDER BY KEY_SEQ
            """;

    /**
     * 初始化 {@code OscarNativeCatalog}。
     */
    private OscarNativeCatalog() {
    }

    /** 目录读取前的纯准入检查。false 允许使用现有 UNKNOWN JDBC 门面；
     * 已准入的原生读取，在目录、协议或授权失败后绝不能回退。 */
    public static boolean admits(Connection connection, String schema, String table) throws SQLException {
        var jdbc = connection.getMetaData();
        return ordinary(schema) && ordinary(table) && "OSCAR".equals(jdbc.getDatabaseProductName())
                && verifiedVersion(jdbc.getDatabaseProductVersion());
    }

    /**
     * 读取当前数据库原生目录并构建表结构元数据。
     */
    public static TableSchemaMetadata read(Connection connection, String schema, String table, int timeoutSeconds)
            throws SQLException {
        if (timeoutSeconds < 1 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("Invalid metadata timeout");
        }
        String relation = exactIdentifier(schema) + "." + exactIdentifier(table);
        var jdbc = connection.getMetaData();
        String observedVersion = jdbc.getDatabaseProductVersion();
        if (!"OSCAR".equals(jdbc.getDatabaseProductName()) || !verifiedVersion(observedVersion)) {
            throw unsupported("Oscar catalog server is unverified");
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("SELECT VERSION() FROM DUAL")) {
                if (!rows.next() || !verifiedVersion(rows.getString(1)) || rows.next()) {
                    throw unsupported("Oscar native catalog version is unverified");
                }
            }
        }
        var source = new MetadataProvenance(ProductTypeEnum.OSCAR, "OSCAR", observedVersion, METHOD, Instant.now());
        var columns = new ArrayList<ColumnDescription>();
        try (var statement = connection.prepareStatement("SELECT * FROM " + relation + " WHERE 1=0")) {
            statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw new SQLException("Oscar metadata shell returned data");
                }
                var metadata = rows.getMetaData();
                int count = metadata.getColumnCount();
                if (count < 1 || count > MAX_COLUMNS) {
                    throw new SQLException("Oscar column metadata limit exceeded");
                }
                for (int i = 1; i <= count; i++) {
                    var column = new ColumnDescription();
                    column.setDbType(ProductTypeEnum.OSCAR);
                    column.setFieldName(metadata.getColumnName(i));
                    column.setFieldType(metadata.getColumnType(i));
                    column.setFieldTypeName(metadata.getColumnTypeName(i));
                    column.setFiledTypeClassName(metadata.getColumnClassName(i));
                    column.setDisplaySize(metadata.getColumnDisplaySize(i));
                    column.setPrecisionSize(metadata.getPrecision(i));
                    column.setScaleSize(metadata.getScale(i));
                    column.setNullable(metadata.isNullable(i) != ResultSetMetaData.columnNoNulls);
                    column.setAutoIncrement(metadata.isAutoIncrement(i));
                    column.setSigned(metadata.isSigned(i));
                    columns.add(column);
                }
            }
        }
        try (var statement = connection.prepareStatement(COLUMNS)) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(MAX_COLUMNS + 1);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                int position = 0;
                int previousOrdinal = 0;
                while (rows.next()) {
                    int ordinal = rows.getInt("ORDINAL_POSITION");
                    if (rows.wasNull() || position >= columns.size() || ordinal <= previousOrdinal) {
                        throw new SQLException("Oscar catalog column order changed during observation");
                    }
                    // 删除列可能使原生 attnum 出现空缺。按物理顺序匹配可见
                    // 空壳，不要将序号空缺误判为缺失的现存列。
                    previousOrdinal = ordinal;
                    var column = columns.get(position++);
                    if (!column.getFieldName().equals(rows.getString("COLUMN_NAME"))) {
                        throw new SQLException("Oscar catalog column identity changed during observation");
                    }
                    String nativeType = rows.getString("TYPE_NAME");
                    if (!nativeTypeAgrees(rows, nativeType, column)) {
                        throw new SQLException("Oscar catalog native type disagrees with JDBC shell");
                    }
                    int nullable = rows.getInt("NULLABLE");
                    boolean nullableKnown = !rows.wasNull() && (nullable == 0 || nullable == 1);
                    if (nullableKnown) {
                        column.setNullable(nullable == 1);
                    }
                    String defaultSql = rows.getString("COLUMN_DEF");
                    column.setDefaultValue(defaultSql);
                    column.setRemarks(rows.getString("REMARKS"));
                    boolean decimal = column.getFieldType() == Types.NUMERIC || column.getFieldType() == Types.DECIMAL;
                    boolean character = Set.of(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR,
                                    Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB)
                            .contains(column.getFieldType());
                    column.setMetadataEvidence(new ColumnMetadataEvidence(source, MetadataValue.value(nativeType),
                            optional(defaultSql),
                            nullableKnown ? MetadataValue.value(nullable == 1) : MetadataValue.unknown(),
                            decimal ? integer(rows, "COLUMN_SIZE") : MetadataValue.absent(),
                            decimal ? integer(rows, "DECIMAL_DIGITS") : MetadataValue.absent(),
                            character ? MetadataValue.unknown() : MetadataValue.absent(),
                            MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                            MetadataValue.unknown()));
                }
                if (position != columns.size()) {
                    throw new SQLException("Oscar catalog columns are incomplete");
                }
            }
        }
        var primaryKeys = new ArrayList<String>();
        var names = new HashSet<String>();
        for (var column : columns) {
            names.add(column.getFieldName());
        }
        try (var statement = connection.prepareStatement(KEYS)) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(MAX_COLUMNS + 1);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString("COLUMN_NAME");
                    if (primaryKeys.size() >= columns.size() || !names.contains(name) || primaryKeys.contains(name)
                            || rows.getInt("KEY_SEQ") != primaryKeys.size() + 1) {
                        throw new SQLException("Oscar catalog primary key is incomplete");
                    }
                    primaryKeys.add(name);
                }
            }
        }
        columns.forEach(column -> column.setPk(primaryKeys.contains(column.getFieldName())));
        return new TableSchemaMetadata(columns, primaryKeys, source, MetadataValue.value(true),
                MetadataValue.unknown());
    }

    /**
     * 此已验证 Oscar 版本将 BIGINT 报告为原生 INT8（OID 20），但。
     */
    private static boolean nativeTypeAgrees(ResultSet rows, String nativeType, ColumnDescription column)
            throws SQLException {
        if (nativeType == null) {
            return false;
        }
        if (nativeType.equalsIgnoreCase(column.getFieldTypeName())) {
            return true;
        }
        // 此已验证 Oscar 版本将 BIGINT 报告为原生 INT8（OID 20），但
        // 零行 JDBC 空壳将其称为 bigint。DATA_TYPE 是原生 OID，而非 java.sql.Types。
        if (!"INT8".equalsIgnoreCase(nativeType) || column.getFieldType() != Types.BIGINT
                || !"bigint".equalsIgnoreCase(column.getFieldTypeName())) {
            return false;
        }
        int nativeOid = rows.getInt("DATA_TYPE");
        return !rows.wasNull() && nativeOid == 20;
    }

    /**
     * 现有 Oscar 投影加引号逻辑将未加引号的引用折叠为大写。目录。
     */
    private static String exactIdentifier(String name) throws SQLFeatureNotSupportedException {
        // 现有 Oscar 投影加引号逻辑将未加引号的引用折叠为大写。目录
        // 参数是物理存储名称：保留大小写，避免读到另一个对象。
        if (!ordinary(name)) {
            throw unsupported("Oscar catalog requires ordinary physical identifiers");
        }
        return "\"" + name + "\"";
    }

    /**
     * 检查当前对象是否为当前路径支持的普通表或列。
     */
    private static boolean ordinary(String name) {
        return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]{0,62}");
    }

    /**
     * 从 JDBC 结果集读取并转换整数。
     */
    private static MetadataValue<Integer> integer(ResultSet rows, String field) throws SQLException {
        int value = rows.getInt(field);
        return rows.wasNull() ? MetadataValue.absent() : MetadataValue.value(value);
    }

    /**
     * 返回当前可选。
     */
    private static MetadataValue<String> optional(String value) {
        return value == null ? MetadataValue.absent() : MetadataValue.value(value);
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String message) {
        return new SQLFeatureNotSupportedException(message, "0A000");
    }
}
