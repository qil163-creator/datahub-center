package cn.iocoder.yudao.module.modeling.service.model;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ModelingMenuMigrationPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void menuMigrationReplaysAndSeparatesDeploymentPermissions() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            fixture(statement);
            statement.execute(migration()); statement.execute(migration());
            assertThat(count(statement, "SELECT count(*) FROM \"datahub-center\".system_menu WHERE component IN ('modeling/businessobject/index','modeling/modelpublish/index')")).isEqualTo(2);
            assertThat(count(statement, "SELECT count(*) FROM \"datahub-center\".system_menu WHERE permission IN ('modeling:model:deploy','modeling:model:deploy-reconcile','modeling:model:check','modeling:model:check-config')")).isEqualTo(4);
            assertThat(count(statement, "SELECT count(*) FROM \"datahub-center\".system_menu WHERE permission LIKE 'modeling:business-object:%'")).isEqualTo(6);
            assertThat(count(statement, "SELECT count(*) FROM \"datahub-center\".system_menu WHERE permission='modeling:model:publish' AND id=3")).isEqualTo(1);
        }
    }

    @Test
    void missingBusinessParentRollsBackInsteadOfInventingAnArchitectureTree() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            fixture(statement);
            statement.execute("DELETE FROM \"datahub-center\".system_menu WHERE id=1");
            assertThatThrownBy(() -> statement.execute(migration())).isInstanceOf(SQLException.class);
            statement.execute("ROLLBACK");
            assertThat(count(statement, "SELECT count(*) FROM \"datahub-center\".system_menu WHERE component='modeling/businessobject/index'")).isZero();
        }
    }

    @Test
    void disabledPermissionIsNotSilentlyRestoredOrGranted() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            fixture(statement);
            statement.execute("INSERT INTO \"datahub-center\".system_menu(id,name,permission,type,parent_id,status) VALUES(4,'受控部署','modeling:model:deploy',3,20,1)");
            assertThatThrownBy(() -> statement.execute(migration())).isInstanceOf(SQLException.class);
            statement.execute("ROLLBACK");
            assertThat(count(statement, "SELECT count(*) FROM \"datahub-center\".system_menu WHERE id=4 AND status=1")).isEqualTo(1);
            assertThat(count(statement, "SELECT count(*) FROM \"datahub-center\".system_menu WHERE component='modeling/modelpublish/index'")).isZero();
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void fixture(Statement statement) throws SQLException {
        statement.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE; CREATE SCHEMA \"datahub-center\"");
        statement.execute("""
                CREATE SEQUENCE "datahub-center".system_menu_seq;
                CREATE TABLE "datahub-center".system_menu (
                  id bigint PRIMARY KEY DEFAULT nextval('"datahub-center".system_menu_seq'),
                  name varchar(100),permission varchar(100),type integer,sort integer,parent_id bigint,
                  path varchar(200),icon varchar(100),component varchar(200),component_name varchar(200),
                  status integer DEFAULT 0,visible boolean,keep_alive boolean,always_show boolean,
                  creator varchar(64),updater varchar(64),create_time timestamp,update_time timestamp,deleted smallint DEFAULT 0);
                INSERT INTO "datahub-center".system_menu(id,name,type,parent_id,component) VALUES
                  (1,'业务过程',2,10,'modeling/businessprocess/index'),(2,'逻辑模型',2,20,'modeling/logicmodel/index');
                INSERT INTO "datahub-center".system_menu(id,name,type,parent_id,permission) VALUES
                  (3,'设计发布',3,20,'modeling:model:publish');
                """);
    }

    private int count(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) { result.next(); return result.getInt(1); }
    }

    private String migration() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("没有找到仓库根目录");
        return Files.readString(root.resolve("sql/postgresql/migrations/0080_modeling_closed-loop-menu.sql"));
    }
}
