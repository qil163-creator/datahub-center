package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.schema.DdlPlan;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaDiff;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sqlite-jdbc 3.31.1 file database. {@code ALTER TABLE ... DROP COLUMN} is a syntax error,
 * so nullable ADD COLUMN reverse SQL stays blank. This does not mark DDL_ALTER verified
 * and does not emit a table-rebuild substitute.
 */
@Timeout(60)
class Sqlite3DropColumnUnsupportedTest {
    private static final String SQLITE_ERROR =
            "[SQLITE_ERROR] SQL error or missing database (near \"DROP\": syntax error)";
    private static final String SOURCE_ID =
            "2020-01-27 19:55:54 3bfa9cc97da10598521b342961df8f5f68c7388fa117345eeb516eaa837balt2";

    private final DatabaseSqliteImpl database = new DatabaseSqliteImpl();

    @TempDir Path directory;

    @BeforeAll
    static void driver() throws ClassNotFoundException {
        Class.forName("org.sqlite.JDBC");
    }

    @Test
    void fileDatabaseRefusesDropColumnAndLeavesTheAddedColumn() throws Exception {
        ColumnDescription note = column("note");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("drop.db").toAbsolutePath())) {
            var meta = connection.getMetaData();
            assertEquals("SQLite JDBC", meta.getDriverName());
            assertEquals("3.31.1", meta.getDriverVersion());
            assertEquals("3.31.1", meta.getDatabaseProductVersion());
            assertEquals("3.31.1", scalar(connection, "select sqlite_version()"));
            assertEquals(SOURCE_ID, scalar(connection, "select sqlite_source_id()"));

            exec(connection, "CREATE TABLE \"items\"(\"id\" INTEGER)");
            exec(connection, "INSERT INTO \"items\"(\"id\") VALUES (1)");
            exec(connection, database.getAddColumnSql("main", "items", note));
            assertEquals(List.of("id:INTEGER", "note:TEXT"), columns(connection));
            assertNull(scalar(connection, "select \"note\" from \"items\""));

            assertDropRefused(connection, "ALTER TABLE \"main\".\"items\" DROP COLUMN \"note\"");
            assertDropRefused(connection, "ALTER TABLE items DROP COLUMN note");
            assertDropRefused(connection, "ALTER TABLE \"items\" DROP COLUMN \"note\"");
            assertDropRefused(connection, "ALTER TABLE main.items DROP COLUMN note");

            assertEquals(List.of("id:INTEGER", "note:TEXT"), columns(connection));
            assertNull(scalar(connection, "select \"note\" from \"items\""));
        }
    }

    @Test
    void nullableAddReverseStaysBlankAndAlterIsNotVerified() throws Exception {
        MetadataProvenance provenance = new MetadataProvenance(
                ProductTypeEnum.SQLITE3, "SQLite", "3.31.1", "SQLITE_CATALOG_V1", Instant.EPOCH);
        ColumnDescription id = new ColumnDescription();
        id.setFieldName("id");
        id.setNullable(false);
        TableSchemaMetadata actual = new TableSchemaMetadata(List.of(id), List.of("id"), provenance,
                MetadataValue.value(true), MetadataValue.absent());

        for (String name : List.of("note", "a\"b")) {
            ColumnDescription column = column(name);
            DdlPlan plan = new SchemaDiff().planAddColumns(database, "main", "items", List.of(column), actual);
            assertTrue(plan.ready(), String.valueOf(plan.diagnostics()));
            assertEquals(1, plan.steps().size());
            DdlPlan.Step step = plan.steps().getFirst();
            assertEquals(DdlPlan.Kind.ADD_COLUMN, step.kind());
            assertEquals(database.getAddColumnSql("main", "items", column), step.sql());
            assertEquals("", step.reverseSql());
            assertFalse(step.sql().toUpperCase(Locale.ROOT).contains("DROP"));
            assertFalse(step.sql().toUpperCase(Locale.ROOT).contains("CREATE TABLE"));
            assertFalse(step.reverseSql().toUpperCase(Locale.ROOT).contains("DROP"));
        }

        var sqlite = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.SQLITE3).findFirst().orElseThrow();
        OperationCapability alter = sqlite.capabilities().get(DatabaseOperation.DDL_ALTER);
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                "SQLITE3_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"), alter);
        assertNotEquals(OperationCapability.Status.VERIFIED, alter.status());
        try (var input = getClass().getClassLoader().getResourceAsStream("META-INF/dbswitch/products/sqlite3.json")) {
            assertTrue(input != null);
            var manifest = new ObjectMapper().readTree(input);
            assertEquals("DECLARED", manifest.path("operations").path("DDL_ALTER").path("status").asText());
            assertNotEquals("VERIFIED", manifest.path("operations").path("DDL_ALTER").path("status").asText());
            assertEquals("SQLITE3_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED",
                    manifest.path("operations").path("DDL_ALTER").path("reason").asText());
        }
    }

    private static void assertDropRefused(Connection connection, String sql) {
        SQLException failure = assertThrows(SQLException.class, () -> exec(connection, sql));
        assertEquals("org.sqlite.SQLiteException", failure.getClass().getName());
        assertEquals(1, failure.getErrorCode());
        assertNull(failure.getSQLState());
        assertEquals(SQLITE_ERROR, failure.getMessage());
    }

    private static void exec(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static List<String> columns(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA table_info(\"items\")")) {
            while (rows.next()) {
                names.add(rows.getString("name") + ":" + rows.getString("type"));
            }
        }
        return names;
    }

    private static ColumnDescription column(String name) {
        ColumnDescription column = new ColumnDescription();
        column.setFieldName(name);
        column.setDbType(ProductTypeEnum.SQLITE3);
        column.setValType(ColumnMetaData.TYPE_STRING);
        column.setLength(20);
        column.setPrecision(0);
        column.setNullable(true);
        return column;
    }
}
