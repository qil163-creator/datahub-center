package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlVersionRestoreReqVO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlPipelineMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlVersionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL current schema and real transaction proof for version persistence. */
@Testcontainers
class EtlVersionPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer();
    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement static class Transactions { }
    private Connection connection;
    private String schema;
    private AnnotationConfigApplicationContext app;
    private EtlVersionService service;
    private Long historicalId;

    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        schema = "etl_version_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema); sql("SET search_path TO " + schema);
        sql("CREATE TABLE dh_dataforge_etl_pipeline(environment_id bigint,id bigint PRIMARY KEY,tenant_id bigint,name text,task_type integer,cron text,next_run_time timestamp,status integer,schedule_status integer,run_status integer,start_time timestamp,end_time timestamp,release_time timestamp,release_user_id bigint,description text,nodes_json jsonb,edges_json jsonb,params_json jsonb,org_id bigint,creator text,updater text,create_time timestamp default now(),update_time timestamp default now(),deleted smallint default 0,quality_task_id bigint,revision integer,published_config_json jsonb,published_revision integer,published_execution_user_id bigint,schedule_generation bigint,latest_run_id bigint)");
        sql("INSERT INTO dh_dataforge_etl_pipeline(id,name,task_type,revision,published_revision,published_execution_user_id,schedule_generation,nodes_json,edges_json,params_json,published_config_json) VALUES (10,'test',1,4,2,42,8,'[]','[]','[]','{\"nodes\":[{\"id\":\"historical\"}],\"edges\":[],\"parameters\":[],\"taskType\":1}')");
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertNotNull(root);
        String migration = cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve("sql/postgresql/migrations/0081_dataforge_etl-version-governance-link.sql"))
                .replace("\"datahub-center\"", "\"" + schema + "\"");
        sql(migration); sql(migration);
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema, POSTGRES.getUsername(), POSTGRES.getPassword());
        var mybatis = new MybatisConfiguration(); mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        mybatis.setEnvironment(new Environment("version-real-pg", new SpringManagedTransactionFactory(), source));
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(source);
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(mybatis).setMetaObjectHandler(
                new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        var interceptor = new MybatisPlusInterceptor();

        mybatis.addInterceptor(interceptor); mybatis.addMapper(EtlPipelineMapper.class); mybatis.addMapper(EtlVersionMapper.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(mybatis));
        app = new AnnotationConfigApplicationContext(); app.register(Transactions.class);
        app.registerBean(EtlVersionMapper.class, () -> sessions.getMapper(EtlVersionMapper.class));
        app.registerBean(EtlPipelineMapper.class, () -> sessions.getMapper(EtlPipelineMapper.class));
        app.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        app.registerBean(EtlVersionService.class); app.refresh(); service = app.getBean(EtlVersionService.class);
        TenantContextHolder.setTenantId(7L);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(55L).setTenantId(7L), new MockHttpServletRequest());
        var published = app.getBean(EtlPipelineMapper.class).selectById(10L);
        published.setRevision(published.getPublishedRevision());
        String snapshot = value("SELECT published_config_json::text FROM dh_dataforge_etl_pipeline WHERE id=10");
        new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(
                status -> service.capturePublished(published, 8, 42L, snapshot));
        historicalId = Long.parseLong(value("SELECT id FROM dh_dataforge_etl_version"));
    }

    @AfterEach void cleanup() throws Exception {
        TenantContextHolder.clear(); SecurityContextHolder.clearContext(); if (app != null) app.close();
        if (connection != null) {
            try { if (schema != null && schema.matches("etl_version_[a-f0-9]{32}")) sql("DROP SCHEMA " + schema + " CASCADE"); }
            finally { connection.close(); }
        }
    }

    @Test void capturedVersionHashSurvivesDatabaseRoundTrip() throws Exception {
        assertEquals("1", value("SELECT count(*) FROM dh_dataforge_etl_version"));
        var version = service.get(historicalId);
        assertEquals(DigestUtil.sha256Hex(version.getConfigJson()), version.getContentHash());
        String raw = "{ \"nodes\" : [], \"edges\":[], \"parameters\":[] }";
        var pipeline = app.getBean(EtlPipelineMapper.class).selectById(10L);
        new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> service.capturePublished(pipeline, 9, 55L, raw));
        Long capturedId = Long.valueOf(value("SELECT id FROM dh_dataforge_etl_version WHERE release_generation=9"));
        assertEquals(raw, service.get(capturedId).getConfigJson());
        assertEquals(DigestUtil.sha256Hex(raw), service.get(capturedId).getContentHash());
    }

    @Test void restoreChangesDraftAndAppendsAuditWithoutChangingPublishedSnapshotOrHistory() throws Exception {
        String published = value("SELECT published_config_json::text FROM dh_dataforge_etl_pipeline WHERE id=10");
        String historical = value("SELECT config_json FROM dh_dataforge_etl_version WHERE id=" + historicalId);
        assertEquals(5, service.restore(request()));
        assertEquals("historical", value("SELECT nodes_json->0->>'id' FROM dh_dataforge_etl_pipeline WHERE id=10"));
        assertEquals("2:8", value("SELECT published_revision||':'||schedule_generation FROM dh_dataforge_etl_pipeline WHERE id=10"));
        assertEquals(published, value("SELECT published_config_json::text FROM dh_dataforge_etl_pipeline WHERE id=10"));
        assertEquals(historical, value("SELECT config_json FROM dh_dataforge_etl_version WHERE id=" + historicalId));
        assertEquals("1", value("SELECT count(*) FROM dh_dataforge_etl_version WHERE event_type='RESTORE' AND actor_id=55"));
        assertThrows(RuntimeException.class, () -> service.restore(request()));
    }

    @Test void failedRestoreAuditRollsBackDraftRevisionAndContent() throws Exception {
        sql("CREATE FUNCTION reject_version_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'version audit failure'; END $$");
        sql("CREATE TRIGGER reject_version_audit BEFORE INSERT ON dh_dataforge_etl_version FOR EACH ROW EXECUTE FUNCTION reject_version_audit()");
        assertThrows(RuntimeException.class, () -> service.restore(request()));
        assertEquals("4:[]", value("SELECT revision||':'||nodes_json::text FROM dh_dataforge_etl_pipeline WHERE id=10"));
        assertEquals("1", value("SELECT count(*) FROM dh_dataforge_etl_version"));
    }

    @Test void tamperingAndMissingHistoryAreRejected() throws Exception {
        assertThrows(RuntimeException.class, () -> service.get(-1L));
        sql("UPDATE dh_dataforge_etl_version SET config_json='{}' WHERE id=" + historicalId);
        assertThrows(RuntimeException.class, () -> service.get(historicalId));
    }

    @Test void workOrderReportLinksEnforceGlobalUniqueReferences() throws Exception {
        sql("INSERT INTO dh_governance_quality_work_order_etl_report(work_order_id,report_id) VALUES(1,1)");
        assertThrows(SQLException.class, () -> sql("INSERT INTO dh_governance_quality_work_order_etl_report(work_order_id,report_id) VALUES(2,1)"));
        assertThrows(SQLException.class, () -> sql("INSERT INTO dh_governance_quality_work_order_etl_report(work_order_id,report_id) VALUES(1,2)"));
    }

    private EtlVersionRestoreReqVO request() { var req = new EtlVersionRestoreReqVO(); req.setVersionId(historicalId); req.setExpectedRevision(4); req.setReason("restore verified version"); return req; }
    private void sql(String statement) throws SQLException { try (var query = connection.createStatement()) { query.execute(statement); } }
    private String value(String statement) throws SQLException { try (var query = connection.createStatement(); var result = query.executeQuery(statement)) { assertTrue(result.next()); return result.getString(1); } }
}
