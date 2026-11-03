package cn.iocoder.yudao.module.supply.service.dataapi;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplyMigrationPostgreSqlTest extends cn.iocoder.yudao.module.supply.service.app.SharingPostgresSupport {
    @Override protected boolean useLegacyFixture() { return false; }


    @Test
    void t36ReplayAndHalfOpenBackfillAndActiveGrantUniqueness() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(legacySchema());
            statement.executeUpdate("""
                    INSERT INTO dh_supply_app_api
                      (id, app_id, api_id, forever_flag, start_date, end_date, deleted, tenant_id)
                    VALUES (1, 1, 2, 0, DATE '2026-09-16', DATE '2026-09-16', 0, 1)
                    """);
            statement.execute(migrationSql());
            statement.execute(migrationSql());

            assertThat(scalarLong(connection, """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'datahub-center' AND table_name = 'dh_supply_api_release'
                      AND column_name = 'contract_hash'
                    """)).isEqualTo(1);
            assertThat(scalarLong(connection, """
                    SELECT count(*) FROM pg_indexes
                    WHERE schemaname = 'datahub-center'
                      AND indexname IN ('uk_dh_supply_app_api_active', 'uk_dh_supply_grant_op',
                                        'uk_dh_supply_api_release_rev')
                    """)).isEqualTo(3);

            LocalDateTime validTo = scalarTimestamp(connection,
                    "SELECT valid_to FROM dh_supply_app_api WHERE id = 1");
            assertThat(validTo).isEqualTo(LocalDateTime.of(2026, 9, 17, 0, 0));

            statement.executeUpdate("""
                    INSERT INTO dh_supply_app_api
                      (id, app_id, api_id, forever_flag, grant_status, deleted, tenant_id)
                    VALUES (2, 1, 3, 1, 1, 0, 1)
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO dh_supply_app_api
                      (id, app_id, api_id, forever_flag, grant_status, deleted, tenant_id)
                    VALUES (3, 1, 3, 1, 1, 0, 1)
                    """))
                    .isInstanceOf(SQLException.class);
            statement.executeUpdate("""
                    UPDATE dh_supply_app_api SET grant_status = 3 WHERE id = 2
                    """);
            statement.executeUpdate("""
                    INSERT INTO dh_supply_app_api
                      (id, app_id, api_id, forever_flag, grant_status, grant_revision, deleted, tenant_id)
                    VALUES (4, 1, 3, 1, 1, 2, 0, 1)
                    """);
            assertThat(scalarLong(connection,
                    "SELECT count(*) FROM dh_supply_app_api WHERE app_id = 1 AND api_id = 3 AND deleted = 0"))
                    .isEqualTo(2);
        }
    }

    private static String migrationSql() throws Exception {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path[] candidates = {
                cwd.resolve("sql/postgresql/migrations/0029_supply_service-lifecycle.sql"),
                cwd.resolve("../../sql/postgresql/migrations/0029_supply_service-lifecycle.sql"),
                cwd.getParent().getParent().resolve("sql/postgresql/migrations/0029_supply_service-lifecycle.sql")
        };
        for (Path path : candidates) {
            if (Files.exists(path)) {
                return Files.readString(path);
            }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(false, "Historical 0029 migration is no longer published; current runtime uses the platform baseline");
        return "";
    }

    private static String legacySchema() {
        return """
                CREATE TABLE IF NOT EXISTS dh_supply_app (
                  id int8 PRIMARY KEY,
                  name varchar(100) NOT NULL,
                  app_type varchar(20) NOT NULL DEFAULT 'Web',
                  app_key varchar(64) NOT NULL,
                  secret varchar(64) NOT NULL,
                  public_flag int2 NOT NULL DEFAULT 1,
                  creator varchar(64) DEFAULT '',
                  create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updater varchar(64) DEFAULT '',
                  update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  deleted int2 NOT NULL DEFAULT 0,
                  tenant_id int8 NOT NULL DEFAULT 1,
                  sign_protocol varchar(16) NOT NULL DEFAULT 'BOTH'
                );
                CREATE TABLE IF NOT EXISTS dh_supply_api (
                  id int8 PRIMARY KEY,
                  category_id int8 NOT NULL,
                  name varchar(100) NOT NULL,
                  version varchar(20) NOT NULL DEFAULT 'v1.0.0',
                  path varchar(200) NOT NULL,
                  method varchar(10) NOT NULL DEFAULT 'POST',
                  result_format varchar(20) NOT NULL DEFAULT 'PAGE',
                  status int2 NOT NULL DEFAULT 0,
                  config_type varchar(20) NOT NULL DEFAULT 'TABLE',
                  creator varchar(64) DEFAULT '',
                  create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updater varchar(64) DEFAULT '',
                  update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  deleted int2 NOT NULL DEFAULT 0,
                  tenant_id int8 NOT NULL DEFAULT 1
                );
                CREATE TABLE IF NOT EXISTS dh_supply_app_api (
                  id int8 PRIMARY KEY,
                  app_id int8 NOT NULL,
                  api_id int8 NOT NULL,
                  forever_flag int2 NOT NULL DEFAULT 1,
                  start_date date,
                  end_date date,
                  creator varchar(64) DEFAULT '',
                  create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updater varchar(64) DEFAULT '',
                  update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  deleted int2 NOT NULL DEFAULT 0,
                  tenant_id int8 NOT NULL DEFAULT 1
                );
                CREATE TABLE IF NOT EXISTS dh_supply_api_log (
                  id int8 PRIMARY KEY,
                  api_id int8,
                  api_name varchar(100),
                  status int2 NOT NULL DEFAULT 1,
                  creator varchar(64) DEFAULT '',
                  create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updater varchar(64) DEFAULT '',
                  update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  deleted int2 NOT NULL DEFAULT 0,
                  tenant_id int8 NOT NULL DEFAULT 1
                );
                CREATE SEQUENCE IF NOT EXISTS system_menu_id_seq;
                CREATE TABLE IF NOT EXISTS system_menu (
                  id int8 PRIMARY KEY DEFAULT nextval('system_menu_id_seq'),
                  name varchar(50),
                  permission varchar(100),
                  type int2,
                  sort int4,
                  parent_id int8,
                  path varchar(200) DEFAULT '',
                  icon varchar(100) DEFAULT '',
                  component varchar(255),
                  component_name varchar(50),
                  status int2 DEFAULT 0,
                  visible char(1) DEFAULT '1',
                  keep_alive char(1) DEFAULT '1',
                  always_show char(1) DEFAULT '1',
                  creator varchar(64) DEFAULT '',
                  create_time timestamp(6) DEFAULT CURRENT_TIMESTAMP,
                  updater varchar(64) DEFAULT '',
                  update_time timestamp(6) DEFAULT CURRENT_TIMESTAMP,
                  deleted int2 DEFAULT 0
                );
                INSERT INTO system_menu (id, name, permission, type, sort, parent_id, status, deleted)
                VALUES (6311, 'API修改', 'supply:data-api:update', 3, 2, 6302, 0, 0)
                ON CONFLICT (id) DO NOTHING;
                """;
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static LocalDateTime scalarTimestamp(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getTimestamp(1).toLocalDateTime();
        }
    }
}
