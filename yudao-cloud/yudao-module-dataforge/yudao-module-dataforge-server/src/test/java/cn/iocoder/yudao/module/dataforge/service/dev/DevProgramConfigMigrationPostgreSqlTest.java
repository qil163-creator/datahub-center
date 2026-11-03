package cn.iocoder.yudao.module.dataforge.service.dev;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 PostgreSQL 验证程序配置迁移、重放与 JSON 对象边界，历史任务不补造配置。 */
@Testcontainers(disabledWithoutDocker = true)
class DevProgramConfigMigrationPostgreSqlTest {

    private static final String MIGRATION = "sql/postgresql/migrations/0121_dataforge_dev-program-config.sql";
    private static final String BASELINE = "sql/postgresql/datahub-center.sql";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migrationPreservesLegacyNullAcceptsObjectsRejectsOtherJsonAndIsRepeatable() throws Exception {
        verifyDelta(Files.readString(root().resolve(MIGRATION)));
    }

    @Test
    void newDatabaseBaselineContainsTheSameProgramConfigContract() throws Exception {
        String baseline = Files.readString(root().resolve(BASELINE));
        int highest;
        try (var files = Files.list(root().resolve("sql/postgresql/migrations"))) {
            highest = files.map(path -> path.getFileName().toString()).filter(name -> name.matches("[0-9]{4}_.*\\.sql"))
                    .mapToInt(name -> Integer.parseInt(name.substring(0, 4))).max().orElseThrow();
        }
        assertTrue(baseline.startsWith("-- Included migrations through: " + String.format("%04d", highest)));
        int start = baseline.indexOf("-- 新库同步增量：0121_dataforge_dev-program-config.sql");
        assertTrue(start >= 0, "新库基线必须包含程序配置增量");
        // 只验证本次新增的基线结构，完整平台基线初始化由平台安装验收覆盖。
        int end = baseline.indexOf("-- 新库同步增量：", start + 1);
        verifyDelta(baseline.substring(start, end < 0 ? baseline.length() : end));
    }

    private static void verifyDelta(String migration) throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
            sql.execute("CREATE SCHEMA \"datahub-center\"");
            sql.execute("CREATE TABLE \"datahub-center\".dh_dataforge_dev_task (id bigint PRIMARY KEY, content text)");
            sql.execute("INSERT INTO \"datahub-center\".dh_dataforge_dev_task VALUES (1,'legacy script')");
            sql.execute(migration);

            assertEquals("jsonb", one(sql, "SELECT data_type FROM information_schema.columns"
                    + " WHERE table_schema='datahub-center' AND table_name='dh_dataforge_dev_task' AND column_name='program_config_json'"));
            assertEquals("YES", one(sql, "SELECT is_nullable FROM information_schema.columns"
                    + " WHERE table_schema='datahub-center' AND table_name='dh_dataforge_dev_task' AND column_name='program_config_json'"));
            assertEquals("1", one(sql, "SELECT count(*) FROM \"datahub-center\".dh_dataforge_dev_task WHERE id=1 AND program_config_json IS NULL AND content='legacy script'"));
            sql.execute("INSERT INTO \"datahub-center\".dh_dataforge_dev_task(id,program_config_json) VALUES (2,'{}'),(3,'{\"schemaVersion\":1,\"engine\":\"FLINK\"}'),(4,NULL)");
            // 数据库仅保证容器形状；未来结构版本交由业务入口显式拒绝，不能在数据库中伪装成当前版本。
            sql.execute("INSERT INTO \"datahub-center\".dh_dataforge_dev_task(id,program_config_json) VALUES (5,'{\"schemaVersion\":2}')");
            for (String invalid : new String[]{"[]", "[{}]", "null", "1", "true", "\"text\""}) {
                SQLException error = assertThrows(SQLException.class, () -> sql.execute(
                        "INSERT INTO \"datahub-center\".dh_dataforge_dev_task(id,program_config_json) VALUES (99,'" + invalid + "')"), invalid);
                assertEquals("23514", error.getSQLState(), "必须由检查约束拒绝 " + invalid);
            }

            sql.execute(migration);
            assertEquals("5", one(sql, "SELECT count(*) FROM \"datahub-center\".dh_dataforge_dev_task"));
            assertEquals("FLINK", one(sql, "SELECT program_config_json->>'engine' FROM \"datahub-center\".dh_dataforge_dev_task WHERE id=3"));
            assertEquals("1", one(sql, "SELECT count(*) FROM pg_constraint WHERE conname='ck_dev_task_program_config' AND conrelid='\"datahub-center\".dh_dataforge_dev_task'::regclass"));
            assertTrue(one(sql, "SELECT col_description('\"datahub-center\".dh_dataforge_dev_task'::regclass,attnum) FROM pg_attribute"
                    + " WHERE attrelid='\"datahub-center\".dh_dataforge_dev_task'::regclass AND attname='program_config_json'").contains("版本化程序配置"));
        }
    }

    private static String one(Statement sql, String query) throws SQLException {
        try (var result = sql.executeQuery(query)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static Path root() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve(MIGRATION))) current = current.getParent();
        assertNotNull(current, "缺少仓库迁移 " + MIGRATION);
        return current;
    }
}
