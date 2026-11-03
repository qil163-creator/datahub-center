package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlPipelineDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses the real batch mapper, PostgreSQL and Spring transaction proxy; task locks are isolated mocks. */
@Testcontainers
class EtlQualityRegistrationPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer();
    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement static class Transactions { }
    private final EtlRecordMapper records = mock(EtlRecordMapper.class);
    private final EtlPipelineMapper pipelines = mock(EtlPipelineMapper.class);
    private Connection connection;
    private String schema;
    private AnnotationConfigApplicationContext app;
    private EtlRunCoordinator coordinator;

    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        schema = "etl_quality_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema); sql("SET search_path TO " + schema);
        sql("CREATE TABLE dh_dataforge_etl_tool(id bigint PRIMARY KEY,parent_id bigint,name text,icon text,type integer,code text,built_in integer,enabled integer,order_no integer,description text,tenant_id bigint,creator text,create_time timestamp,updater text,update_time timestamp,deleted smallint)");
        sql("CREATE TABLE dh_dataforge_etl_record(id bigint PRIMARY KEY,pipeline_id bigint,tenant_id bigint)");
        sql("INSERT INTO dh_dataforge_etl_record VALUES (99,10,7)");
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertNotNull(root);
        String migration = cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve("sql/postgresql/migrations/0078_dataforge_etl-governance-evidence.sql"))
                .replace("\"datahub-center\"", "\"" + schema + "\"");
        sql(migration); sql(migration);
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        int toolsStart = baseline.indexOf("-- ETL 字典与质量节点");
        String tools = baseline.substring(toolsStart, baseline.indexOf("-- ----------------------------", toolsStart))
                .replace("datahub-center", schema);
        sql(tools); sql(tools);
        sql(cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve("sql/postgresql/migrations/0088_dataforge_etl-target-batch.sql"))
                .replace("\"datahub-center\"", "\"" + schema + "\""));
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(connection, schema);
        sql("INSERT INTO dh_dataforge_etl_quality_batch(id,record_id,node_no,execution_user_id,plan_hash,input_count,pass_count,warn_count,reject_count,violation_count,registration_status,attempt_count) VALUES (1,99,'quality',42,'frozen-hash',1000,930,20,50,120,'FAILED',10)");
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema, POSTGRES.getUsername(), POSTGRES.getPassword());
        var mybatis = new MybatisConfiguration(); mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        mybatis.setEnvironment(new Environment("quality-real-pg", new SpringManagedTransactionFactory(), source));
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(source);
        mybatis.addMapper(EtlQualityBatchMapper.class);
        var assistant = new MapperBuilderAssistant(mybatis, "quality-coordinator");
        TableInfoHelper.initTableInfo(assistant, EtlRecordDO.class); TableInfoHelper.initTableInfo(assistant, EtlPipelineDO.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(mybatis));
        var record = EtlRecordDO.builder().id(99L).pipelineId(10L).runStatus(3).occupiesSlot(false).reviewRequired(false).build();
        when(records.selectOne(any())).thenReturn(record);
        when(pipelines.selectOne(any())).thenReturn(EtlPipelineDO.builder().id(10L).status(0).build());
        app = new AnnotationConfigApplicationContext(); app.register(Transactions.class);
        app.getBeanFactory().registerSingleton("records", records); app.getBeanFactory().registerSingleton("pipelines", pipelines);
        app.getBeanFactory().registerSingleton("metrics", mock(RunMetrics.class));
        app.getBeanFactory().registerSingleton("versionService", mock(EtlVersionService.class));
        app.getBeanFactory().registerSingleton("targetBatches", mock(cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlTargetBatchMapper.class));
        app.getBeanFactory().registerSingleton("replays", mock(cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlReplayMapper.class));
        app.getBeanFactory().registerSingleton("archives", mock(cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlArchiveMapper.class));
        app.registerBean(EtlQualityBatchMapper.class, () -> sessions.getMapper(EtlQualityBatchMapper.class));
        app.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        app.registerBean(RunExecutionProperties.class);
                app.registerBean(EtlRunCoordinator.class); app.refresh();
        coordinator = app.getBean(EtlRunCoordinator.class); TenantContextHolder.setTenantId(7L);
    }

    @AfterEach void cleanup() throws Exception {
        TenantContextHolder.clear(); if (app != null) app.close();
        if (connection != null) {
            try { if (schema != null && schema.matches("etl_quality_[a-f0-9]{32}")) sql("DROP SCHEMA " + schema + " CASCADE"); }
            finally { connection.close(); }
        }
    }

    @Test void retryCommitsOneAuditAndKeepsFrozenFactsAndExecutionIdentity() throws Exception {
        coordinator.retryQualityRegistration(1L, 99L, 55L);
        assertEquals("PENDING", value("SELECT registration_status FROM dh_dataforge_etl_quality_batch WHERE id=1"));
        assertEquals("0", value("SELECT attempt_count FROM dh_dataforge_etl_quality_batch WHERE id=1"));
        assertEquals("frozen-hash:42:1000:930:20:50:120", value("SELECT plan_hash||':'||execution_user_id||':'||input_count||':'||pass_count||':'||warn_count||':'||reject_count||':'||violation_count FROM dh_dataforge_etl_quality_batch WHERE id=1"));
        assertEquals("1:55:10", value("SELECT batch_id||':'||actor_id||':'||previous_attempts FROM dh_dataforge_etl_quality_retry"));
        assertThrows(RuntimeException.class, () -> coordinator.retryQualityRegistration(1L, 99L, 55L));
        assertEquals("1", value("SELECT count(*) FROM dh_dataforge_etl_quality_retry"));
    }

    @Test void auditInsertFailureRollsBackClaimedRetry() throws Exception {
        sql("CREATE FUNCTION reject_quality_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'audit failure'; END $$");
        sql("CREATE TRIGGER reject_quality_audit BEFORE INSERT ON dh_dataforge_etl_quality_retry FOR EACH ROW EXECUTE FUNCTION reject_quality_audit()");
        assertThrows(RuntimeException.class, () -> coordinator.retryQualityRegistration(1L, 99L, 55L));
        assertEquals("FAILED:10", value("SELECT registration_status||':'||attempt_count FROM dh_dataforge_etl_quality_batch WHERE id=1"));
        assertEquals("0", value("SELECT count(*) FROM dh_dataforge_etl_quality_retry"));
    }

    @Test void evidencePreventsRecordOrPipelineDeletion() {
        AtomicBoolean childrenDeleted = new AtomicBoolean();
        assertThrows(RuntimeException.class, () -> coordinator.deleteRecord(99L, () -> childrenDeleted.set(true)));
        assertThrows(RuntimeException.class, () -> coordinator.deletePipeline(10L));
        assertFalse(childrenDeleted.get()); verify(records, never()).deleteById(anyLong()); verify(pipelines, never()).deleteById(anyLong());
    }

    @Test void migrationReplaysAndRejectsPartialCountsOrCrossRowConservationErrors() throws Exception {
        assertEquals("1", value("SELECT count(*) FROM dh_dataforge_etl_tool WHERE code='quality-check'"));
        assertThrows(SQLException.class, () -> sql("UPDATE dh_dataforge_etl_quality_batch SET warn_count=NULL WHERE id=1"));
        assertThrows(SQLException.class, () -> sql("UPDATE dh_dataforge_etl_quality_batch SET reject_count=49 WHERE id=1"));
        assertThrows(SQLException.class, () -> sql("INSERT INTO dh_dataforge_etl_quality_retry(batch_id,actor_id,previous_attempts) VALUES (1,55,-1)"));
    }

    private void sql(String statement) throws SQLException { try (var query = connection.createStatement()) { query.execute(statement); } }
    private String value(String statement) throws SQLException {
        try (var query = connection.createStatement(); var result = query.executeQuery(statement)) { assertTrue(result.next()); return result.getString(1); }
    }
}
