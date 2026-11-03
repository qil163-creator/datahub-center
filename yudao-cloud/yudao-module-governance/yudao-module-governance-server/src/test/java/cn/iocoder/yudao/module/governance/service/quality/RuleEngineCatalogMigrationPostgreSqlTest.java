package cn.iocoder.yudao.module.governance.service.quality;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.*;
import java.sql.*;
import java.util.regex.*;

import static org.junit.jupiter.api.Assertions.*;

/** 使用基线原始目录及 0190 已生效文案验证 0198 升级，避免拿新迁移种子自证。 */
@Testcontainers(disabledWithoutDocker = true)
class RuleEngineCatalogMigrationPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void replaysAndPreservesCustomRulesParametersAndExistingManualDisable() throws Exception {
        try (Connection connection = connection()) {
            fixture(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("UPDATE \"datahub-center\".dh_governance_audit_rule SET status=1 WHERE code='107'");
                statement.execute("UPDATE \"datahub-center\".dh_governance_clean_rule SET status=1 WHERE code='001'");
                for (String table : new String[]{"audit", "clean"}) statement.execute("INSERT INTO \"datahub-center\".dh_governance_" + table
                        + "_rule(code,name,strategy_key,rule_level,status,configuration) VALUES ('CUSTOM','用户规则','CUSTOM_ENGINE',1,1,'{\"keep\":true}')");
                statement.execute(migration());
                statement.execute(migration());
            }
            assertEquals(28, scalar(connection, "SELECT count(*) FROM \"datahub-center\".dh_governance_audit_rule WHERE code<>'CUSTOM' AND nullif(strategy_key,'') IS NOT NULL"));
            assertEquals(40, scalar(connection, "SELECT count(*) FROM \"datahub-center\".dh_governance_clean_rule WHERE code<>'CUSTOM' AND nullif(strategy_key,'') IS NOT NULL"));
            assertEquals(1, scalar(connection, "SELECT status FROM \"datahub-center\".dh_governance_audit_rule WHERE code='107'"));
            assertEquals(1, scalar(connection, "SELECT status FROM \"datahub-center\".dh_governance_clean_rule WHERE code='001'"));
            assertEquals(0, scalar(connection, "SELECT status FROM \"datahub-center\".dh_governance_audit_rule WHERE code='113'"));
            assertEquals(0, scalar(connection, "SELECT status FROM \"datahub-center\".dh_governance_clean_rule WHERE code='003'"));
            for (String table : new String[]{"audit", "clean"}) {
                assertEquals(1, scalar(connection, "SELECT count(*) FROM \"datahub-center\".dh_governance_" + table + "_rule WHERE code='CUSTOM' AND name='用户规则' AND strategy_key='CUSTOM_ENGINE' AND status=1 AND configuration='{\"keep\":true}'::jsonb AND updater='original'"));
                assertEquals(0, scalar(connection, "SELECT count(*) FROM \"datahub-center\".dh_governance_" + table + "_rule WHERE code<>'CUSTOM' AND configuration<>'{\"frozen\":true}'::jsonb"));
            }
            assertEquals(2, scalar(connection, "SELECT rule_level FROM \"datahub-center\".dh_governance_audit_rule WHERE code='206'"));
        }
    }

    @Test
    void builtinOwnershipConflictRejectsWholeMigrationWithoutEnablingOtherRows() throws Exception {
        try (Connection connection = connection()) {
            fixture(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("UPDATE \"datahub-center\".dh_governance_audit_rule SET strategy_key='USER_OVERRIDE' WHERE code='109'");
                assertThrows(SQLException.class, () -> statement.execute(migration()));
                statement.execute("ROLLBACK");
            }
            assertEquals(1, scalar(connection, "SELECT count(*) FROM \"datahub-center\".dh_governance_audit_rule WHERE code='109' AND strategy_key='USER_OVERRIDE'"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM \"datahub-center\".dh_governance_clean_rule WHERE code='003' AND strategy_key IS NULL AND status=1"));
        }
    }

    private static void fixture(Connection connection) throws Exception {
        String baseline = Files.readString(repoFile("sql/postgresql/datahub-center.sql"));
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE"); statement.execute("CREATE SCHEMA \"datahub-center\"");
            for (String kind : new String[]{"audit", "clean"}) {
                String table = "dh_governance_" + kind + "_rule";
                statement.execute("CREATE TABLE \"datahub-center\"." + table + "(code text,name text,strategy_key text,rule_level smallint,status smallint,deleted smallint DEFAULT 0,updater text DEFAULT 'original',update_time timestamp DEFAULT CURRENT_TIMESTAMP,configuration jsonb DEFAULT '{\"frozen\":true}')");
                Pattern pattern = Pattern.compile("INSERT INTO \"datahub-center\"\\.\"" + table + "\" VALUES \\(\\d+, '((?:[^']|'')*)', \\d+, (\\d+), '([^']+)', (NULL|'(?:[^']|'')*')");
                Matcher matcher = pattern.matcher(baseline); int inserted = 0;
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO \"datahub-center\"." + table + "(code,name,strategy_key,rule_level,status) VALUES (?,?,?,?,?)")) {
                    while (matcher.find()) {
                        String strategy = matcher.group(4); strategy = "NULL".equals(strategy) ? null : strategy.substring(1, strategy.length() - 1).replace("''", "'");
                        insert.setString(1, matcher.group(3)); insert.setString(2, matcher.group(1).replace("''", "'")); insert.setString(3, strategy);
                        insert.setInt(4, Integer.parseInt(matcher.group(2))); insert.setInt(5, strategy == null ? 1 : 0); insert.executeUpdate(); inserted++;
                    }
                }
                assertEquals("audit".equals(kind) ? 28 : 40, inserted, "基线目录必须完整");
            }
            statement.execute("UPDATE \"datahub-center\".dh_governance_audit_rule SET name='数值字段精度校验' WHERE code='110' AND name='数值精度校验'");
        }
    }
    private static String migration() throws Exception { return Files.readString(repoFile("sql/postgresql/migrations/0198_governance_complete-rule-engines.sql")); }
    private static Path repoFile(String name) {
        for (Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath(); path != null; path = path.getParent()) if (Files.exists(path.resolve(name))) return path.resolve(name);
        throw new IllegalStateException("Repository file missing: " + name);
    }
    private static Connection connection() throws Exception { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private static long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) { assertTrue(result.next()); return result.getLong(1); }
    }
}
