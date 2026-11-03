package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
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
@EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class SchemaSynchronizationMySqlTest {
    private final String table = "schema_sync_" + UUID.randomUUID().toString().replace("-", "");

    @AfterEach void drop() throws Exception { try (var connection = connect()) { sql(connection, "DROP TABLE IF EXISTS `" + table + "`"); } }

    @Test void executesCompletePlanAndPreservesMysqlCharacterSemantics() throws Exception {
        try (var connection = connect()) {
            String schema = connection.getCatalog();
            sql(connection, "CREATE TABLE `" + table + "` (id bigint NOT NULL PRIMARY KEY, seq bigint, label varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT 'old', amount decimal(12,2), obsolete text) ENGINE=InnoDB");
            sql(connection, "INSERT INTO `" + table + "` VALUES(1,2,'old',12.34,'obsolete')");
            var desired = List.of(column("id", "bigint", null, null, null, true, false, null, ""),
                    column("seq", "bigint", null, null, null, false, true, null, ""),
                    column("label", "varchar", 20, null, null, false, false, "'new'", "literal ' comment"),
                    column("amount", "decimal", null, 20, 4, false, false, "0", ""),
                    column("added", "varchar", 20, null, null, true, false, "'a''b; literal'", "added"));
            var database = new DatabaseMysqlImpl();
            var plan = SchemaSynchronization.plan(connection, database, schema, table, "table ' comment", desired);
            assertTrue(plan.ready(), plan.diagnostics().toString());
            assertTrue(connection.getAutoCommit());
            var result = database.executePlan(connection, SqlScriptPlan.parse(ProductTypeEnum.MYSQL, String.join(";\n", plan.statements())), List.of(), 1, null, ignored -> { });
            assertTrue(Boolean.TRUE.equals(result.getSuccess()));
            var actual = NativeSchemaReader.read(connection, ProductTypeEnum.MYSQL, schema, table, 10);
            assertEquals(List.of("seq"), actual.primaryKeys());
            var label = actual.columns().stream().filter(column -> "label".equals(column.getFieldName())).findFirst().orElseThrow();
            assertEquals("utf8mb4_bin", label.getMetadataEvidence().collation().value());
            assertEquals("literal ' comment", label.getRemarks());
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id,label,amount,added FROM `" + table + "`")) {
                assertTrue(rows.next()); assertEquals(1, rows.getLong(1)); assertEquals("old", rows.getString(2));
                assertEquals("12.3400", rows.getBigDecimal(3).toPlainString()); assertEquals("a'b; literal", rows.getString(4));
            }
            var replay = SchemaSynchronization.plan(connection, database, schema, table, "table ' comment", desired);
            assertTrue(replay.ready(), replay.diagnostics().toString());
            assertTrue(replay.statements().isEmpty(), replay.statements().toString());
        }
    }

    @Test void generatedColumnsCannotBeSilentlyLost() throws Exception {
        try (var connection = connect()) {
            sql(connection, "CREATE TABLE `" + table + "`(id bigint, derived bigint GENERATED ALWAYS AS(id+1) STORED) ENGINE=InnoDB");
            var plan = SchemaSynchronization.plan(connection, new DatabaseMysqlImpl(), connection.getCatalog(), table, "",
                    List.of(column("id", "bigint", null, null, null, true, false, null, "")));
            assertFalse(plan.ready()); assertTrue(plan.statements().isEmpty());
        }
    }

    @Test void caseOnlyColumnAndPrimaryKeySpellingsDoNotChangeMysqlSchema() throws Exception {
        try (var connection = connect()) {
            sql(connection, "CREATE TABLE `" + table + "`(id bigint NOT NULL PRIMARY KEY, label varchar(20)) ENGINE=InnoDB");
            sql(connection, "INSERT INTO `" + table + "` VALUES(1,'retained')");
            var plan = SchemaSynchronization.plan(connection, new DatabaseMysqlImpl(), connection.getCatalog(), table, "",
                    List.of(column("ID", "bigint", null, null, null, false, true, null, ""),
                            column("LABEL", "varchar", 20, null, null, true, false, null, "")));
            assertTrue(plan.ready(), plan.diagnostics().toString());
            assertTrue(plan.statements().isEmpty(), plan.statements().toString());
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id,label FROM `" + table + "`")) {
                assertTrue(rows.next()); assertEquals(1, rows.getLong(1)); assertEquals("retained", rows.getString(2));
            }
        }
    }

    @Test void onUpdateSemanticsCannotBeSilentlyLost() throws Exception {
        try (var connection = connect()) {
            sql(connection, "CREATE TABLE `" + table + "`(changed timestamp DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB");
            var plan = SchemaSynchronization.plan(connection, new DatabaseMysqlImpl(), connection.getCatalog(), table, "",
                    List.of(column("changed", "timestamp", null, null, null, true, false, "CURRENT_TIMESTAMP", "")));
            assertFalse(plan.ready()); assertTrue(plan.statements().isEmpty());
        }
    }

    @Test void explicitCreatePreservesLiteralDatesAndNotNullWithoutASecondAlter() throws Exception {
        try (var connection = connect()) {
            var desired = List.of(column("state", "varchar", 20, null, null, false, false, "pending", "state"),
                    column("created", "date", null, null, null, false, false, "'2000-01-01'", "date"),
                    column("at_time", "timestamp", null, null, null, false, false, "'2000-01-01 12:34:56'", "time"),
                    column("enabled", "boolean", null, null, null, false, false, "TRUE", "flag"));
            var database = new DatabaseMysqlImpl();
            String schema = connection.getCatalog();
            var create = SchemaSynchronization.createPlan(database, schema, table, "created", desired);
            assertTrue(create.ready(), create.diagnostics().toString());
            assertTrue(Boolean.TRUE.equals(database.executePlan(connection, SqlScriptPlan.parse(ProductTypeEnum.MYSQL,
                    String.join(";\n", create.statements())), List.of(), 1, null, ignored -> { }).getSuccess()));
            sql(connection, "INSERT INTO `" + table + "` () VALUES ()");
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT state,created,at_time FROM `" + table + "`")) {
                assertTrue(rows.next()); assertEquals("pending", rows.getString(1));
                assertEquals("2000-01-01", rows.getDate(2).toString()); assertEquals("2000-01-01 12:34:56.0", rows.getTimestamp(3).toString());
            }
            var actual = NativeSchemaReader.read(connection, ProductTypeEnum.MYSQL, schema, table, 10);
            assertTrue(actual.columns().stream().noneMatch(column -> column.isNullable()));
            var replay = SchemaSynchronization.plan(connection, database, schema, table, "created", desired);
            assertTrue(replay.ready(), replay.diagnostics().toString()); assertTrue(replay.statements().isEmpty(), replay.statements().toString());
        }
    }

    private static void sql(Connection connection, String sql) throws Exception { try (var statement = connection.createStatement()) { statement.setQueryTimeout(10); statement.execute(sql); } }
    private static Connection connect() throws Exception { return DriverManager.getConnection(System.getProperty("datahub.test.mysql.url"), System.getProperty("datahub.test.mysql.user", "root"), System.getProperty("datahub.test.mysql.password", "")); }
}
