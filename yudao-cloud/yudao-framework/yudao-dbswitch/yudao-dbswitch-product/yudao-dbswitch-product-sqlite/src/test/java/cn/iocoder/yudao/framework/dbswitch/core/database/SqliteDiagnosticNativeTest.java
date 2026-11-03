package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 通过真实 SQLite 引擎验证诊断，不以其他兼容数据库替代。 */
class SqliteDiagnosticNativeTest {
    private final DatabaseSqliteImpl database = new DatabaseSqliteImpl();

    @Test
    void readsMainAndAttachedNamespaceWithoutChangingBusyTimeout() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=1234");
            statement.execute("ATTACH DATABASE ':memory:' AS archive");
            database.probeNamespace(connection, "main", 2);
            database.probeNamespace(connection, "archive", 2);
            try (var rows = statement.executeQuery("PRAGMA busy_timeout")) {
                assertTrue(rows.next());
                assertEquals(1234, rows.getInt(1));
            }
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test
    void diagnosticCatalogIsBoundedAndKeepsAttachedDatabaseIdentity() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=1234");
            statement.execute("CREATE TABLE main.business(id INTEGER)");
            statement.execute("ATTACH DATABASE ':memory:' AS archive");
            statement.execute("CREATE TABLE archive.sample(id INTEGER)");
            var tables = database.queryCatalogTableList(connection, "archive", 1, 2);
            assertEquals(1, tables.size());
            assertEquals("archive", tables.getFirst().getSchemaName());
            assertEquals("sample", tables.getFirst().getTableName());
            assertEquals("TABLE", tables.getFirst().getTableType());
            statement.execute("CREATE TABLE archive.second(id INTEGER)");
            assertThrows(IllegalArgumentException.class,
                    () -> database.queryCatalogTableList(connection, "archive", 1, 2));
            assertThrows(SQLException.class,
                    () -> database.queryCatalogTableList(connection, "missing", 10, 2));
            try (var rows = statement.executeQuery("PRAGMA busy_timeout")) {
                assertTrue(rows.next());
                assertEquals(1234, rows.getInt(1));
            }
        }
    }

    @Test
    void missingNamespaceCannotFallBackToMain() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            SQLException failure = assertThrows(SQLException.class,
                    () -> database.probeNamespace(connection, "missing", 2));
            assertEquals("3F000", failure.getSQLState());
        }
    }

    @Test
    void maliciousAndUnboundedNamespaceInputsAreRejected() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            assertThrows(IllegalArgumentException.class, () -> database.probeNamespace(connection, "main", 0));
            assertThrows(IllegalArgumentException.class, () -> database.probeNamespace(connection, "", 2));
            assertThrows(SQLException.class, () -> database.probeNamespace(connection, "main\"; ATTACH 'x' AS x; --", 2));
            assertFalse(connection.isClosed());
        }
    }

    @Test
    void unsupportedCapabilitiesExplainNativeProductLimitations() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            for (var failure : new SQLFeatureNotSupportedException[] {
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> database.probeAccountPrivileges(connection, "main", 2)),
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> database.probeExtendedProperties(connection, "main", "orders", 2)),
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> database.probeCdcReadiness(connection, 2))}) {
                assertEquals("0A000", failure.getSQLState());
                assertTrue(failure.getMessage().contains("SQLite"));
                assertFalse(failure.getMessage().contains("not verified"));
            }
        }
    }

    @Test
    void temporaryPlanExecutesAndCannotTouchSameNamedMainTable() throws Exception {
        String name = "dbswitch_diag_" + UUID.randomUUID().toString().replace("-", "");
        var plan = database.temporaryTableDiagnosticPlan(name);
        assertNull(plan.mergeSql());
        assertNull(plan.truncateSql());
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE main.\"" + name + "\" (sentinel INTEGER)");
            statement.execute("INSERT INTO main.\"" + name + "\" VALUES (42)");
            statement.execute(plan.createSql());
            statement.execute("INSERT INTO " + plan.tableReference() + " VALUES (4,'initial')");
            assertEquals(1, statement.executeUpdate(plan.upsertSql()));
            try (var rows = statement.executeQuery("SELECT probe_value FROM " + plan.tableReference())) {
                assertTrue(rows.next());
                assertEquals("upserted", rows.getString(1));
            }
            connection.setAutoCommit(false);
            statement.execute("INSERT INTO " + plan.tableReference() + " VALUES (9,'rollback')");
            connection.rollback();
            connection.setAutoCommit(true);
            statement.execute(plan.alterSql());
            statement.execute(plan.renameSql());
            try (var rows = statement.executeQuery("SELECT probe_extra FROM " + plan.renamedTableReference())) {
                assertTrue(rows.next());
            }
            statement.execute(plan.dropSql());
            statement.execute(plan.dropRenamedSql());
            statement.execute(plan.dropRenamedSql());
            try (var rows = statement.executeQuery("SELECT sentinel FROM main.\"" + name + "\"")) {
                assertTrue(rows.next());
                assertEquals(42, rows.getInt(1));
            }
        }
    }

    @Test
    void temporaryPlanRejectsAnyNameNotGeneratedByComponent() {
        for (String name : new String[] {"orders", "dbswitch_diag_abc", "dbswitch_diag_" + "a".repeat(32) + ";"}) {
            assertThrows(IllegalArgumentException.class, () -> database.temporaryTableDiagnosticPlan(name));
        }
    }
}
