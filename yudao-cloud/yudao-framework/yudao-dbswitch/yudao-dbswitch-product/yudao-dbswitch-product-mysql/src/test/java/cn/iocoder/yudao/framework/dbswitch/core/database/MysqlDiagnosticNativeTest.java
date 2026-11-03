package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MysqlDiagnosticNativeTest {
    private final DatabaseMysqlImpl database = new DatabaseMysqlImpl();

    private Connection open() throws SQLException {
        return DriverManager.getConnection(System.getProperty("datahub.test.mysql.url"),
                System.getProperty("datahub.test.mysql.user", "root"),
                System.getProperty("datahub.test.mysql.password", ""));
    }

    @Test
    void compatibilitySubclassDoesNotInheritUnverifiedDiagnostics() {
        var compatible = new DatabaseMysqlImpl() {
            @Override public ProductTypeEnum getDatabaseType() { return ProductTypeEnum.TIDB; }
        };
        assertThrows(SQLFeatureNotSupportedException.class, () -> compatible.probeAccountPrivileges(null, "x", 2));
        assertThrows(SQLFeatureNotSupportedException.class, () -> compatible.probeExtendedProperties(null, "x", "y", 2));
        assertThrows(SQLFeatureNotSupportedException.class, () -> compatible.probeCdcReadiness(null, 2));
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> compatible.temporaryTableDiagnosticPlan("dbswitch_diag_" + "a".repeat(32)));
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/dbswitch_test.*")
    void accountAndNamespaceChecksActuallyReachServer() throws Exception {
        try (var connection = open()) {
            database.probeNamespace(connection, "dbswitch_test", 2);
            database.probeAccountPrivileges(connection, "dbswitch_test", 2);
            assertThrows(SQLException.class, () -> database.probeNamespace(connection, "missing_diagnostic_database", 2));
            assertThrows(SQLException.class, () -> database.probeAccountPrivileges(connection, "missing_diagnostic_database", 2));
            assertFalse(connection.isClosed());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/dbswitch_test.*")
    void extendedCommentsAreReadFromExactRelation() throws Exception {
        String table = "diagnostic_comments_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = open(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE `" + table + "` (id INTEGER COMMENT 'identifier', value VARCHAR(40)) COMMENT='table'");
            try {
                var result = database.probeExtendedProperties(connection, "dbswitch_test", table, 2);
                assertEquals(2, result.columnsRead());
                assertEquals(1, result.columnCommentsPresent());
                assertTrue(result.tableCommentPresent());
                assertThrows(SQLException.class,
                        () -> database.probeExtendedProperties(connection, "dbswitch_test", table + "' OR 1=1 --", 2));
            } finally {
                statement.execute("DROP TABLE `" + table + "`");
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/dbswitch_test.*")
    void cdcReportsActualBinlogAndDirectReplicationPrivileges() throws Exception {
        try (var connection = open(); var statement = connection.createStatement()) {
            var diagnostic = database.probeCdcReadiness(connection, 2);
            try (var rows = statement.executeQuery("SELECT @@GLOBAL.log_bin,@@GLOBAL.binlog_format,@@GLOBAL.binlog_row_image")) {
                assertTrue(rows.next());
                assertEquals(rows.getBoolean(1) && "ROW".equals(rows.getString(2)) && "FULL".equals(rows.getString(3)),
                        diagnostic.logConfigurationReady());
            }
            assertTrue(diagnostic.accountPrivilegeReady());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/dbswitch_test.*")
    void temporaryPlanIsSessionScopedAndAllSuppliedStatementsExecute() throws Exception {
        String name = "dbswitch_diag_" + UUID.randomUUID().toString().replace("-", "");
        var plan = database.temporaryTableDiagnosticPlan(name);
        assertTrue(plan.dropSql().startsWith("DROP TEMPORARY TABLE IF EXISTS "));
        assertNull(plan.mergeSql());
        try (var connection = open(); var other = open(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + plan.tableReference() + " (sentinel INTEGER)");
            statement.execute(plan.createSql());
            statement.execute("INSERT INTO " + plan.tableReference() + " VALUES (4,'initial')");
            assertEquals(2, statement.executeUpdate(plan.upsertSql()));
            try (var rows = statement.executeQuery("SELECT probe_value FROM " + plan.tableReference())) {
                assertTrue(rows.next()); assertEquals("upserted", rows.getString(1));
            }
            assertThrows(SQLException.class, () -> {
                try (var second = other.createStatement()) { second.executeQuery("SELECT probe_id FROM " + plan.tableReference()); }
            });
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
            statement.execute(plan.dropSql());
            statement.execute(plan.dropRenamedSql());
            statement.execute(plan.dropRenamedSql());
            statement.executeQuery("SELECT sentinel FROM " + plan.tableReference()).close();
            statement.execute("DROP TABLE " + plan.tableReference());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/dbswitch_test.*")
    void readOnlyAccountDoesNotBecomeReplicationAuthorizedOrGainDdlRights() throws Exception {
        String account = "diag_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        try (var admin = open(); var statement = admin.createStatement()) {
            statement.execute("CREATE USER '" + account + "'@'%'");
            try {
                statement.execute("GRANT SELECT ON dbswitch_test.* TO '" + account + "'@'%'");
                try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.mysql.url"), account, "")) {
                    database.probeNamespace(connection, "dbswitch_test", 2);
                    database.probeAccountPrivileges(connection, "dbswitch_test", 2);
                    assertFalse(database.probeCdcReadiness(connection, 2).accountPrivilegeReady());
                    assertThrows(SQLException.class, () -> database.probeNamespace(connection, "mysql", 2));
                    var plan = database.temporaryTableDiagnosticPlan("dbswitch_diag_" + "a".repeat(32));
                    assertThrows(SQLException.class, () -> {
                        try (var limited = connection.createStatement()) { limited.execute(plan.createSql()); }
                    });
                }
            } finally {
                statement.execute("DROP USER '" + account + "'@'%'");
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/dbswitch_test.*")
    void serverExpandedActiveRolePrivilegesAreVerifiedFromActualGrantRows() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String account = "diag_" + suffix;
        String role = "diag_role_" + suffix;
        try (var admin = open(); var statement = admin.createStatement()) {
            statement.execute("CREATE USER '" + account + "'@'%'");
            statement.execute("CREATE ROLE '" + role + "'");
            try {
                statement.execute("GRANT SELECT ON dbswitch_test.* TO '" + account + "'@'%'");
                statement.execute("GRANT REPLICATION SLAVE,REPLICATION CLIENT ON *.* TO '" + role + "'");
                statement.execute("GRANT '" + role + "' TO '" + account + "'@'%'");
                statement.execute("SET DEFAULT ROLE '" + role + "' TO '" + account + "'@'%'");
                try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.mysql.url"), account, "")) {
                    assertTrue(database.probeCdcReadiness(connection, 2).accountPrivilegeReady());
                }
            } finally {
                statement.execute("DROP USER '" + account + "'@'%'");
                statement.execute("DROP ROLE '" + role + "'");
            }
        }
    }

    @Test
    void invalidNamesAndDeadlinesAreRejectedBeforeSql() {
        assertThrows(IllegalArgumentException.class, () -> database.temporaryTableDiagnosticPlan("orders"));
        assertThrows(IllegalArgumentException.class, () -> database.probeNamespace(null, "dbswitch_test", 0));
        assertThrows(IllegalArgumentException.class, () -> database.probeExtendedProperties(null, "x", "y", 0));
    }
}
