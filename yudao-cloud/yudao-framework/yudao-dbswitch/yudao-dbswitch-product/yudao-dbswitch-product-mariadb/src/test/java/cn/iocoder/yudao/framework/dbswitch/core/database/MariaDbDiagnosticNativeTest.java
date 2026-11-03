package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMariaDBImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** MariaDB 必须通过自己的驱动和引擎验证，不能由 MySQL 协议兼容性推断。 */
@EnabledIfSystemProperty(named = "datahub.test.mariadb.url", matches = "jdbc:mariadb://127[.]0[.]0[.]1:[0-9]+/dbswitch_test.*")
class MariaDbDiagnosticNativeTest {
    private final DatabaseMariaDBImpl database = new DatabaseMariaDBImpl();

    private Connection open() throws SQLException {
        return DriverManager.getConnection(System.getProperty("datahub.test.mariadb.url"),
                System.getProperty("datahub.test.mariadb.user", "root"),
                System.getProperty("datahub.test.mariadb.password", ""));
    }

    @Test
    void namespaceAndAccountChecksUseNativeServerAuthorization() throws Exception {
        try (var connection = open()) {
            assertTrue(connection.getMetaData().getDatabaseProductName().contains("MariaDB"));
            database.probeNamespace(connection, "dbswitch_test", 2);
            database.probeAccountPrivileges(connection, "dbswitch_test", 2);
            assertThrows(SQLException.class, () -> database.probeNamespace(connection, "missing_database", 2));
        }
    }

    @Test
    void commentsAreReadFromExactMariaDbRelation() throws Exception {
        String name = "diagnostic_comments_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = open(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE `" + name + "` (id INTEGER COMMENT 'identifier', value VARCHAR(40)) COMMENT='table'");
            try {
                var result = database.probeExtendedProperties(connection, "dbswitch_test", name, 2);
                assertEquals(2, result.columnsRead()); assertTrue(result.tableCommentPresent());
                assertEquals(1, result.columnCommentsPresent());
                assertThrows(SQLException.class,
                        () -> database.probeExtendedProperties(connection, "dbswitch_test", name + "' OR 1=1 --", 2));
            } finally {
                statement.execute("DROP TABLE `" + name + "`");
            }
        }
    }

    @Test
    void cdcObservesActualMariaDbBinlogAndNativePrivileges() throws Exception {
        try (var connection = open(); var statement = connection.createStatement()) {
            var result = database.probeCdcReadiness(connection, 2);
            try (var rows = statement.executeQuery("SELECT @@GLOBAL.log_bin,@@GLOBAL.binlog_format,@@GLOBAL.binlog_row_image")) {
                assertTrue(rows.next());
                assertEquals(rows.getBoolean(1) && "ROW".equals(rows.getString(2)) && "FULL".equals(rows.getString(3)),
                        result.logConfigurationReady());
            }
            assertTrue(result.accountPrivilegeReady());
        }
    }

    @Test
    void nativeReplicationPermissionNamesAndReadOnlyAccountAreDistinguished() throws Exception {
        String account = "diag_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        try (var admin = open(); var statement = admin.createStatement()) {
            statement.execute("CREATE USER '" + account + "'@'%'");
            try {
                statement.execute("GRANT SELECT ON dbswitch_test.* TO '" + account + "'@'%'");
                try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.mariadb.url"), account, "")) {
                    database.probeAccountPrivileges(connection, "dbswitch_test", 2);
                    assertFalse(database.probeCdcReadiness(connection, 2).accountPrivilegeReady());
                    assertThrows(SQLException.class, () -> database.probeNamespace(connection, "mysql", 2));
                }
                statement.execute("GRANT REPLICATION SLAVE,BINLOG MONITOR ON *.* TO '" + account + "'@'%'");
                try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.mariadb.url"), account, "")) {
                    assertTrue(database.probeCdcReadiness(connection, 2).accountPrivilegeReady());
                }
            } finally {
                statement.execute("DROP USER '" + account + "'@'%'");
            }
        }
    }

    @Test
    void nativeTemporaryPlanSupportsRollbackAlterRenameAndSafeCleanup() throws Exception {
        String name = "dbswitch_diag_" + UUID.randomUUID().toString().replace("-", "");
        var plan = database.temporaryTableDiagnosticPlan(name);
        try (var connection = open(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE `" + name + "` (sentinel INTEGER)");
            try {
                statement.execute(plan.createSql());
                statement.execute("INSERT INTO " + plan.tableReference() + " VALUES (4,'initial')");
                assertEquals(2, statement.executeUpdate(plan.upsertSql()));
                try (var rows = statement.executeQuery("SELECT probe_value FROM " + plan.tableReference())) {
                    assertTrue(rows.next()); assertEquals("upserted", rows.getString(1));
                }
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
                statement.execute(plan.dropSql());
                statement.executeQuery("SELECT sentinel FROM `" + name + "`").close();
            } finally {
                statement.execute("DROP TEMPORARY TABLE IF EXISTS `" + name + "`");
                statement.execute("DROP TEMPORARY TABLE IF EXISTS `" + name + "_renamed`");
                statement.execute("DROP TABLE `" + name + "`");
            }
        }
    }
}
