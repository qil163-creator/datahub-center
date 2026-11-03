package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 通过真实 PostgreSQL 验证命名空间、注释、CDC 和隔离临时写入。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/dbswitch_test.*")
class PostgresDiagnosticNativeTest {
    private final DatabasePostgresImpl database = new DatabasePostgresImpl();

    private Connection open() throws SQLException {
        return DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", "postgres"),
                System.getProperty("datahub.test.postgres.password", ""));
    }

    @Test
    void cdcNeedsLogicalWalAndNonzeroSlotAndSenderCapacity() throws Exception {
        try (var connection = open(); var statement = connection.createStatement()) {
            var result = database.probeCdcReadiness(connection, 2);
            try (var rows = statement.executeQuery("SELECT current_setting('wal_level'),"
                    + "current_setting('max_replication_slots')::int,current_setting('max_wal_senders')::int")) {
                assertTrue(rows.next());
                assertEquals("logical".equals(rows.getString(1)) && rows.getInt(2) > 0 && rows.getInt(3) > 0,
                        result.logConfigurationReady());
            }
            assertTrue(result.accountPrivilegeReady());
        }
    }

    @Test
    void namespaceDenialIsNotConfusedWithCatalogVisibility() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String schema = "diag_" + suffix;
        String role = "diag_reader_" + suffix;
        try (var connection = open(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("CREATE ROLE " + role);
            try {
                database.probeNamespace(connection, schema, 2);
                database.probeAccountPrivileges(connection, schema, 2);
                statement.execute("SET ROLE " + role);
                SQLException denied = assertThrows(SQLException.class, () -> database.probeNamespace(connection, schema, 2));
                assertEquals("42501", denied.getSQLState());
                assertThrows(SQLException.class, () -> database.probeAccountPrivileges(connection, schema, 2));
                statement.execute("RESET ROLE");
                assertEquals("3F000", assertThrows(SQLException.class,
                        () -> database.probeNamespace(connection, "missing_" + suffix, 2)).getSQLState());
            } finally {
                statement.execute("RESET ROLE");
                statement.execute("DROP SCHEMA " + schema);
                statement.execute("DROP ROLE " + role);
            }
        }
    }

    @Test
    void exactRelationCommentsAndTemporaryDdlAreExecuted() throws Exception {
        String name = "dbswitch_diag_" + UUID.randomUUID().toString().replace("-", "");
        var plan = database.temporaryTableDiagnosticPlan(name);
        try (var connection = open(); var statement = connection.createStatement()) {
            statement.execute(plan.createSql());
            try {
                statement.execute("COMMENT ON TABLE " + plan.tableReference() + " IS 'table'");
                statement.execute("COMMENT ON COLUMN " + plan.tableReference() + ".probe_id IS 'id'");
                String namespace;
                try (var rows = statement.executeQuery("SELECT nspname FROM pg_namespace WHERE oid=pg_my_temp_schema()")) {
                    assertTrue(rows.next()); namespace = rows.getString(1);
                }
                var result = database.probeExtendedProperties(connection, namespace, name, 2);
                assertEquals(2, result.columnsRead()); assertTrue(result.tableCommentPresent());
                assertEquals(1, result.columnCommentsPresent());
                statement.execute("INSERT INTO " + plan.tableReference() + " VALUES (4,'initial')");
                assertEquals(1, statement.executeUpdate(plan.upsertSql()));
                statement.execute("INSERT INTO " + plan.tableReference() + " VALUES (5,'initial')");
                assertEquals(2, statement.executeUpdate(plan.mergeSql()));
                statement.execute(plan.truncateSql());
                connection.setAutoCommit(false);
                statement.execute("INSERT INTO " + plan.tableReference() + " VALUES (9,'rollback')");
                connection.rollback(); connection.setAutoCommit(true);
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + plan.tableReference())) {
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
                }
                statement.execute(plan.alterSql());
                statement.execute(plan.renameSql());
                statement.executeQuery("SELECT probe_extra FROM " + plan.renamedTableReference()).close();
            } finally {
                statement.execute(plan.dropSql()); statement.execute(plan.dropRenamedSql());
            }
        }
    }
}
