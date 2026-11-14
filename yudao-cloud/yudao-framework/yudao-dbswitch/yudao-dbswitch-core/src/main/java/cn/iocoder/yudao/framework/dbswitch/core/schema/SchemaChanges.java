package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** 受控模型 DDL 的观察与执行边界；PostgreSQL 使用事务锁，Doris 使用持久意图及原生结果对账。 */
public final class SchemaChanges {

    private SchemaChanges() {
    }

    /** 只声明本组件已接线的产品，运行时仍核验原生目录及实际权限。 */
    public static boolean supports(ProductTypeEnum product) {
        return product == ProductTypeEnum.POSTGRESQL || product == ProductTypeEnum.DORIS;
    }

    /** Doris DDL 不具有 PostgreSQL 的整批事务回滚语义。 */
    public static boolean transactionalDdl(ProductTypeEnum product) {
        return product == ProductTypeEnum.POSTGRESQL;
    }

    public enum Outcome { COMMITTED, ROLLED_BACK, NOT_STARTED, UNKNOWN }

    public record ExecutionResult(Outcome outcome, SchemaObservation observation) {
    }

    @FunctionalInterface
    public interface SchemaResultValidator {
        /** 在事务提交前或隐式提交后的原生观测上核验目标结构。 */
        boolean matches(SchemaObservation observation) throws SQLException;
    }

    /** 创建语句必须由组件生成；产品 hook 对冻结后的每个语句重新做窄语法和目标校验。 */
    public static SqlScriptPlan plan(AbstractDatabase database, String schema, String table, List<String> statements) {
        if (!supports(database.getDatabaseType())) throw new IllegalArgumentException("Unsupported schema product");
        if (database.getDatabaseType() == ProductTypeEnum.POSTGRESQL) {
            var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, String.join(";\n", List.copyOf(statements)));
            if (plan.steps().stream().anyMatch(step -> step.kind() != SqlStatementClassifier.Kind.DDL)) {
                throw new IllegalArgumentException("A complete schema DDL plan is required");
            }
            return plan;
        }
        return database.planModelingSchemaScript(schema, table, List.copyOf(statements));
    }

    /** 物理目标不含账号或客户端主机别名，表尚未存在时仍可占用同一个目标。 */
    public static String physicalTargetFingerprint(Connection connection, AbstractDatabase database,
            String schema, String table, MetadataReadOptions options) throws SQLException {
        if (database.getDatabaseType() == ProductTypeEnum.POSTGRESQL) {
            return PostgresSchemaChanges.physicalTargetFingerprint(connection, database, schema, table, options);
        }
        requireDoris(database);
        RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.DORIS, schema, table);
        var scope = dorisScope(connection, schema, options.timeoutSeconds());
        return digest("DORIS_MODEL_TARGET_V1", scope.cluster(), scope.databaseId(), schema, table);
    }

    /** 使用产品原生结构、对象代次和完整 SHOW CREATE 校验，避免遗漏键模型、分区和注释。 */
    public static SchemaObservation observe(Connection connection, AbstractDatabase database,
            String schema, String table, MetadataReadOptions options) throws SQLException {
        if (database.getDatabaseType() == ProductTypeEnum.POSTGRESQL) {
            var first = PostgresSchemaChanges.observe(connection, database, schema, table, options);
            var second = PostgresSchemaChanges.observe(connection, database, schema, table, options);
            if (!first.sameState(second)) throw new SQLException("PostgreSQL schema changed during observation");
            return second;
        }
        requireDoris(database);
        String relation = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.DORIS, schema, table);
        var scope = dorisScope(connection, schema, options.timeoutSeconds());
        String scoped = digest("DORIS_MODEL_SCOPE_V1", scope.cluster(), scope.databaseId(), schema, scope.actor());
        if (!dorisExists(connection, schema, table, options.timeoutSeconds())) {
            var provenance = new MetadataProvenance(ProductTypeEnum.DORIS, "Doris", scope.version(),
                    "DORIS_FRESH_ABSENCE_V1", Instant.now());
            return new SchemaObservation(schema, table, scoped, null, false,
                    new TableSchemaMetadata(List.of(), List.of(), provenance, MetadataValue.value(false), MetadataValue.absent()));
        }
        String generation = database.queryRelationFingerprint(connection, relation);
        String definition = showCreate(connection, relation, options.timeoutSeconds());
        var metadata = database.readModelingSchema(connection, schema, table, options.timeoutSeconds());
        boolean hasData;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(options.timeoutSeconds());
            statement.setMaxRows(1);
            try (var rows = statement.executeQuery("SELECT 1 FROM " + relation + " LIMIT 1")) {
                hasData = rows.next();
            }
        }
        if (!generation.equals(database.queryRelationFingerprint(connection, relation))
                || !definition.equals(showCreate(connection, relation, options.timeoutSeconds()))) {
            throw new SQLException("Doris schema changed during observation");
        }
        return new SchemaObservation(schema, table, scoped, digest(generation, definition), hasData, metadata);
    }

    /** 调用方必须提供独占的自动提交连接；组件不关闭连接，事务结果只依据实际提交/回滚回执。 */
    public static ExecutionResult execute(Connection connection, AbstractDatabase database, SqlScriptPlan plan,
            SchemaPrecondition expected, MetadataReadOptions options, IntConsumer beforeStep) throws SQLException {
        return execute(connection, database, plan, expected, options, beforeStep, ignored -> true);
    }

    /** PostgreSQL 在提交前核验；Doris 失败、超时或核验不符统一保留 UNKNOWN，绝不伪称回滚。 */
    public static ExecutionResult execute(Connection connection, AbstractDatabase database, SqlScriptPlan plan,
            SchemaPrecondition expected, MetadataReadOptions options, IntConsumer beforeStep,
            SchemaResultValidator validator) throws SQLException {
        if (!connection.getAutoCommit() || expected == null || !supports(database.getDatabaseType())
                || plan.product() != database.getDatabaseType()
                || plan.steps().stream().anyMatch(step -> step.kind() != SqlStatementClassifier.Kind.DDL)) {
            throw new IllegalArgumentException("A dedicated connection and complete frozen schema plan are required");
        }
        var verified = plan(database, expected.schemaName(), expected.tableName(),
                plan.steps().stream().map(SqlScriptPlan.Step::sql).toList());
        if (!verified.steps().equals(plan.steps())) throw new IllegalArgumentException("Schema plan changed during admission");
        if (transactionalDdl(plan.product())) {
            boolean commitSent = false;
            try {
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setAutoCommit(false);
                var after = PostgresSchemaChanges.execute(connection, database, plan, expected, options, beforeStep);
                if (!after.precondition().tableExists() || !validator.matches(after)) {
                    throw new SQLException("Schema result differs from the expected definition");
                }
                commitSent = true;
                connection.commit();
                return new ExecutionResult(Outcome.COMMITTED, after);
            } catch (Exception failure) {
                if (commitSent) return new ExecutionResult(Outcome.UNKNOWN, null);
                try {
                    connection.rollback();
                    return new ExecutionResult(Outcome.ROLLED_BACK, null);
                } catch (SQLException uncertain) {
                    return new ExecutionResult(Outcome.UNKNOWN, null);
                }
            }
        }
        boolean started = false;
        try {
            requireDorisLiteralMode(connection, options.timeoutSeconds());
            database.awaitSchemaChange(connection, expected.schemaName(), expected.tableName(), options.timeoutSeconds());
            var current = observe(connection, database, expected.schemaName(), expected.tableName(), options);
            if (!expected.matches(current)) return new ExecutionResult(Outcome.NOT_STARTED, current);
            for (var step : verified.steps()) {
                beforeStep.accept(step.ordinal());
                started = true;
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(options.timeoutSeconds());
                    statement.execute(step.sql());
                }
                database.awaitSchemaChange(connection, expected.schemaName(), expected.tableName(), options.timeoutSeconds());
            }
            var after = observe(connection, database, expected.schemaName(), expected.tableName(), options);
            return new ExecutionResult(after.precondition().tableExists() && validator.matches(after)
                    ? Outcome.COMMITTED : Outcome.UNKNOWN, after);
        } catch (Exception failure) {
            return new ExecutionResult(started ? Outcome.UNKNOWN : Outcome.NOT_STARTED, null);
        }
    }

    /** PostgreSQL 持关系锁；Doris 复核两次原生事实，调用方仍须持有平台持久目标占用。 */
    public static void withGuardedObservation(Connection connection, AbstractDatabase database,
            String schema, String table, MetadataReadOptions options, Consumer<SchemaObservation> callback) throws SQLException {
        if (!connection.getAutoCommit()) throw new IllegalArgumentException("A dedicated auto-commit connection is required");
        if (database.getDatabaseType() == ProductTypeEnum.POSTGRESQL) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                callback.accept(PostgresSchemaChanges.lockAndObserve(connection, database, schema, table, options));
            } finally {
                connection.rollback();
            }
        } else {
            // 旧结构可能仍有异步任务在执行；终态前不得把旧结构判为失败并释放平台目标占用。
            database.awaitSchemaChange(connection, schema, table, options.timeoutSeconds());
            var first = observe(connection, database, schema, table, options);
            var second = observe(connection, database, schema, table, options);
            if (!first.sameState(second)) throw new SQLException("Doris target changed during reconciliation");
            callback.accept(second);
        }
    }

    private static void requireDoris(AbstractDatabase database) throws SQLFeatureNotSupportedException {
        if (database.getDatabaseType() != ProductTypeEnum.DORIS) {
            throw new SQLFeatureNotSupportedException("Schema changes are not implemented for this product");
        }
    }

    private static void requireDorisLiteralMode(Connection connection, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery("SELECT @@sql_mode")) {
                if (!rows.next() || rows.getString(1) == null
                        || Arrays.stream(rows.getString(1).split(",")).anyMatch("NO_BACKSLASH_ESCAPES"::equalsIgnoreCase)
                        || rows.next()) throw new SQLException("Doris literal mode is unverified");
            }
        }
    }

    private record DorisScope(String cluster, String databaseId, String actor, String version) {
    }

    private static DorisScope dorisScope(Connection connection, String schema, int timeout) throws SQLException {
        String cluster = null, version = null;
        boolean connected = false;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(65);
            try (var rows = statement.executeQuery("SHOW FRONTENDS")) {
                int count = 0;
                while (rows.next()) {
                    String id = rows.getString("ClusterId"), observedVersion = rows.getString("Version");
                    if (++count > 64 || id == null || !id.matches("[1-9][0-9]{0,18}")
                            || cluster != null && !cluster.equals(id) || observedVersion == null
                            || !observedVersion.startsWith("doris-4.0.")) throw new SQLException("Doris cluster identity is unverified");
                    cluster = id;
                    if ("Yes".equals(rows.getString("CurrentConnected"))) {
                        if (connected || !rows.getBoolean("Alive") || !rows.getBoolean("Join")) throw new SQLException("Doris FE is not healthy");
                        connected = true;
                        version = observedVersion;
                    }
                }
            }
        }
        if (!connected) throw new SQLException("Doris connected FE identity is missing");
        String actor;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery("SELECT CURRENT_CATALOG(), CURRENT_USER(), @@lower_case_table_names")) {
                if (!rows.next() || !"internal".equals(rows.getString(1)) || rows.getInt(3) != 0 || rows.wasNull()) {
                    throw new SQLException("Doris catalog or case mode is unverified");
                }
                actor = rows.getString(2);
                if (actor == null || actor.isBlank() || rows.next()) throw new SQLException("Doris actor is unavailable");
            }
        }
        String databaseId = null;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(10001);
            try (var rows = statement.executeQuery("SHOW PROC '/dbs'")) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 10000) throw new SQLException("Doris namespace inventory exceeds its bound");
                    if (schema.equals(rows.getString("DbName"))) {
                        if (databaseId != null) throw new SQLException("Doris database identity is ambiguous");
                        databaseId = rows.getString("DbId");
                    }
                }
            }
        }
        if (databaseId == null || !databaseId.matches("[1-9][0-9]{0,18}")) throw new SQLException("Doris database identity is unavailable");
        return new DorisScope(cluster, databaseId, actor, version);
    }

    private static boolean dorisExists(Connection connection, String schema, String table, int timeout) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT TABLE_SCHEMA,TABLE_NAME,TABLE_TYPE,ENGINE FROM internal.information_schema.tables WHERE STRCMP(TABLE_SCHEMA,?)=0 AND STRCMP(TABLE_NAME,?)=0")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return false;
                if (!schema.equals(rows.getString(1)) || !table.equals(rows.getString(2))
                        || !"BASE TABLE".equals(rows.getString(3)) || !"Doris".equals(rows.getString(4)) || rows.next()) {
                    throw new SQLException("Only an exact internal Doris base table can be deployed");
                }
                return true;
            }
        }
    }

    private static String showCreate(Connection connection, String relation, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery("SHOW CREATE TABLE " + relation)) {
                if (!rows.next()) throw new SQLException("Doris table definition is missing");
                String sql = rows.getString(2);
                if (sql == null || sql.length() > 1_048_576 || rows.next()) throw new SQLException("Doris table definition is ambiguous");
                return sql;
            }
        }
    }

    private static String digest(String... values) {
        StringBuilder text = new StringBuilder();
        for (String value : values) text.append(value.length()).append(':').append(value);
        return SqlScriptPlan.digest(text.toString());
    }
}
