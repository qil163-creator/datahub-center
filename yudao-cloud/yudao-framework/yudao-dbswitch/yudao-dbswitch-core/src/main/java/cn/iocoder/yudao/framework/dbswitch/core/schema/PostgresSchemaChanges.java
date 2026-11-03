package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier;

import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * PostgreSQL 同一连接上的新鲜度边界。调用方负责授权和事务。
 * 不提交、不回滚、不关闭；结果失败或未知时须重新观测。
 * 对现有普通表获取排他关系锁。对尚不存在的名称获取协作式
 * 命名空间及名称建议锁；不配合的并发 CREATE 仍会使 CREATE 安全失败。
 */
public final class PostgresSchemaChanges {

    /**
     * 初始化 {@code PostgresSchemaChanges}。
     */
    private PostgresSchemaChanges() {
    }

    /** 重建计划在执行前整体解析；DROP 与 CREATE 必须由调用方在同一个冻结事务中执行。 */
    public static SqlScriptPlan rebuildPlan(String schema, String table, List<String> createStatements,
            boolean tableExists) {
        if (createStatements == null || createStatements.isEmpty()) {
            throw new IllegalArgumentException("A rebuild requires a create plan");
        }
        var statements = new java.util.ArrayList<String>();
        if (tableExists) {
            statements.add("DROP TABLE " + RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL, schema,
                    table));
        }
        statements.addAll(createStatements);
        var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, String.join(";\n", statements));
        if (plan.steps().stream().anyMatch(step -> step.kind() != SqlStatementClassifier.Kind.DDL)) {
            throw new IllegalArgumentException("A rebuild only accepts a complete DDL plan");
        }
        return plan;
    }

    /** 权威物理目标身份不包含连接账号、平台编号或客户端主机别名；重建和目标尚不存在时保持稳定。 */
    public static String physicalTargetFingerprint(Connection connection, AbstractDatabase database,
            String schema, String table, MetadataReadOptions options) throws SQLException {
        requirePostgres(connection, database);
        RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL, schema, table);
        // 集群控制标识不随 IPv4、IPv6、网卡或代理入口变化；缺执行权限时明确拒绝，不能退回地址推断。
        try (var statement = connection.prepareStatement(
                "SELECT control.system_identifier::text,d.datname,d.oid::text,n.oid::text,current_setting('max_identifier_length')::integer FROM pg_catalog.pg_control_system() control CROSS JOIN pg_catalog.pg_database d CROSS JOIN pg_catalog.pg_namespace n WHERE d.datname=current_database() AND n.nspname=?")) {
            statement.setQueryTimeout(options.timeoutSeconds());
            statement.setString(1, schema);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Physical schema identity cannot be proven");
                }
                int maxIdentifierBytes = rows.getInt(5);
                if (maxIdentifierBytes < 1 || schema.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8).length > maxIdentifierBytes
                        || table.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxIdentifierBytes) {
                    throw new SQLException("Physical identifier would be truncated by PostgreSQL");
                }
                StringBuilder identity = new StringBuilder("PG_PHYSICAL_TARGET_V1");
                for (int i = 1; i <= 4; i++) {
                    String value = rows.getString(i);
                    if (value == null) {
                        throw new SQLException("Physical server identity cannot be proven");
                    }
                    identity.append(value.length()).append(':').append(value);
                }
                identity.append(table.length()).append(':').append(table);
                if (rows.next()) {
                    throw new SQLException("Physical target identity is ambiguous");
                }
                return SqlScriptPlan.digest(identity.toString());
            }
        }
    }

    /** 对账与执行共用同一个目标锁；调用方拥有事务和连接，并应在持锁回调完成围栏状态更新。 */
    public static SchemaObservation lockAndObserve(Connection connection, AbstractDatabase database,
            String schema, String table, MetadataReadOptions options) throws SQLException {
        requirePostgres(connection, database);
        if (connection.getAutoCommit() || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalArgumentException("Schema locking requires the caller's READ_COMMITTED transaction");
        }
        String target = physicalTargetFingerprint(connection, database, schema, table, options);
        long key = Long.parseUnsignedLong(target.substring(0, 16), 16);
        try (var statement = connection.prepareStatement("SELECT pg_catalog.pg_advisory_xact_lock(?)")) {
            statement.setQueryTimeout(options.timeoutSeconds());
            statement.setLong(1, key);
            statement.execute();
        }
        SchemaObservation initial = observe(connection, database, schema, table, options);
        if (Boolean.TRUE.equals(initial.metadata().existence().value())) {
            String relation = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL, schema, table);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(options.timeoutSeconds());
                statement.execute("LOCK TABLE ONLY " + relation + " IN ACCESS EXCLUSIVE MODE");
            }
        }
        return observe(connection, database, schema, table, options);
    }

    /**
     * 拒绝当前实现未支持的 {@code requirePostgres} 操作。
     */
    private static void requirePostgres(Connection connection, AbstractDatabase database) throws SQLException {
        if (database.getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            throw new SQLFeatureNotSupportedException("Physical target identity is verified only for PostgreSQL");
        }
        var jdbc = connection.getMetaData();
        if (!"PostgreSQL".equals(jdbc.getDatabaseProductName()) || jdbc.getDatabaseMajorVersion() < 12) {
            throw new SQLFeatureNotSupportedException("Native PostgreSQL schema identity is unverified");
        }
    }

    /**
     * 读取当前目标对象或运行状态的最新事实。
     */
    public static SchemaObservation observe(Connection connection, AbstractDatabase database,
            String schema, String table, MetadataReadOptions options) throws SQLException {
        if (database.getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            throw new SQLFeatureNotSupportedException("Locked schema changes are verified only for PostgreSQL");
        }
        String relation = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL, schema, table);
        var jdbc = connection.getMetaData();
        if (!"PostgreSQL".equals(jdbc.getDatabaseProductName()) || jdbc.getDatabaseMajorVersion() < 12) {
            throw new SQLFeatureNotSupportedException("Native PostgreSQL schema observation is unverified");
        }
        String scope;
        try (var statement = connection.prepareStatement(
                "SELECT current_database(),current_user,inet_server_addr()::text,inet_server_port(),n.oid::text FROM pg_catalog.pg_namespace n WHERE n.nspname=?")) {
            statement.setQueryTimeout(options.timeoutSeconds());
            statement.setString(1, schema);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(3) == null) {
                    throw new SQLException("Schema scope cannot be proven");
                }
                StringBuilder identity = new StringBuilder("PG_SCHEMA_SCOPE_V1");
                for (int i = 1; i <= 5; i++) {
                    String value = rows.getString(i);
                    identity.append(value.length()).append(':').append(value);
                }
                scope = SqlScriptPlan.digest(identity.toString());
                if (rows.next()) {
                    throw new SQLException("Schema scope is ambiguous");
                }
            }
        }
        boolean exists;
        try (var statement = connection.prepareStatement("SELECT pg_catalog.to_regclass(?) IS NOT NULL")) {
            statement.setQueryTimeout(options.timeoutSeconds());
            statement.setString(1, relation);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Missing existence observation");
                }
                exists = rows.getBoolean(1);
            }
        }
        if (!exists) {
            var provenance = new MetadataProvenance(ProductTypeEnum.POSTGRESQL, jdbc.getDatabaseProductName(),
                    jdbc.getDatabaseProductVersion(), "NATIVE_FRESH_ABSENCE_V1", Instant.now());
            return new SchemaObservation(schema, table, scope, null, false,
                    new TableSchemaMetadata(List.of(), List.of(), provenance, MetadataValue.value(false),
                            MetadataValue.absent()));
        }
        String identity = database.queryRelationFingerprint(connection, relation);
        var metadata = NativeSchemaReader.read(connection, ProductTypeEnum.POSTGRESQL, schema, table,
                options.timeoutSeconds());
        boolean hasData;
        try (var statement = connection.prepareStatement("SELECT 1 FROM " + relation + " LIMIT 1")) {
            statement.setQueryTimeout(options.timeoutSeconds());
            try (var rows = statement.executeQuery()) {
                hasData = rows.next();
            }
        }
        if (!identity.equals(database.queryRelationFingerprint(connection, relation))) {
            throw new SQLException("Relation changed during observation");
        }
        return new SchemaObservation(schema, table, scope, identity, hasData, metadata);
    }

    /**
     * 拒绝当前实现未支持的 {@code execute} 操作。
     */
    public static SchemaObservation execute(Connection connection, AbstractDatabase database, SqlScriptPlan plan,
            SchemaPrecondition expected, MetadataReadOptions options,
            IntConsumer beforeStep) throws SQLException {
        if (plan.product() != ProductTypeEnum.POSTGRESQL || database.getDatabaseType() != plan.product()
                || plan.steps().stream().anyMatch(step -> step.kind() != SqlStatementClassifier.Kind.DDL)) {
            throw new IllegalArgumentException("Schema execution requires a complete PostgreSQL DDL plan");
        }
        if (connection.getAutoCommit() || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalArgumentException("Schema execution requires the caller's READ_COMMITTED transaction");
        }
        if (expected == null) {
            throw new IllegalArgumentException("A frozen schema observation is required");
        }
        SchemaObservation current = lockAndObserve(connection, database, expected.schemaName(), expected.tableName(),
                options);
        if (!expected.matches(current)) {
            throw new SQLException("Frozen schema observation is stale");
        }
        if (Boolean.TRUE.equals(current.metadata().existence().value())
                && current.metadata().additionalObjects().state() != MetadataValue.State.ABSENT) {
            throw new SQLFeatureNotSupportedException("Table dependencies require an explicit schema-change plan");
        }
        var result = database.executePlan(connection, plan, List.of(), 1, null, beforeStep);
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            throw new SQLException("Schema DDL plan did not complete");
        }
        return observe(connection, database, expected.schemaName(), expected.tableName(), options);
    }
}
