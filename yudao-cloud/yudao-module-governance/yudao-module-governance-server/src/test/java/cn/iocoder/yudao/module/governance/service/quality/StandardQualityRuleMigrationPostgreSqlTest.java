package cn.iocoder.yudao.module.governance.service.quality;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class StandardQualityRuleMigrationPostgreSqlTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migrationShouldReplayAndEnforceStandardConstraintUniqueness() throws Exception {
        try (Connection connection = openConnection()) {
            resetLegacyRuleTable(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute(migrationSql());
                statement.execute(migrationSql());
            }

            assertThat(scalarLong(connection, """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'datahub-center'
                      AND table_name = 'dh_governance_quality_rule'
                      AND column_name IN ('standard_element_id', 'element_version', 'constraint_hash')
                    """)).isEqualTo(3);

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO "datahub-center".dh_governance_quality_rule
                          (id, rule_name, quality_dim, check_level, rule_type, tenant_id,
                           standard_element_id, element_version, constraint_hash)
                        VALUES (1, '性别-码值', 'ACCURACY', 'FIELD', 'ENUM', 1, 12, 1, repeat('a', 64))
                        """);
                statement.executeUpdate("""
                        INSERT INTO "datahub-center".dh_governance_quality_rule
                          (id, rule_name, quality_dim, check_level, rule_type, tenant_id,
                           standard_element_id, element_version, constraint_hash)
                        VALUES (2, '性别-长度', 'NORMALIZATION', 'FIELD', 'LENGTH', 1, 12, 1, repeat('b', 64))
                        """);
                statement.executeUpdate("""
                        INSERT INTO "datahub-center".dh_governance_quality_rule
                          (id, rule_name, quality_dim, check_level, rule_type, tenant_id,
                           standard_element_id, element_version, constraint_hash)
                        VALUES (3, '租户2同哈希', 'ACCURACY', 'FIELD', 'ENUM', 2, 12, 1, repeat('a', 64))
                        """);
                assertThatThrownBy(() -> statement.executeUpdate("""
                        INSERT INTO "datahub-center".dh_governance_quality_rule
                          (id, rule_name, quality_dim, check_level, rule_type, tenant_id,
                           standard_element_id, element_version, constraint_hash)
                        VALUES (4, '重复', 'ACCURACY', 'FIELD', 'ENUM', 1, 12, 2, repeat('a', 64))
                        """)).isInstanceOf(SQLException.class);
                statement.executeUpdate("""
                        INSERT INTO "datahub-center".dh_governance_quality_rule
                          (id, rule_name, quality_dim, check_level, rule_type, tenant_id)
                        VALUES (5, '手工规则', 'ACCURACY', 'FIELD', 'ENUM', 1)
                        """);
                statement.executeUpdate("""
                        INSERT INTO "datahub-center".dh_governance_quality_rule
                          (id, rule_name, quality_dim, check_level, rule_type, tenant_id)
                        VALUES (6, '另一条手工规则', 'ACCURACY', 'FIELD', 'ENUM', 1)
                        """);
            }
        }
    }

    @Test
    void baselineCreateTableShouldIncludeStandardProvenanceColumns() throws Exception {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
            statement.execute("CREATE SCHEMA \"datahub-center\"");
            statement.execute("""
                    CREATE SEQUENCE "datahub-center"."dh_governance_quality_rule_seq"
                    """);
            String snapshot = Files.readString(repoFile("sql/postgresql/datahub-center.sql"));
            statement.execute(statementStartingWith(snapshot,
                    "CREATE TABLE \"datahub-center\".\"dh_governance_quality_rule\""));
            assertThat(scalarLong(connection, """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'datahub-center'
                      AND table_name = 'dh_governance_quality_rule'
                      AND column_name IN ('standard_element_id', 'element_version', 'constraint_hash')
                    """)).isEqualTo(3);
        }
    }

    private static void resetLegacyRuleTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
            statement.execute("CREATE SCHEMA \"datahub-center\"");
            statement.execute("""
                    CREATE SEQUENCE "datahub-center".dh_governance_quality_rule_seq
                    """);
            statement.execute("""
                    CREATE TABLE "datahub-center".dh_governance_quality_rule (
                      id int8 NOT NULL DEFAULT nextval('"datahub-center".dh_governance_quality_rule_seq'::regclass),
                      rule_name varchar(128) NOT NULL,
                      quality_dim varchar(32) NOT NULL,
                      check_level varchar(32) NOT NULL DEFAULT 'FIELD',
                      rule_type varchar(32) NOT NULL,
                      deleted int2 NOT NULL DEFAULT 0,
                      tenant_id int8 NOT NULL DEFAULT 0
                    )
                    """);
        }
    }

    private static String migrationSql() throws Exception {
        return Files.readString(repoFile("sql/postgresql/migrations/0014_governance_standard-quality-rule.sql"));
    }

    private static Path repoFile(String relative) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path file = current.resolve(relative);
            if (Files.exists(file)) {
                return file;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository file not found: " + relative);
    }

    private static String statementStartingWith(String sql, String prefix) {
        int start = sql.indexOf(prefix);
        if (start < 0) {
            throw new IllegalStateException("SQL not found: " + prefix);
        }
        int end = sql.indexOf(';', start);
        if (end < 0) {
            throw new IllegalStateException("SQL terminator missing: " + prefix);
        }
        return sql.substring(start, end + 1);
    }

    private static Connection openConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long scalarLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
