package cn.iocoder.yudao.framework.dbswitch.data.diagnostic;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticOptions;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 仅在显式指定的本机测试实例创建随机数据库，不修改现有业务对象。 */
@Timeout(45)
class ConnectionDiagnosticsMysqlTest {
    private static Connection control;
    private static String source, database, user, password, url;

    @BeforeAll
    static void prepare() throws Exception {
        source = System.getenv("DBSWITCH_DIAGNOSTIC_MYSQL_URL");
        Assumptions.assumeTrue(source != null && !source.isBlank(), "需配置本机 MySQL 诊断测试实例");
        assertTrue(source.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/"));
        user = System.getenv("DBSWITCH_DIAGNOSTIC_MYSQL_USER");
        password = System.getenv().getOrDefault("DBSWITCH_DIAGNOSTIC_MYSQL_PASSWORD", "");
        control = DriverManager.getConnection(source + "?useSSL=false&allowPublicKeyRetrieval=true", user, password);
        database = "diagdb_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE DATABASE " + database);
        execute("CREATE TABLE " + database + ".sample(id INTEGER PRIMARY KEY,note VARCHAR(40) COMMENT 'fixture') COMMENT='fixture'");
        execute("INSERT INTO " + database + ".sample VALUES (17,'untouched')");
        url = source + database + "?useSSL=false&allowPublicKeyRetrieval=true";
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (control != null) {
            try (var owned = control) {
                if (database != null) {
                    assertTrue(database.matches("diagdb_[a-f0-9]{32}"));
                    execute("DROP DATABASE IF EXISTS " + database);
                }
            }
        }
    }

    @Test
    void readonlyDiagnosticsActuallyReadCatalogAndPreserveBusinessRows() throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = runtime.diagnoseConnection(ProductTypeEnum.MYSQL, null, url, user, password,
                    new ConnectionDiagnosticOptions(database, false, 5000));
            assertTrue(result.connectionSuccessful(), result.toString());
            for (String key : List.of("DNS", "TCP", "AUTH", "VALIDATION", "SCHEMA", "PRIVILEGES", "TABLES",
                    "COLUMNS", "PRIMARY_KEYS", "EXTENDED_PROPERTIES", "ZERO_ROW_READ", "CLEANUP")) {
                assertEquals("PASS", ConnectionDiagnosticsTest.check(result, key).status(), key + ": " + result);
            }
            assertEquals("NOT_AUTHORIZED", ConnectionDiagnosticsTest.check(result, "TEMP_CREATE").reasonCode());
            assertUnchanged();
            assertEquals(0, runtime.drivers().runtimes().connections().snapshot().metadata());
        }
    }

    @Test
    void authorizedTemporaryWritesUseActualMysqlAffectedRowsAndCleanUp() throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = runtime.diagnoseConnection(ProductTypeEnum.MYSQL, null, url, user, password,
                    new ConnectionDiagnosticOptions(database, true, 5000));
            assertTrue(result.connectionSuccessful(), result.toString());
            for (String key : List.of("TEMP_CREATE", "INSERT", "BATCH_INSERT", "UPDATE", "UPSERT", "DELETE",
                    "TRUNCATE", "TRANSACTION", "ALTER_TEMP", "RENAME_TEMP", "DROP_TEMP", "WRITE_CLEANUP")) {
                assertEquals("PASS", ConnectionDiagnosticsTest.check(result, key).status(), key + ": " + result);
            }
            assertUnchanged();
            assertEquals(0, runtime.drivers().runtimes().connections().snapshot().workload());
        }
    }

    private static void assertUnchanged() throws Exception {
        try (var statement = control.createStatement();
             var rows = statement.executeQuery("SELECT id,note FROM " + database + ".sample")) {
            assertTrue(rows.next());
            assertEquals(17, rows.getInt(1));
            assertEquals("untouched", rows.getString(2));
            assertFalse(rows.next());
        }
    }

    private static void execute(String sql) throws Exception {
        try (var statement = control.createStatement()) {
            statement.execute(sql);
        }
    }
}
