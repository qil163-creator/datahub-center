package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaSynchronizationTest.column;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class SchemaSynchronizationPostgreSqlTest {
    private final String schema = "schema_sync_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach void create() throws Exception { try (var connection = connect()) { sql(connection, "CREATE SCHEMA " + schema); } }
    @AfterEach void drop() throws Exception { try (var connection = connect()) { sql(connection, "DROP SCHEMA IF EXISTS " + schema + " CASCADE"); } }

    @Test void executesCompletePlanRetainsRowsAndSecondPlanIsEmpty() throws Exception {
        try (var connection = connect()) {
            sql(connection, "CREATE TABLE " + schema + ".target (id bigint NOT NULL, seq bigint, label varchar(30) DEFAULT 'old', amount numeric(12,2), obsolete text, CONSTRAINT custom_pk PRIMARY KEY(id))");
            sql(connection, "INSERT INTO " + schema + ".target VALUES (1,2,'old',12.34,'obsolete')");
            var desired = List.of(column("id", "bigint", null, null, null, true, false, null, ""),
                    column("seq", "bigint", null, null, null, false, true, null, ""),
                    column("label", "varchar", 20, null, null, false, false, "'new'", "literal ' comment"),
                    column("amount", "decimal", null, 20, 4, false, false, "0", ""),
                    column("added", "varchar", 20, null, null, true, false, "'a''b; literal'", "added"));
            var database = new DatabasePostgresImpl();
            var plan = SchemaSynchronization.plan(connection, database, schema, "target", "table ' comment", desired);
            assertTrue(plan.ready(), plan.diagnostics().toString());
            assertTrue(connection.getAutoCommit());
            assertTrue(read(connection).columns().stream().anyMatch(column -> "obsolete".equals(column.getFieldName())), "Planning must not execute DDL");
            connection.setAutoCommit(false);
            var result = database.executePlan(connection, SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, String.join(";\n", plan.statements())), List.of(), 1, null, ignored -> { });
            assertTrue(Boolean.TRUE.equals(result.getSuccess()));
            connection.commit();
            var actual = read(connection);
            assertEquals(List.of("seq"), actual.primaryKeys());
            assertFalse(actual.columns().stream().anyMatch(column -> "obsolete".equals(column.getFieldName())));
            assertEquals(4, actual.columns().stream().filter(column -> "amount".equals(column.getFieldName())).findFirst().orElseThrow().getMetadataEvidence().numericScale().value());
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id,seq,label,amount,added FROM " + schema + ".target")) {
                assertTrue(rows.next()); assertEquals(1, rows.getLong(1)); assertEquals(2, rows.getLong(2));
                assertEquals("old", rows.getString(3)); assertEquals("12.3400", rows.getBigDecimal(4).toPlainString()); assertEquals("a'b; literal", rows.getString(5));
            }
            var replay = SchemaSynchronization.plan(connection, database, schema, "target", "table ' comment", desired);
            assertTrue(replay.ready(), replay.diagnostics().toString());
            assertTrue(replay.statements().isEmpty(), replay.statements().toString());
        }
    }

    @Test void failedNotNullIsDatabaseFailureAndTransactionCanRollback() throws Exception {
        try (var connection = connect()) {
            sql(connection, "CREATE TABLE " + schema + ".target (value varchar(20))");
            sql(connection, "INSERT INTO " + schema + ".target VALUES (NULL)");
            var database = new DatabasePostgresImpl();
            var plan = SchemaSynchronization.plan(connection, database, schema, "target", "", List.of(column("value", "varchar", 20, null, null, false, false, null, "")));
            assertTrue(plan.ready());
            connection.setAutoCommit(false);
            assertThrows(java.sql.SQLException.class, () -> sql(connection, plan.statements().getFirst()));
            connection.rollback();
            assertTrue(read(connection).columns().getFirst().isNullable());
        }
    }

    @Test void incomingForeignKeysAreRejectedBeforeSqlExposure() throws Exception {
        try (var connection = connect()) {
            sql(connection, "CREATE TABLE " + schema + ".target(id bigint PRIMARY KEY)");
            sql(connection, "CREATE TABLE " + schema + ".child(id bigint REFERENCES " + schema + ".target(id))");
            var plan = SchemaSynchronization.plan(connection, new DatabasePostgresImpl(), schema, "target", "",
                    List.of(column("id", "bigint", null, null, null, true, false, null, "")));
            assertFalse(plan.ready()); assertTrue(plan.statements().isEmpty());
        }
    }

    @Test void explicitCreateDefaultsAndRequiredTemporalFieldsAlreadyMatchSynchronization() throws Exception {
        try (var connection = connect()) {
            var desired = List.of(column("state", "varchar", 20, null, null, false, false, "pending", "state"),
                    column("created", "date", null, null, null, false, false, "'2000-01-01'", "date"),
                    column("at_time", "timestamp", null, null, null, false, false, "'2000-01-01 12:34:56'", "time"),
                    column("enabled", "boolean", null, null, null, false, false, "TRUE", "flag"));
            var database = new DatabasePostgresImpl();
            var create = SchemaSynchronization.createPlan(database, schema, "target", "created", desired);
            assertTrue(create.ready(), create.diagnostics().toString());
            assertTrue(Boolean.TRUE.equals(database.executePlan(connection, SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL,
                    String.join(";\n", create.statements())), List.of(), 1, null, ignored -> { }).getSuccess()));
            sql(connection, "INSERT INTO " + schema + ".target DEFAULT VALUES");
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT state,created,at_time FROM " + schema + ".target")) {
                assertTrue(rows.next()); assertEquals("pending", rows.getString(1));
                assertEquals("2000-01-01", rows.getDate(2).toString()); assertEquals("2000-01-01 12:34:56.0", rows.getTimestamp(3).toString());
            }
            assertTrue(read(connection).columns().stream().noneMatch(column -> column.isNullable()));
            var replay = SchemaSynchronization.plan(connection, database, schema, "target", "created", desired);
            assertTrue(replay.ready(), replay.diagnostics().toString()); assertTrue(replay.statements().isEmpty(), replay.statements().toString());
        }
    }

    private TableSchemaMetadata read(Connection connection) throws Exception { return NativeSchemaReader.read(connection, ProductTypeEnum.POSTGRESQL, schema, "target", 10); }
    private static void sql(Connection connection, String sql) throws Exception { try (var statement = connection.createStatement()) { statement.setQueryTimeout(10); statement.execute(sql); } }
    private static Connection connect() throws Exception { return DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"), System.getProperty("datahub.test.postgres.user", "dataforge_test"), System.getProperty("datahub.test.postgres.password", "")); }
}
