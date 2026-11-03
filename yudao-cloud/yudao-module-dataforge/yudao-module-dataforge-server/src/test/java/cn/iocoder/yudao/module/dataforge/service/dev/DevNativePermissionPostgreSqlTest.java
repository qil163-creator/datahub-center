package cn.iocoder.yudao.module.dataforge.service.dev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/** 使用专用 PostgreSQL 测试库验证原生执行权限；禁止指向业务数据库。 */
@EnabledIfEnvironmentVariable(named = "NATIVE_ENGINE_TEST_PG_URL", matches = ".+")
class DevNativePermissionPostgreSqlTest {
    private static final String MIGRATION = "0147_dataforge_native-engine-execution.sql";

    @Test
    void registersIndependentPermissionWithoutGrantingRolesAndReplaysSafely() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            fixture(sql, true);
            String migration = Files.readString(root().resolve("sql/postgresql/migrations/" + MIGRATION));
            sql.execute(migration);
            assertEquals(1, count(sql, "SELECT count(*) FROM \"datahub-center\".system_menu "
                    + "WHERE permission='dataforge:dev-task:execute-native' AND deleted=0 AND parent_id=100 AND type=3"));
            assertEquals(1, count(sql, "SELECT count(*) FROM \"datahub-center\".system_role_menu WHERE menu_id=101 AND role_id=200"));
            assertEquals(0, count(sql, "SELECT count(*) FROM \"datahub-center\".system_role_menu r "
                    + "JOIN \"datahub-center\".system_menu m ON m.id=r.menu_id WHERE m.permission='dataforge:dev-task:execute-native'"));
            sql.execute(migration);
            assertEquals(1, count(sql, "SELECT count(*) FROM \"datahub-center\".system_menu "
                    + "WHERE permission='dataforge:dev-task:execute-native' AND deleted=0"));
            sql.execute("UPDATE \"datahub-center\".system_menu SET name='运维自定义名称' WHERE permission='dataforge:dev-task:execute-native'");
            sql.execute(migration);
            assertEquals(1, count(sql, "SELECT count(*) FROM \"datahub-center\".system_menu "
                    + "WHERE permission='dataforge:dev-task:execute-native' AND name='运维自定义名称'"));
        }
    }

    @Test
    void missingDataDevelopmentMenuAbortsInsteadOfInventingAnEntryOrGrant() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            fixture(sql, false);
            String migration = Files.readString(root().resolve("sql/postgresql/migrations/" + MIGRATION));
            assertThrows(java.sql.SQLException.class, () -> sql.execute(migration));
            sql.execute("ROLLBACK");
            assertEquals(0, count(sql, "SELECT count(*) FROM \"datahub-center\".system_menu WHERE permission='dataforge:dev-task:execute-native'"));
            assertEquals(1, count(sql, "SELECT count(*) FROM \"datahub-center\".system_role_menu WHERE menu_id=101 AND role_id=200"));
        }
    }

    @Test
    void freshDatabaseBaselineContainsTheExactNativePermissionIncrement() throws Exception {
        Path root = root();
        String delta = Files.readString(root.resolve("sql/postgresql/migrations/" + MIGRATION));
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        assertTrue(baseline.contains("-- 新库同步增量：" + MIGRATION + "\n" + delta));
    }

    private static Connection connection() throws Exception {
        String url = System.getenv("NATIVE_ENGINE_TEST_PG_URL");
        assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:") || url.startsWith("jdbc:postgresql://localhost:"),
                "只能使用本地隔离 PostgreSQL 测试实例");
        assertTrue(url.matches(".*/codex_dev_native_engine(?:\\?.*)?"), "测试库名必须是 codex_dev_native_engine");
        return DriverManager.getConnection(url,
                System.getenv().getOrDefault("NATIVE_ENGINE_TEST_PG_USER", "codex_dev"),
                System.getenv().getOrDefault("NATIVE_ENGINE_TEST_PG_PASSWORD", ""));
    }

    private static void fixture(Statement sql, boolean withPage) throws Exception {
        sql.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
        sql.execute("CREATE SCHEMA \"datahub-center\"");
        sql.execute("CREATE SEQUENCE \"datahub-center\".system_menu_id_seq");
        sql.execute("CREATE TABLE \"datahub-center\".system_menu (id bigint PRIMARY KEY DEFAULT nextval('\"datahub-center\".system_menu_id_seq'), "
                + "name varchar(50), permission varchar(100) DEFAULT '', type smallint, sort int, parent_id bigint, component varchar(255), "
                + "status smallint DEFAULT 0, creator varchar(64), updater varchar(64), deleted smallint DEFAULT 0)");
        sql.execute("CREATE TABLE \"datahub-center\".system_role_menu (role_id bigint, menu_id bigint)");
        sql.execute("INSERT INTO \"datahub-center\".system_menu(id,name,permission,type) VALUES (101,'普通运行','dataforge:dev-task:run',3)");
        sql.execute("INSERT INTO \"datahub-center\".system_role_menu VALUES (200,101)");
        if (withPage) sql.execute("INSERT INTO \"datahub-center\".system_menu(id,name,type,component) VALUES (100,'数据开发',2,'/dataforge/dev/index/')");
    }

    private static int count(Statement sql, String query) throws Exception {
        try (var result = sql.executeQuery(query)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static Path root() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("sql/postgresql/migrations/" + MIGRATION))) current = current.getParent();
        assertNotNull(current);
        return current;
    }
}
