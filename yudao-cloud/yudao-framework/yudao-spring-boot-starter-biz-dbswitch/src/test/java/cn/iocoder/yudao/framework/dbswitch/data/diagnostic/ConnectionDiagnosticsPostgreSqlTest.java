package cn.iocoder.yudao.framework.dbswitch.data.diagnostic;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticOptions;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 仅在显式提供的本机测试服务中创建隔离数据库和随机角色；不改原库权限。 */
@Timeout(45)
class ConnectionDiagnosticsPostgreSqlTest {
    private static Connection bootstrap, control;
    private static String url, user, password, databaseName, role, schema;

    @BeforeAll static void prepare() throws Exception {
        String source = System.getenv("DBSWITCH_DIAGNOSTIC_PG_URL");
        Assumptions.assumeTrue(source != null && !source.isBlank(), "需显式配置 DBSWITCH_DIAGNOSTIC_PG_URL 本机测试实例");
        assertTrue(source.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[A-Za-z0-9_]+"), "仅接受显式 loopback 测试实例，不接受额外路由参数");
        user = System.getenv("DBSWITCH_DIAGNOSTIC_PG_USER");
        password = System.getenv().getOrDefault("DBSWITCH_DIAGNOSTIC_PG_PASSWORD", "");
        bootstrap = DriverManager.getConnection(source, user, password);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        databaseName = "diagdb_" + suffix; role = "diagrole_" + suffix; schema = "diagscope_" + suffix;
        sql(bootstrap, "CREATE ROLE " + role + " LOGIN");
        sql(bootstrap, "CREATE DATABASE " + databaseName);
        url = source.substring(0, source.lastIndexOf('/') + 1) + databaseName;
        control = DriverManager.getConnection(url, user, password);
        sql(control, "CREATE SCHEMA " + schema);
        sql(control, "CREATE TABLE " + schema + ".sample(id INTEGER PRIMARY KEY,note TEXT)");
        sql(control, "INSERT INTO " + schema + ".sample VALUES (17,'untouched fixture')");
        sql(control, "COMMENT ON TABLE " + schema + ".sample IS 'private table comment fixture'");
        sql(control, "COMMENT ON COLUMN " + schema + ".sample.note IS 'private column comment fixture'");
        sql(control, "CREATE SCHEMA hidden_scope");
        sql(control, "GRANT USAGE ON SCHEMA " + schema + " TO " + role);
        sql(control, "GRANT SELECT ON " + schema + ".sample TO " + role);
        sql(control, "REVOKE TEMPORARY ON DATABASE " + databaseName + " FROM PUBLIC");
    }

    @AfterAll static void cleanup() throws Exception {
        if (control != null) control.close();
        if (bootstrap != null) try (var owned = bootstrap) {
            if (databaseName != null) {
                assertTrue(databaseName.matches("diagdb_[a-f0-9]{32}"));
                sql(bootstrap, "DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
            }
            if (role != null) { assertTrue(role.matches("diagrole_[a-f0-9]{32}")); sql(bootstrap, "DROP ROLE IF EXISTS " + role); }
        }
    }

    @Test void readonlyAndQuotedCatalogSamplePreserveBusinessRows() throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = runtime.diagnoseConnection(ProductTypeEnum.POSTGRESQL, null, url, user, password,
                    new ConnectionDiagnosticOptions(schema, false, 5000));
            assertTrue(result.connectionSuccessful(), result.toString());
            for (String key : List.of("CONFIG", "DNS", "TCP", "AUTH", "VALIDATION", "SCHEMA", "PRIVILEGES", "TABLES", "COLUMNS", "PRIMARY_KEYS", "EXTENDED_PROPERTIES", "ZERO_ROW_READ", "CLEANUP")) {
                assertEquals("PASS", check(result, key).status(), key + ": " + result);
            }
            assertTrue(check(result, "EXTENDED_PROPERTIES").message().contains("表注释已设置，字段注释 1 项"));
            assertFalse(result.toString().contains("private table comment fixture"));
            assertFalse(result.toString().contains("private column comment fixture"));
            assertCdcObservation(result, true);
            assertEquals("SKIPPED", check(result, "TEMP_CREATE").status());
            assertBudgetReleased(runtime); assertUnchanged();
        }
    }

    @Test void explicitTemporaryWritesExecuteBatchUpdatesRollbackAlterAndDropOnOwnSession() throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = runtime.diagnoseConnection(ProductTypeEnum.POSTGRESQL, null, url, user, password,
                    new ConnectionDiagnosticOptions(schema, true, 5000));
            assertTrue(result.connectionSuccessful(), result.toString());
            for (String key : List.of("TEMP_CREATE", "INSERT", "BATCH_INSERT", "UPDATE", "UPSERT", "MERGE", "DELETE", "TRUNCATE", "TRANSACTION", "ALTER_TEMP", "RENAME_TEMP", "DROP_TEMP", "WRITE_CLEANUP")) {
                assertEquals("PASS", check(result, key).status(), key + ": " + result);
            }
            assertBudgetReleased(runtime); assertUnchanged();
            try (var statement = control.createStatement(); var rows = statement.executeQuery(
                    "SELECT count(*) FROM pg_catalog.pg_class WHERE relname LIKE 'dbswitch_diag_%'")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test void visibleSchemaWithoutUsageIsRejectedAndNeverStartsWrites() {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = runtime.diagnoseConnection(ProductTypeEnum.POSTGRESQL, null, url, role, "",
                    new ConnectionDiagnosticOptions("hidden_scope", true, 5000));
            assertFalse(result.connectionSuccessful()); assertEquals("PASS", check(result, "AUTH").status());
            assertEquals("FAIL", check(result, "SCHEMA").status());
            assertEquals("42501", check(result, "SCHEMA").sqlState());
            assertEquals("FAIL", check(result, "PRIVILEGES").status());
            assertEquals("WARN", check(result, "CDC").status());
            assertEquals("SKIPPED", check(result, "TEMP_CREATE").status()); assertBudgetReleased(runtime);
        }
    }

    @Test void temporaryPrivilegeFailureDoesNotEraseSuccessfulReadonlyConnection() {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = runtime.diagnoseConnection(ProductTypeEnum.POSTGRESQL, null, url, role, "",
                    new ConnectionDiagnosticOptions(schema, true, 5000));
            assertTrue(result.connectionSuccessful(), result.toString()); assertEquals("PASS", check(result, "ZERO_ROW_READ").status());
            assertEquals("FAIL", check(result, "TEMP_CREATE").status());
            assertEquals("42501", check(result, "TEMP_CREATE").sqlState());
            assertEquals("SKIPPED", check(result, "INSERT").status()); assertBudgetReleased(runtime);
            assertEquals("PASS", check(result, "PRIVILEGES").status());
            assertEquals("WARN", check(result, "CDC").status());
        }
    }

    @Test void keylessTableAndMissingCommentsAreSuccessfulMetadataReads() throws Exception {
        String keylessSchema = "diagkeyless_" + UUID.randomUUID().toString().replace("-", "");
        sql(control, "CREATE SCHEMA " + keylessSchema);
        try (var runtime = DbSwitchRuntime.standalone()) {
            sql(control, "CREATE TABLE " + keylessSchema + ".sample(id integer,note text)");
            var result = runtime.diagnoseConnection(ProductTypeEnum.POSTGRESQL, null, url, user, password,
                    new ConnectionDiagnosticOptions(keylessSchema, false, 5000));
            assertEquals("PASS", check(result, "PRIMARY_KEYS").status());
            assertTrue(check(result, "PRIMARY_KEYS").message().contains("未声明主键"));
            assertEquals("PASS", check(result, "COLUMNS").status());
            assertEquals("PASS", check(result, "EXTENDED_PROPERTIES").status());
            assertTrue(check(result, "EXTENDED_PROPERTIES").message().contains("表注释未设置，字段注释 0 项"));
            assertBudgetReleased(runtime);
        } finally {
            assertTrue(keylessSchema.matches("diagkeyless_[a-f0-9]{32}")); sql(control, "DROP SCHEMA " + keylessSchema + " CASCADE");
        }
    }

    private static ConnectionDiagnosticResult.Check check(ConnectionDiagnosticResult result, String key) {
        return ConnectionDiagnosticsTest.check(result, key);
    }
    private static void assertBudgetReleased(DbSwitchRuntime runtime) {
        var snapshot = runtime.drivers().runtimes().connections().snapshot();
        assertEquals(0, snapshot.metadata()); assertEquals(0, snapshot.workload());
        assertEquals(0, snapshot.active()); assertEquals(0, snapshot.quarantined());
    }
    private static void assertUnchanged() throws SQLException {
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SELECT id,note FROM " + schema + ".sample")) {
            assertTrue(rows.next()); assertEquals(17, rows.getInt(1)); assertEquals("untouched fixture", rows.getString(2)); assertFalse(rows.next());
        }
    }
    private static void assertCdcObservation(ConnectionDiagnosticResult result, boolean privileged) throws SQLException {
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SHOW wal_level")) {
            assertTrue(rows.next());
            assertEquals("logical".equals(rows.getString(1)) && privileged ? "PASS" : "WARN", check(result, "CDC").status());
        }
    }
    private static void sql(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.setQueryTimeout(5); statement.execute(sql); }
    }
}
