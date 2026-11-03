package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** SQL Server 16 目录观测。不提交、不改变会话设置，也不持有连接。 */
final class SqlServerNativeSchemaReader {

    /**
     * 初始化 {@code SqlServerNativeSchemaReader}。
     */
    private SqlServerNativeSchemaReader() {
    }

    /**
     * 读取SQL Server原生目录并构建表结构元数据。
     */
    static TableSchemaMetadata read(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        var jdbc = connection.getMetaData();
        if (!"Microsoft SQL Server".equals(jdbc.getDatabaseProductName()) || jdbc.getDatabaseMajorVersion() != 16) {
            throw unsupported("SQL Server native catalog requires verified server major version 16");
        }
        var provenance = new MetadataProvenance(ProductTypeEnum.SQLSERVER, jdbc.getDatabaseProductName(),
                jdbc.getDatabaseProductVersion(), "SQLSERVER16_CATALOG_V1", Instant.now());
        String relation = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.SQLSERVER, schema, table);
        var before = tableObservation(connection, schema, table, relation, timeout);
        List<ColumnDescription> columns = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT * FROM " + relation + " WHERE 1=0")) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery()) {
                var metadata = rows.getMetaData();
                if (metadata.getColumnCount() < 1 || metadata.getColumnCount() > 2000) {
                    throw unsupported("Column count is outside native schema bounds");
                }
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    var column = new ColumnDescription();
                    column.setDbType(ProductTypeEnum.SQLSERVER);
                    column.setFieldName(metadata.getColumnName(i));
                    column.setFieldType(metadata.getColumnType(i));
                    column.setFieldTypeName(metadata.getColumnTypeName(i));
                    column.setFiledTypeClassName(metadata.getColumnClassName(i));
                    column.setDisplaySize(metadata.getColumnDisplaySize(i));
                    column.setPrecisionSize(metadata.getPrecision(i));
                    column.setScaleSize(metadata.getScale(i));
                    column.setSigned(metadata.isSigned(i));
                    column.setAutoIncrement(metadata.isAutoIncrement(i));
                    columns.add(column);
                }
            }
        }
        int observed = 0;
        try (var statement = connection.prepareStatement(COLUMNS)) {
            statement.setQueryTimeout(timeout);
            statement.setInt(1, before.objectId());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (observed >= columns.size() || !columns.get(observed).getFieldName()
                            .equals(rows.getString("name"))) {
                        throw unsupported("Schema changed during native observation");
                    }
                    var column = columns.get(observed++);
                    String base = rows.getString("type_name").toLowerCase(Locale.ROOT);
                    int length = rows.getInt("max_length"), precision = rows.getInt("precision"), scale = rows.getInt(
                            "scale");
                    boolean alias = rows.getInt("user_type_id") != rows.getInt("system_type_id") || rows.getBoolean(
                            "is_assembly_type");
                    String nativeType = alias ? "UNSUPPORTED_ALIAS_OR_CLR" : nativeType(base, length, precision, scale);
                    String expression = rows.getString("default_expression");
                    boolean opaqueDefault = rows.getInt("default_object_id") != 0 && expression == null;
                    // 引用函数或其他对象的计算表达式，无法仅靠其所属表的 DDL 重放。
                    boolean special = rows.getBoolean("is_sparse") || rows.getBoolean(
                            "is_column_set") || rows.getBoolean("is_hidden")
                            || rows.getInt("generated_always_type") != 0 || rows.getInt("encryption_type") != 0
                            || rows.getBoolean("is_filestream") || rows.getInt("rule_object_id") != 0 || rows.getInt(
                            "computed_dependency") != 0;
                    column.setNullable(rows.getBoolean("is_nullable"));
                    column.setDefaultValue(expression);
                    column.setMetadataEvidence(new ColumnMetadataEvidence(provenance, MetadataValue.value(nativeType),
                            opaqueDefault ? MetadataValue.unsupported() : optional(expression),
                            MetadataValue.value(column.isNullable()),
                            MetadataValue.value(precision), MetadataValue.value(scale),
                            base.matches("n?(?:var)?char|(?:var)?binary") ? MetadataValue.value((long) (base.startsWith(
                                    "n") && length > 0 ? length / 2 : length)) : MetadataValue.absent(),
                            rows.getBoolean("is_identity") ? MetadataValue.value(
                                    "IDENTITY(" + rows.getBigDecimal("identity_seed").toBigIntegerExact()
                                            + "," + rows.getBigDecimal("identity_increment").toBigIntegerExact() + ")"
                                            + (rows.getBoolean(
                                            "is_not_for_replication") ? " NOT FOR REPLICATION" : "")) : MetadataValue.absent(),
                            special ? MetadataValue.unsupported() : rows.getBoolean("is_computed")
                                    ? optionalRequired(computed(rows.getString("computed_expression"),
                                    rows.getBoolean("is_persisted"))) : MetadataValue.absent(),
                            MetadataValue.absent(), optional(rows.getString("collation_name")),
                            MetadataValue.absent()));
                }
            }
        }
        if (observed != columns.size()) {
            throw unsupported("Incomplete native column metadata");
        }
        List<String> keys = new ArrayList<>(), additional = new ArrayList<>();
        try (var statement = connection.prepareStatement(PRIMARY_KEYS)) {
            statement.setQueryTimeout(timeout);
            statement.setInt(1, before.objectId());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (rows.getInt("key_ordinal") != keys.size() + 1) {
                        throw unsupported("Invalid native primary key order");
                    }
                    keys.add(rows.getString("name"));
                    if (rows.getInt("type") != 1 || rows.getBoolean("is_descending_key")) {
                        additional.add("PRIMARY_KEY_STORAGE_OR_DIRECTION");
                    }
                }
            }
        }
        try (var statement = connection.prepareStatement(ADDITIONAL)) {
            statement.setQueryTimeout(timeout);
            for (int i = 1; i <= 8; i++) {
                statement.setInt(i, before.objectId());
            }
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (additional.size() >= 2000) {
                        throw unsupported("Additional schema object limit exceeded");
                    }
                    additional.add(rows.getString(1));
                }
            }
        }
        if (!before.equals(tableObservation(connection, schema, table, relation, timeout))) {
            throw unsupported("Schema changed during native observation");
        }
        columns.forEach(column -> column.setPk(keys.contains(column.getFieldName())));
        return new TableSchemaMetadata(columns, keys, provenance, MetadataValue.value(true),
                additional.isEmpty() ? MetadataValue.absent() : MetadataValue.value(additional));
    }

    private record TableObservation(int objectId, Timestamp modified) {

    }

    /**
     * 读取标识表。
     */
    static SqlServerIdentityTable readIdentityTable(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        var columns = read(connection, schema, table, timeout).columns();
        String relation = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.SQLSERVER, schema, table);
        String sql = "SELECT CONVERT(varchar(36),d.database_guid),t.object_id,t.create_date,c.name,"
                + "CONVERT(decimal(38,0),c.seed_value),CONVERT(decimal(38,0),c.increment_value),"
                + "CONVERT(decimal(38,0),c.last_value),CONVERT(decimal(38,0),IDENT_CURRENT(?)),c.is_not_for_replication "
                + "FROM sys.tables t JOIN sys.schemas s ON s.schema_id=t.schema_id "
                + "JOIN sys.database_recovery_status d ON d.database_id=DB_ID() "
                + "LEFT JOIN sys.identity_columns c ON c.object_id=t.object_id WHERE s.name=? AND t.name=?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, relation);
            statement.setString(2, schema);
            statement.setString(3, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("Identity table incarnation is not visible");
                }
                String guid = rows.getString(1);
                long objectId = rows.getLong(2);
                var created = rows.getTimestamp(3).toInstant();
                Map<String, SqlServerIdentityMetadata> values = new LinkedHashMap<>();
                String field = rows.getString(4);
                if (field != null) {
                    var column = columns.stream().filter(candidate -> field.equals(candidate.getFieldName()))
                            .findFirst()
                            .orElseThrow(() -> unsupported("Identity column changed during observation"));
                    var seed = rows.getBigDecimal(5);
                    var step = rows.getBigDecimal(6);
                    var last = rows.getBigDecimal(7);
                    var current = rows.getBigDecimal(8);
                    if (seed == null || step == null || current == null || last != null && last.compareTo(
                            current) != 0) {
                        throw unsupported("Identity position observation is incomplete or changed");
                    }
                    var identity = new SqlServerIdentityMetadata(column.getMetadataEvidence().nativeType().value(),
                            seed.toBigIntegerExact(), step.toBigIntegerExact(),
                            current.toBigIntegerExact(), last != null, rows.getBoolean(9));
                    if (!identity.definition().equals(column.getMetadataEvidence().identity().value())) {
                        throw unsupported("Identity definition changed during observation");
                    }
                    values.put(field, identity);
                }
                if (rows.next()) {
                    throw unsupported("Ambiguous SQL Server identity table");
                }
                return new SqlServerIdentityTable(guid, objectId, created, values);
            }
        }
    }

    /**
     * 读取系统目录中的表属性及原生对象事实。
     */
    private static TableObservation tableObservation(Connection connection, String schema, String table,
            String relation, int timeout) throws SQLException {
        String sql = "SELECT t.object_id,t.modify_date,t.temporal_type,t.is_memory_optimized,t.is_filetable,t.is_node,t.is_edge,t.ledger_type,"
                + "HAS_PERMS_BY_NAME(?, 'OBJECT', 'VIEW DEFINITION') AS visible,"
                + "HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'VIEW DEFINITION') AS dependencies_visible "
                + "FROM sys.tables t JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=? AND t.name=?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, relation);
            statement.setString(2, schema);
            statement.setString(3, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt("visible") != 1 || rows.getInt("dependencies_visible") != 1) {
                    throw unsupported(
                            "Native table and dependency metadata require database and object VIEW DEFINITION permission");
                }
                if (rows.getInt("temporal_type") != 0 || rows.getBoolean("is_memory_optimized") || rows.getBoolean(
                        "is_filetable")
                        || rows.getBoolean("is_node") || rows.getBoolean("is_edge") || rows.getInt(
                        "ledger_type") != 0) {
                    throw unsupported("Special SQL Server table requires an explicit native reconstruction plan");
                }
                var result = new TableObservation(rows.getInt("object_id"), rows.getTimestamp("modify_date"));
                if (rows.next()) {
                    throw unsupported("Ambiguous native table identity");
                }
                return result;
            }
        }
    }

    /**
     * 根据原生列信息确定数据库类型声明。
     */
    private static String nativeType(String type, int length, int precision, int scale) {
        return switch (type) {
            case "decimal", "numeric" -> type + '(' + Integer.toString(precision) + ',' + scale + ')';
            case "char", "varchar", "binary", "varbinary" ->
                    type + '(' + (length == -1 ? "max" : Integer.toString(length)) + ')';
            case "nchar", "nvarchar" -> type + '(' + (length == -1 ? "max" : Integer.toString(length / 2)) + ')';
            case "datetime2", "time", "datetimeoffset" -> type + '(' + Integer.toString(scale) + ')';
            case "float" -> "float(" + precision + ")";
            default -> type;
        };
    }

    /** 计算列依据是 AS 后的子句：目录定义，以及存在时的 PERSISTED 存储方式。 */
    private static String computed(String definition, boolean persisted) {
        return definition == null ? null : persisted ? definition + " PERSISTED" : definition;
    }

    /**
     * 返回当前可选。
     */
    private static MetadataValue<String> optional(String value) {
        return value == null ? MetadataValue.absent() : MetadataValue.value(value);
    }

    /**
     * 根据当前输入解析可选或必需属性约束。
     */
    private static MetadataValue<String> optionalRequired(String value) {
        return value == null ? MetadataValue.unknown() : MetadataValue.value(value);
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String message) {
        return new SQLFeatureNotSupportedException(message, "0A000");
    }

    private static final String COLUMNS = "SELECT c.name,t.name AS type_name,c.max_length,c.precision,c.scale,c.user_type_id,c.system_type_id,t.is_assembly_type,"
            + "c.is_nullable,c.is_identity,c.is_computed,c.is_sparse,c.is_column_set,c.is_hidden,c.generated_always_type,COALESCE(c.encryption_type,0) AS encryption_type,"
            + "c.is_filestream,c.rule_object_id,c.default_object_id,c.collation_name,d.definition AS default_expression,cc.definition AS computed_expression,"
            + "COALESCE(cc.is_persisted,0) AS is_persisted,CASE WHEN EXISTS(SELECT 1 FROM sys.sql_expression_dependencies x WHERE x.referencing_id=c.object_id "
            + "AND x.referencing_minor_id=c.column_id AND (x.referenced_id IS NULL OR x.referenced_id<>c.object_id OR x.referenced_minor_id=0)) THEN 1 ELSE 0 END AS computed_dependency,"
            + "CONVERT(decimal(38,0),ic.seed_value) AS identity_seed,CONVERT(decimal(38,0),ic.increment_value) AS identity_increment,ic.is_not_for_replication "
            + "FROM sys.columns c JOIN sys.types t ON t.user_type_id=c.user_type_id LEFT JOIN sys.default_constraints d ON d.object_id=c.default_object_id "
            + "LEFT JOIN sys.computed_columns cc ON cc.object_id=c.object_id AND cc.column_id=c.column_id "
            + "LEFT JOIN sys.identity_columns ic ON ic.object_id=c.object_id AND ic.column_id=c.column_id WHERE c.object_id=? ORDER BY c.column_id";

    private static final String PRIMARY_KEYS = "SELECT c.name,ic.key_ordinal,ic.is_descending_key,i.type FROM sys.indexes i "
            + "JOIN sys.index_columns ic ON ic.object_id=i.object_id AND ic.index_id=i.index_id JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id "
            + "WHERE i.object_id=? AND i.is_primary_key=1 AND ic.key_ordinal>0 ORDER BY ic.key_ordinal";

    private static final String ADDITIONAL = "SELECT 'CHECK' FROM sys.check_constraints WHERE parent_object_id=? "
            + "UNION ALL SELECT 'FOREIGN_KEY' FROM sys.foreign_keys WHERE parent_object_id=? "
            + "UNION ALL SELECT 'INDEX' FROM sys.indexes WHERE object_id=? AND index_id>0 AND is_primary_key=0 AND is_hypothetical=0 "
            + "UNION ALL SELECT 'TRIGGER' FROM sys.triggers WHERE parent_id=? "
            + "UNION ALL SELECT 'SECURITY_POLICY' FROM sys.security_predicates WHERE target_object_id=? "
            + "UNION ALL SELECT 'PARTITION_OR_COMPRESSION' FROM sys.partitions WHERE object_id=? AND (partition_number<>1 OR data_compression<>0) "
            + "UNION ALL SELECT 'MASK' FROM sys.masked_columns WHERE object_id=? AND is_masked=1 "
            + "UNION ALL SELECT 'CUSTOM_STATISTICS' FROM sys.stats WHERE object_id=? AND user_created=1";
}
