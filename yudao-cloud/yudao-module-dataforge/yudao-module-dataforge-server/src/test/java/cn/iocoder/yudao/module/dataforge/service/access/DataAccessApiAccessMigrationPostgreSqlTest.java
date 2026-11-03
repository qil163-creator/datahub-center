package cn.iocoder.yudao.module.dataforge.service.access;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真实 PostgreSQL 验证接口采集可空源库，以及离线同步仍禁止空源库。 */
@Testcontainers(disabledWithoutDocker = true)
class DataAccessApiAccessMigrationPostgreSqlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migrationAllowsApiAccessNullSourceAndKeepsOfflineRequired() throws Exception {
        String migration = Files.readString(migrationFile());
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA \"datahub-center\"");
            sql.execute("""
                    CREATE TABLE "datahub-center".dh_dataforge_access (
                      id int8 PRIMARY KEY,
                      task_name varchar(128) NOT NULL,
                      engine_type int2 NOT NULL DEFAULT 1,
                      source_database_id int8 NOT NULL,
                      target_database_id int8,
                      deleted int2 NOT NULL DEFAULT 0
                    )
                    """);
            assertThrows(SQLException.class, () -> sql.execute("""
                    INSERT INTO "datahub-center".dh_dataforge_access (id, task_name, engine_type, target_database_id)
                    VALUES (1, '测试接口', 3, 1)
                    """));
            sql.execute(migration);
            sql.execute(migration);
            sql.execute("""
                    INSERT INTO "datahub-center".dh_dataforge_access (id, task_name, engine_type, target_database_id)
                    VALUES (1, '测试接口', 3, 1)
                    """);
            sql.execute("""
                    INSERT INTO "datahub-center".dh_dataforge_access (id, task_name, engine_type, target_database_id)
                    VALUES (2, '实时同步', 2, 1)
                    """);
            sql.execute("""
                    INSERT INTO "datahub-center".dh_dataforge_access
                      (id, task_name, engine_type, source_database_id, target_database_id)
                    VALUES (3, '离线同步', 1, 10, 1)
                    """);
            assertThrows(SQLException.class, () -> sql.execute("""
                    INSERT INTO "datahub-center".dh_dataforge_access (id, task_name, engine_type, target_database_id)
                    VALUES (4, '离线缺源', 1, 1)
                    """));
            try (ResultSet nullable = sql.executeQuery("""
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_schema = 'datahub-center'
                      AND table_name = 'dh_dataforge_access'
                      AND column_name = 'source_database_id'
                    """)) {
                assertTrue(nullable.next());
                assertEquals("YES", nullable.getString(1));
            }
            try (ResultSet constraint = sql.executeQuery("""
                    SELECT count(*) FROM pg_constraint
                    WHERE conname = 'chk_dh_dataforge_access_source_database_id'
                    """)) {
                assertTrue(constraint.next());
                assertEquals(1, constraint.getInt(1));
            }
        }
    }

    private static Path migrationFile() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "sql/postgresql/migrations/0033_dataforge_access-source-database-nullable.sql");
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("0033 dataforge access migration not found");
    }
}
