package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Real embedded SQLite execution, independent of PostgreSQL/H2 and native vendor promises. */
@Timeout(20)
class MigrationHookSqliteTest {
    @TempDir Path directory;

    @Test void newHookPlanExecutesBorrowedConnectionDdlDmlAndExactLiterals() throws Exception {
        try (var drivers = fixture(); var connection = open(drivers)) {
            String value = "left;?@:# and 'quotes' 中文";
            var plan = SqlScriptPlan.parseMigrationHook(ProductTypeEnum.SQLITE3,
                    "CREATE TABLE \"fixture;table\"(id INTEGER PRIMARY KEY,note TEXT);"
                            + "INSERT INTO \"fixture;table\" VALUES(1,'left;?@:# and ''quotes'' 中文');"
                            + "CREATE INDEX \"fixture;index\" ON \"fixture;table\"(note);"
                            + "ALTER TABLE \"fixture;table\" ADD extra INTEGER;"
                            + "UPDATE \"fixture;table\" SET extra=2 WHERE id=1;"
                            + "SELECT note,extra FROM \"fixture;table\";");
            var result = DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.SQLITE3)
                    .executePlan(connection, plan, List.of(), 10, null, ignored -> { });
            assertTrue(result.getSuccess(), result::getErrorMsg);
            assertFalse(connection.isClosed());
            assertTrue(connection.getAutoCommit());
            assertEquals(value, result.getResults().getLast().getRowData().getFirst().get("note"));
            assertEquals("2", String.valueOf(result.getResults().getLast().getRowData().getFirst().get("extra")));
        }
    }

    @Test void rejectedWholeInputDoesNotCreateTheFirstTable() throws Exception {
        try (var drivers = fixture(); var connection = open(drivers)) {
            assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parseMigrationHook(ProductTypeEnum.SQLITE3,
                    "CREATE TABLE fixture(id INT); INSERT INTO fixture VALUES(1); COMMIT"));
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT count(*) FROM sqlite_master WHERE name='fixture'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    private VerifiedDriverTestHandle fixture() {
        String supplied = System.getProperty("datahub.test.sqlite.driver");
        return supplied == null ? null : new VerifiedDriverTestHandle(Path.of(supplied), ProductTypeEnum.SQLITE3);
    }

    private Connection open(VerifiedDriverTestHandle drivers) throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("migration-hook.db");
        return drivers == null ? DriverManager.getConnection(url)
                : drivers.dataSource(url, "org.sqlite.JDBC", null, null, new Properties()).getConnection();
    }
}
