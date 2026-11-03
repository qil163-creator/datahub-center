package cn.iocoder.yudao.module.governance.support;

import cn.iocoder.yudao.module.governance.dal.mysql.doc.StandardDocMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydatameta.CategoryDatametaMapper;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Real PostgreSQL foundation for standard-document mapper tests.
 *
 * <p>Testcontainers is the normal and CI path. Set {@code standard.postgres.external=true}
 * only to use a dedicated PostgreSQL test database supplied by {@code STANDARD_TEST_POSTGRES_URL}
 * and {@code STANDARD_TEST_POSTGRES_USERNAME}; {@code STANDARD_TEST_POSTGRES_PASSWORD} is optional.
 * The external path never falls back to Testcontainers. It additionally requires
 * {@code STANDARD_TEST_POSTGRES_RESET=ALLOW_DATA_STANDARD_TEST_RESET}, validates a dedicated
 * test-only database name, and serializes schema resets with a session advisory lock.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringJUnitConfig(StandardPostgreSqlIntegrationTestBase.Application.class)
public abstract class StandardPostgreSqlIntegrationTestBase {

    private static final boolean EXTERNAL_POSTGRES = Boolean.getBoolean("standard.postgres.external");
    private static final long SCHEMA_RESET_ADVISORY_LOCK = 8_419_287_301L;

    @Autowired
    protected DataSource dataSource;

    private Connection isolationConnection;

    @BeforeAll
    void acquireIsolationLock() throws Exception {
        isolationConnection = dataSource.getConnection();
        try {
            if (EXTERNAL_POSTGRES) {
                validateExternalResetTarget(isolationConnection);
            }
            execute(isolationConnection, "SELECT pg_advisory_lock(" + SCHEMA_RESET_ADVISORY_LOCK + ")");
        } catch (Exception exception) {
            closeIsolationConnection();
            throw exception;
        }
    }

    @BeforeEach
    void resetStandardSchema() throws Exception {
        try {
            isolationConnection.setAutoCommit(false);
            execute(isolationConnection, "DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
            execute(isolationConnection, "CREATE SCHEMA \"datahub-center\"");
            execute(isolationConnection, "SET search_path TO \"datahub-center\"");
            execute(isolationConnection, """
                CREATE TABLE "datahub-center".dh_governance_category_datameta (
                  id bigint PRIMARY KEY, parent_id bigint DEFAULT 0, category_name varchar(255), category_code varchar(255),
                  sort integer DEFAULT 0, status smallint DEFAULT 0, remark text,
                  creator varchar(64) DEFAULT '', create_time timestamp DEFAULT CURRENT_TIMESTAMP,
                  updater varchar(64) DEFAULT '', update_time timestamp DEFAULT CURRENT_TIMESTAMP,
                  deleted smallint DEFAULT 0
                )
                """);
            execute(isolationConnection, """
                CREATE TABLE "datahub-center".dh_governance_standard_doc (
                  id int8 NOT NULL,
                  std_code varchar(64),
                  name_cn varchar(255) NOT NULL,
                  name_en varchar(255),
                  std_type varchar(16) NOT NULL DEFAULT 'LOCAL',
                  category_id bigint,
                  version_no varchar(64),
                  abolish_date date,
                  publish_org varchar(255),
                  publish_date date,
                  implement_date date,
                  implement_status varchar(500) NOT NULL DEFAULT 'CURRENT',
                  registry_no varchar(64),
                  registry_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  file_id int8,
                  file_name varchar(255),
                  file_url varchar(1024),
                  replace_doc_id int8,
                  summary varchar(2048),
                  status int2 NOT NULL DEFAULT 0,
                  sort int4 NOT NULL DEFAULT 0,
                  remark varchar(512),
                  creator varchar(64) DEFAULT '',
                  create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updater varchar(64) DEFAULT '',
                  update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  deleted int2 NOT NULL DEFAULT 0
                )
                """);
            execute(isolationConnection, """
                CREATE TABLE "datahub-center".dh_governance_standard_element (
                  id int4 NOT NULL,
                  code varchar(128),
                  name varchar(255),
                  eng_name varchar(255),
                  category_id int4,
                  document_id int4,
                  column_type varchar(255),
                  data_length integer,
                  data_precision integer,
                  data_scale integer,
                  data_format varchar(255),
                  measure_unit varchar(64),
                  value_range text,
                  nullable_flag integer,
                  default_value varchar(255),
                  lifecycle_status varchar(32),
                  standard_type varchar(64),
                  contact_number varchar(64),
                  revision bigint DEFAULT 0,
                  person_charge int4,
                  description varchar(255),
                  sort int4 NOT NULL DEFAULT 0,
                  status int2 NOT NULL DEFAULT 1,
                  remark text,
                  creator varchar(64) DEFAULT '',
                  create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updater varchar(64) DEFAULT '',
                  update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  deleted int2 NOT NULL DEFAULT 0,
                  code_set_id int4
                )
                """);
            execute(isolationConnection, """
                CREATE TABLE "datahub-center".dh_governance_standard_code_set (
                  id integer PRIMARY KEY,
                  name varchar(50), type varchar(100), status smallint DEFAULT 0,
                  remark text, deleted_time timestamp,
                  category_id bigint, column_type varchar(64), data_length integer, data_scale integer,
                  document_id bigint, person_charge bigint, description varchar(256),
                  standard_type varchar(64), contact_number varchar(64), revision bigint DEFAULT 0,
                  creator varchar(64) DEFAULT '', create_time timestamp DEFAULT CURRENT_TIMESTAMP,
                  updater varchar(64) DEFAULT '', update_time timestamp DEFAULT CURRENT_TIMESTAMP,
                  deleted smallint DEFAULT 0
                )
                """);
            new ResourceDatabasePopulator(new ClassPathResource("sql/standard/doc-fixture.sql"))
                    .populate(isolationConnection);
            isolationConnection.commit();
        } catch (Exception exception) {
            rollbackIsolationConnection();
            throw exception;
        } finally {
            if (!isolationConnection.getAutoCommit()) {
                isolationConnection.setAutoCommit(true);
            }
        }
    }

    @AfterAll
    void releaseIsolationLock() {
        try {
            if (isolationConnection != null && !isolationConnection.isClosed()) {
                execute(isolationConnection, "SELECT pg_advisory_unlock(" + SCHEMA_RESET_ADVISORY_LOCK + ")");
            }
        } catch (SQLException ignored) {
            // Closing the session below also releases the session-level advisory lock.
        } finally {
            closeIsolationConnection();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @MapperScan(basePackageClasses = {StandardDocMapper.class, StandardElementMapper.class, StandardCodeSetMapper.class, CategoryDatametaMapper.class},
            sqlSessionFactoryRef = "sqlSessionFactory")
    static class Application {

        @Bean(destroyMethod = "close")
        StandardPostgresRuntime standardPostgresRuntime() {
            return new StandardPostgresRuntime(EXTERNAL_POSTGRES);
        }

        @Bean
        DataSource dataSource(StandardPostgresRuntime standardPostgresRuntime) {
            String url;
            String username;
            String password;
            if (EXTERNAL_POSTGRES) {
                url = requiredEnvironment("STANDARD_TEST_POSTGRES_URL");
                username = requiredEnvironment("STANDARD_TEST_POSTGRES_USERNAME");
                password = System.getenv("STANDARD_TEST_POSTGRES_PASSWORD");
            } else {
                url = standardPostgresRuntime.getJdbcUrl();
                username = standardPostgresRuntime.getUsername();
                password = standardPostgresRuntime.getPassword();
            }
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.postgresql.Driver");
            dataSource.setUrl(appendCurrentSchema(url));
            dataSource.setUsername(username);
            dataSource.setPassword(password == null ? "" : password);
            return dataSource;
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setGlobalConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig()
                    .setMetaObjectHandler(new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler()));
            var pagination = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
            pagination.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(
                    com.baomidou.mybatisplus.annotation.DbType.POSTGRE_SQL));
            factoryBean.setPlugins(pagination);
            return factoryBean.getObject();
        }
    }

    static final class StandardPostgresRuntime implements AutoCloseable {

        private final PostgreSQLContainer<?> container;

        private StandardPostgresRuntime(boolean externalPostgres) {
            this.container = externalPostgres ? null : new PostgreSQLContainer<>("postgres:16-alpine");
        }

        private String getJdbcUrl() {
            start();
            return container.getJdbcUrl();
        }

        private String getUsername() {
            start();
            return container.getUsername();
        }

        private String getPassword() {
            start();
            return container.getPassword();
        }

        private void start() {
            if (container == null) {
                throw new IllegalStateException("Testcontainers is unavailable in external PostgreSQL mode");
            }
            if (!container.isRunning()) {
                container.start();
            }
        }

        @Override
        public void close() {
            if (container != null && container.isRunning()) {
                container.stop();
            }
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when -Dstandard.postgres.external=true");
        }
        return value;
    }

    private static void validateExternalResetTarget(Connection connection) throws SQLException {
        if (!"ALLOW_DATA_STANDARD_TEST_RESET".equals(System.getenv("STANDARD_TEST_POSTGRES_RESET"))) {
            throw new IllegalStateException("STANDARD_TEST_POSTGRES_RESET=ALLOW_DATA_STANDARD_TEST_RESET is required "
                    + "when -Dstandard.postgres.external=true");
        }
        String databaseName;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT current_database()")) {
            resultSet.next();
            databaseName = resultSet.getString(1);
        }
        if (!isUnmistakableTestDatabaseName(databaseName)) {
            throw new IllegalStateException("External PostgreSQL reset requires a data_standard_*_test database; "
                    + "connected to " + databaseName);
        }
    }

    public static boolean isUnmistakableTestDatabaseName(String databaseName) {
        return databaseName != null && databaseName.matches("data_standard_[a-z0-9_]*_test");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void rollbackIsolationConnection() {
        try {
            if (isolationConnection != null && !isolationConnection.getAutoCommit()) {
                isolationConnection.rollback();
            }
        } catch (SQLException ignored) {
            // The original setup error is more useful to the caller.
        }
    }

    private void closeIsolationConnection() {
        try {
            if (isolationConnection != null) {
                isolationConnection.close();
            }
        } catch (SQLException ignored) {
            // The session will still be released when the JDBC driver tears it down.
        }
    }

    private static String appendCurrentSchema(String url) {
        return url.contains("?") ? url + "&currentSchema=%22datahub-center%22"
                : url + "?currentSchema=%22datahub-center%22";
    }
}
