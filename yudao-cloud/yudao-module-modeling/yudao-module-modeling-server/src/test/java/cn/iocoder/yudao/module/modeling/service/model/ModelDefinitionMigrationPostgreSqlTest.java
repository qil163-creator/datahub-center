package cn.iocoder.yudao.module.modeling.service.model;

import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 在独立临时数据库验证真实基线、迁移重放、部分唯一索引和并发引用保护。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelDefinitionMigrationPostgreSqlTest {
    private String controlUrl;
    private String user;
    private String database;
    private String url;
    private JdbcTemplate jdbc;
    private String migration;

    @BeforeAll
    void initialize() throws Exception {
        controlUrl = System.getenv("MODEL_OVERVIEW_TEST_JDBC_URL");
        assumeTrue(controlUrl != null && !controlUrl.isBlank(), "需要显式提供隔离 PostgreSQL 测试环境");
        user = System.getenv().getOrDefault("MODEL_OVERVIEW_TEST_DB_USER", "overview_test");
        if (!controlUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres") || !"overview_test".equals(user)) {
            throw new IllegalStateException("仅接受本机任务专用数据库和测试角色");
        }
        database = "model_definition_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(controlUrl, user, ""); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        url = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database + "?currentSchema=datahub-center";
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, ""));
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertNotNull(root);
        // 0171 的重放必须使用其菜单后置状态之前的基线；0185 会把该页面改为指标目录。
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        jdbc.execute(baseline.substring(0, baseline.indexOf("-- Migration: 0185 ")));
        migration = Files.readString(root.resolve("sql/postgresql/migrations/0171_modeling_concept-indicator-models.sql"));
    }

    @AfterAll
    void cleanup() throws Exception {
        if (database != null && database.matches("model_definition_[0-9a-f]{32}")) {
            try (var connection = DriverManager.getConnection(controlUrl, user, ""); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }

    @Test
    void migrationReplaysWithoutDuplicateMenusOrTenancy() {
        jdbc.execute(migration);
        jdbc.execute(migration);
        assertEquals(8L, jdbc.queryForObject("SELECT count(*) FROM system_menu WHERE deleted=0 AND "
                + "(permission LIKE 'modeling:concept-model:%' OR permission LIKE 'modeling:indicator-model:%')", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' "
                + "AND table_name IN ('dh_modeling_concept_model','dh_modeling_indicator_model') AND column_name='tenant_id'", Long.class));
    }

    @Test
    void migrationRejectsAmbiguousOrCrossModulePlanningMenus() {
        long page = jdbc.queryForObject("SELECT id FROM system_menu WHERE deleted=0 AND component='modeling/datadomain/index'", Long.class);
        long parent = jdbc.queryForObject("SELECT parent_id FROM system_menu WHERE id=?", Long.class, page);
        long duplicate = jdbc.queryForObject("SELECT max(id)+1 FROM system_menu", Long.class);
        try {
            jdbc.update("INSERT INTO system_menu(id,name,type,parent_id,path,component,deleted) VALUES(?,'歧义测试',2,?,'ambiguity-test','modeling/datadomain/index',0)", duplicate, parent);
            assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.execute(migration));
        } finally {
            jdbc.update("DELETE FROM system_menu WHERE id=?", duplicate);
        }
        try {
            jdbc.update("UPDATE system_menu SET parent_id=0 WHERE id=?", page);
            assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.execute(migration));
        } finally {
            jdbc.update("UPDATE system_menu SET parent_id=? WHERE id=?", parent, page);
        }
    }

    @Test
    void conceptPartialUniquenessAndPlanningDeletionProtectLiveDefinitions() {
        long domain = domain();
        String code = token();
        long concept = jdbc.queryForObject("INSERT INTO dh_modeling_concept_model(name,code,data_domain_id) VALUES(?,?,?) RETURNING id", Long.class, code, code, domain);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO dh_modeling_concept_model(name,code,data_domain_id) VALUES(?,?,?)", token(), code.toUpperCase(), domain));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE dh_modeling_data_domain SET deleted=1 WHERE id=?", domain));
        jdbc.update("UPDATE dh_modeling_concept_model SET deleted=1 WHERE id=?", concept);
        jdbc.update("INSERT INTO dh_modeling_concept_model(name,code,data_domain_id) VALUES(?,?,?)", code, code, domain);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO dh_modeling_concept_model(name,code,data_domain_id,design) VALUES(?,?,?,'[]'::jsonb)", token(), token(), domain));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO dh_modeling_concept_model(name,code,data_domain_id,design) VALUES(?,?,?,'{}'::jsonb)", token(), token(), domain));
    }

    @Test
    void atomicReferencesRejectMissingFieldsAndProtectFrozenSources() {
        long[] source = source();
        long domain = domain();
        assertThrows(DataIntegrityViolationException.class, () -> atomic(domain,source[0],source[1],"missing"));
        long atomic = atomic(domain,source[0],source[1],"amount");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE dh_modeling_logic_model_version SET deleted=1 WHERE id=?",source[1]));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE dh_modeling_logic_model SET status='DISCARDED' WHERE id=?",source[0]));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM dh_modeling_logic_model WHERE id=?",source[0]));
        jdbc.update("UPDATE dh_modeling_indicator_model SET deleted=1 WHERE id=?",atomic);
        jdbc.update("UPDATE dh_modeling_logic_model SET status='DISCARDED' WHERE id=?",source[0]);
    }

    @Test
    void derivedReferencePreventsAtomicChangesAndCrossDomainBindings() {
        long[] source = source();
        long domain = domain();
        long atomic = atomic(domain,source[0],source[1],"amount");
        jdbc.update("UPDATE dh_modeling_indicator_model SET status='ACTIVE' WHERE id=?",atomic);
        assertThrows(DataIntegrityViolationException.class, () -> derived(domain(),atomic));
        long derived = derived(domain,atomic);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE dh_modeling_indicator_model SET status='DRAFT' WHERE id=?",atomic));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE dh_modeling_indicator_model SET name='变更' WHERE id=?",atomic));
        jdbc.update("UPDATE dh_modeling_indicator_model SET deleted=1 WHERE id=?",derived);
        jdbc.update("UPDATE dh_modeling_indicator_model SET status='DRAFT' WHERE id=?",atomic);
    }

    @Test
    void concurrentDerivedCreationAndAtomicDisableCannotLeaveBrokenReference() throws Exception {
        long[] source = source();
        long domain = domain();
        long atomic = atomic(domain,source[0],source[1],"amount");
        jdbc.update("UPDATE dh_modeling_indicator_model SET status='ACTIVE' WHERE id=?",atomic);
        try (var first = DriverManager.getConnection(url,user,""); var executor = Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false);
            try (var statement = first.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0))");
                statement.execute("INSERT INTO dh_modeling_indicator_model(name,code,indicator_type,data_domain_id,base_indicator_id,time_period,business_condition) VALUES('并发派生','"+token()+"','DERIVED',"+domain+","+atomic+",'DAY','全部有效业务')");
            }
            var entered = new CountDownLatch(1);
            Future<String> outcome = executor.submit(() -> {
                try (var second = DriverManager.getConnection(url,user,""); var statement = second.createStatement()) {
                    statement.execute("SET statement_timeout='5s'");
                    entered.countDown();
                    statement.execute("UPDATE dh_modeling_indicator_model SET status='DRAFT' WHERE id="+atomic);
                    return "unexpected-success";
                } catch (SQLException failure) { return failure.getSQLState(); }
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            first.commit();
            assertEquals("23514",outcome.get(10,TimeUnit.SECONDS));
        }
    }

    private long domain() {
        String value = token();
        return jdbc.queryForObject("INSERT INTO dh_modeling_data_domain(name,code,status) VALUES(?,?,0) RETURNING id",Long.class,value,value);
    }

    private long[] source() {
        String value = token();
        long model = jdbc.queryForObject("INSERT INTO dh_modeling_logic_model(name,code,status,enabled) VALUES(?,?,'PUBLISHED',1) RETURNING id",Long.class,value,value);
        long version = jdbc.queryForObject("INSERT INTO dh_modeling_logic_model_version(model_id,version,status,fields) VALUES(?,1,'PUBLISHED','[{\"fieldUid\":\"amount\",\"fieldName\":\"amount\",\"dataType\":\"DECIMAL\"},{\"fieldUid\":\"time\",\"fieldName\":\"time\",\"dataType\":\"TIMESTAMP\"}]'::jsonb) RETURNING id",Long.class,model);
        return new long[]{model,version};
    }

    private long atomic(long domain,long model,long version,String field) {
        String value = token();
        return jdbc.queryForObject("INSERT INTO dh_modeling_indicator_model(name,code,indicator_type,data_domain_id,source_model_id,source_version_id,value_field_uid,time_field_uid,aggregation,result_data_type) VALUES(?,?,'ATOMIC',?,?,?,?,'time','SUM','DECIMAL') RETURNING id",Long.class,value,value,domain,model,version,field);
    }

    private long derived(long domain,long atomic) {
        String value = token();
        return jdbc.queryForObject("INSERT INTO dh_modeling_indicator_model(name,code,indicator_type,data_domain_id,base_indicator_id,time_period,business_condition) VALUES(?,?,'DERIVED',?,?,'DAY','全部有效业务') RETURNING id",Long.class,value,value,domain,atomic);
    }

    private String token() { return "t"+UUID.randomUUID().toString().replace("-",""); }
}
