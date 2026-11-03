package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryTableRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import net.sf.jsqlparser.expression.*;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Testcontainers(disabledWithoutDocker = true)
class QualityEtlTaskCreationPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement static class Transactions { }
    private String schema;
    private Connection control;
    private AnnotationConfigApplicationContext app;
    private QualityEtlTaskCreationService service;
    private DatabaseApi databaseApi;
    private MasterDatabaseResolver databases;
    private QualityTaskExecutor executor;
    private Object previousBeanFactory;
    private boolean beanFactoryInstalled;

    @BeforeEach void setup() throws Exception {
        schema = "etl_task_" + UUID.randomUUID().toString().replace("-", "");
        control = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        sql("CREATE SCHEMA " + schema); sql("SET search_path TO " + schema);
        createTable("dh_governance_quality_task", "task_name text,task_code text,task_category text,execute_strategy text,schedule_type text,"
                + "cron_expression text,time_window text,task_status integer,priority text,retry_count integer,retry_interval integer,"
                + "delay_exec_time integer,worker_group text,rule_set_id bigint,org_field text,group_field text,owner_user_id bigint,description text,remark text");
        createTable("dh_governance_quality_check_object", "object_name text,datasource_conn_id bigint,datasource_type text,datasource_instance text,"
                + "format_name text,table_name text,table_id bigint,remark text");
        createTable("dh_governance_quality_task_object", "task_id bigint,object_id bigint");
        createTable("dh_governance_quality_task_rule", "task_id bigint,rule_id bigint");
        createTable("dh_governance_quality_rule", "category_id bigint,rule_name text,rule_code text,quality_dim text,check_level text,rule_type text,"
                + "strategy_key text,param_schema text,rule_content text,severity text,apply_layer text,collect_issue_detail integer,"
                + "use_case text,example text,status integer,builtin integer,description text,remark text,standard_element_id bigint,element_version integer,constraint_hash text");
        sql("INSERT INTO dh_governance_quality_rule(id,rule_name,status,strategy_key,check_level,param_schema) VALUES(1,'非空',0,'NOT_NULL_VALIDATION','FIELD','{\"column\":\"id\"}'),(2,'停用',1,'NOT_NULL_VALIDATION','FIELD','{}')");
        var root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        assertNotNull(root);
        Path migration = root.resolve("sql/postgresql/drafts/governance_etl-task-creation.sql");
        if (!Files.exists(migration)) {
            try (var paths = Files.list(root.resolve("sql/postgresql/migrations"))) {
                migration = paths.filter(path -> path.getFileName().toString().endsWith("_governance_etl-task-creation.sql")).findFirst().orElseThrow();
            }
        }
        String ddl = Files.readString(migration).replace("\"datahub-center\"", "\"" + schema + "\"");
        sql(ddl); sql(ddl);
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("etl-task", new SpringManagedTransactionFactory(), ds));
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        var types = List.of(QualityTaskMapper.class, QualityTaskObjectMapper.class, QualityTaskRuleMapper.class,
                QualityCheckObjectMapper.class, QualityRuleMapper.class, QualityEtlTaskCreationMapper.class);
        types.forEach(config::addMapper);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        app = new AnnotationConfigApplicationContext(); app.register(Transactions.class);
        app.registerBean(javax.sql.DataSource.class, () -> ds);
        previousBeanFactory = ReflectionTestUtils.getField(cn.hutool.extra.spring.SpringUtil.class, "beanFactory");
        new cn.hutool.extra.spring.SpringUtil().postProcessBeanFactory(app.getBeanFactory());
        beanFactoryInstalled = true;
        app.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        registerMapper(QualityTaskMapper.class, sessions); registerMapper(QualityTaskObjectMapper.class, sessions);
        registerMapper(QualityTaskRuleMapper.class, sessions); registerMapper(QualityCheckObjectMapper.class, sessions);
        registerMapper(QualityRuleMapper.class, sessions); registerMapper(QualityEtlTaskCreationMapper.class, sessions);
        app.registerBean(QualityTaskInstanceMapper.class, () -> mock(QualityTaskInstanceMapper.class));
        app.registerBean(QualityRuleSetMapper.class, () -> mock(QualityRuleSetMapper.class));
        databases = mock(MasterDatabaseResolver.class); databaseApi = mock(DatabaseApi.class);
        var route = new MasterDatabaseResolver.ConnectionInfo(); route.setDatabaseId(60L); route.setProductType(ProductTypeEnum.POSTGRESQL); route.setSchema(schema);
        when(databases.resolve(eq(60L), any())).thenAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return route; });
        var target = new DatabaseQueryTableRespDTO(); target.setSchemaName(schema); target.setTableName("target");
        target.setTableReference("\"" + schema + "\".\"target\""); target.setNormalizedSql("SELECT * FROM " + target.getTableReference());
        when(databaseApi.resolveQueryTable(any(), any())).thenAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return CommonResult.success(target); });
        when(databaseApi.getQueryTableColumns(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return CommonResult.success(List.of(new cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO("id", "text")));
        });
        var realExecutor = QualityRuntimeFixture.executor(); ReflectionTestUtils.setField(realExecutor, "auditSqlRegistry", new AuditSqlRegistry());
        executor = mock(QualityTaskExecutor.class);
        doAnswer(call -> { realExecutor.validateEtlDraftRules(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)); return null; })
                .when(executor).validateEtlDraftRules(any(), any(), anyList(), anyList());
        app.getBeanFactory().registerSingleton("masterDatabaseResolver", databases); app.registerBean(DatabaseApi.class, () -> databaseApi);
        app.getBeanFactory().registerSingleton("qualityTaskExecutor", executor);
        app.registerBean(QualityTaskEvaluationService.class, () -> mock(QualityTaskEvaluationService.class));
        app.registerBean(QualityTaskServiceImpl.class); app.registerBean(QualityCheckObjectServiceImpl.class);
        app.registerBean(QualityEtlTaskCreationService.class); app.refresh(); service = app.getBean(QualityEtlTaskCreationService.class);
        login(99L);
    }

    @AfterEach void cleanup() throws Exception {
        TenantContextHolder.clear(); SecurityContextHolder.clearContext(); if (app != null) app.close();
        if (beanFactoryInstalled) ReflectionTestUtils.setField(cn.hutool.extra.spring.SpringUtil.class, "beanFactory", previousBeanFactory);
        if (control != null) { try { if (schema != null && schema.matches("etl_task_[a-f0-9]{32}")) sql("DROP SCHEMA " + schema + " CASCADE"); } finally { control.close(); } }
    }

    @Test void createsRealTaskObjectRelationsAndReplaysLostResponseWithoutExternalCalls() throws Exception {
        var req = request(); req.setActivate(true);
        var result = service.create(req);
        assertFalse(result.replayed()); assertEquals(0, result.taskStatus());
        assertEquals(1, count("dh_governance_quality_task")); assertEquals(1, count("dh_governance_quality_check_object"));
        assertEquals(1, count("dh_governance_quality_task_object")); assertEquals(1, count("dh_governance_quality_task_rule"));
        var detail = app.getBean(QualityTaskMapper.class).selectById(result.taskId());
        assertEquals(99L, detail.getOwnerUserId()); assertEquals("MANUAL", detail.getScheduleType()); assertEquals("SERIAL", detail.getExecuteStrategy());
        clearInvocations(databaseApi, databases, executor);
        var replay = service.create(req); assertEquals(result.taskId(), replay.taskId()); assertTrue(replay.replayed());
        assertEquals(result.taskId(), service.result(req.getRequestId()).taskId());
        verifyNoInteractions(databaseApi, databases, executor);
        assertThrows(SQLException.class, () -> sql("UPDATE dh_governance_quality_etl_task_creation SET task_name='rewrite'"));
        assertThrows(SQLException.class, () -> sql("DELETE FROM dh_governance_quality_etl_task_creation"));
    }

    @Test void duplicateRequestWithChangedPayloadOrActorCannotCreateOrRevealAnotherReceipt() throws Exception {
        var req = request(); service.create(req);
        req.setTaskName("改名"); assertThrows(RuntimeException.class, () -> service.create(req));
        login(100L); assertThrows(RuntimeException.class, () -> service.result(req.getRequestId()));
        login(99L); assertNotNull(service.result(req.getRequestId()));
        assertThrows(RuntimeException.class, () -> service.create(req)); assertEquals(1, count("dh_governance_quality_task"));
    }

    @Test void invalidRulesAndSourceFailBeforeAnyTaskIsPersisted() throws Exception {
        var req = request(); req.setRuleIds(List.of(2L)); assertThrows(RuntimeException.class, () -> service.create(req));
        req.setRuleIds(List.of(1L)); req.setSchemaName("other"); assertThrows(RuntimeException.class, () -> service.create(req));
        assertEquals(0, count("dh_governance_quality_task")); assertEquals(0, count("dh_governance_quality_etl_task_creation"));
    }

    @Test void taskOrReceiptFailureRollsBackNewObjectAndAllRelations() throws Exception {
        sql("CREATE FUNCTION reject_task() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture rejection'; END; $$;"
                + "CREATE TRIGGER reject_task BEFORE INSERT ON dh_governance_quality_task FOR EACH ROW EXECUTE FUNCTION reject_task()");
        assertThrows(RuntimeException.class, () -> service.create(request()));
        for (String name : List.of("task", "check_object", "task_rule", "task_object", "etl_task_creation"))
            assertEquals(0, count("dh_governance_quality_" + name));
    }

    @Test void simultaneousRetriesProduceOneTaskAndSameReceipt() throws Exception {
        var req = request(); var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var calls = new ArrayList<Future<QualityEtlTaskCreateRespVO>>();
            for (int i = 0; i < 2; i++) calls.add(threads.submit(() -> {
                login(99L);
                try { start.await(); return service.create(req); }
                finally { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
            }));
            start.countDown(); var first = calls.get(0).get(20, TimeUnit.SECONDS); var second = calls.get(1).get(20, TimeUnit.SECONDS);
            assertEquals(first.taskId(), second.taskId()); assertNotEquals(first.replayed(), second.replayed());
            assertEquals(1, count("dh_governance_quality_task")); assertEquals(1, count("dh_governance_quality_etl_task_creation"));
        }
    }

    @Test void receiptCompletionFailureRollsBackTaskObjectAndBatchInsertedRelations() throws Exception {
        sql("CREATE FUNCTION reject_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture receipt rejection'; END; $$;"
                + "CREATE TRIGGER reject_receipt BEFORE UPDATE ON dh_governance_quality_etl_task_creation FOR EACH ROW EXECUTE FUNCTION reject_receipt()");
        assertThrows(RuntimeException.class, () -> service.create(request()));
        for (String name : List.of("task", "check_object", "task_rule", "task_object", "etl_task_creation"))
            assertEquals(0, count("dh_governance_quality_" + name), name);
    }

    @Test void editedRuleAfterPreflightIsRejectedWithinTransaction() throws Exception {
        doAnswer(call -> { sql("UPDATE dh_governance_quality_rule SET param_schema='{\"column\":\"changed\"}' WHERE id=1"); return null; })
                .when(executor).validateEtlDraftRules(any(), any(), anyList(), anyList());
        assertThrows(RuntimeException.class, () -> service.create(request()));
        assertEquals(0, count("dh_governance_quality_task")); assertEquals(0, count("dh_governance_quality_etl_task_creation"));
    }

    @Test void generatedTaskRulesCanAuditOnlyTheSpecifiedCommittedBatch() throws Exception {
        var req = request(); req.setActivate(true); var receipt = service.create(req);
        sql("CREATE TABLE target(id text,_etl_batch_key text,_quality_status text)");
        sql("INSERT INTO target VALUES ('1','batch-a','PASS'),('2','batch-a','WARN'),(null,'batch-a','REJECT'),(null,'batch-b','PASS')");
        var real = QualityRuntimeFixture.executor(); ReflectionTestUtils.setField(real, "auditSqlRegistry", new AuditSqlRegistry());
        ReflectionTestUtils.setField(real, "auditLogMapper", mock(QualityAuditLogMapper.class));
        var task = app.getBean(QualityTaskMapper.class).selectById(receipt.taskId());
        var object = app.getBean(QualityCheckObjectMapper.class).selectById(receipt.checkObjectId());
        var rule = app.getBean(QualityRuleMapper.class).selectById(1L); rule.setCollectIssueDetail(0);
        var route = new MasterDatabaseResolver.ConnectionInfo(); route.setDatabaseId(60L); route.setProductType(ProductTypeEnum.POSTGRESQL);
        route.setSchema(schema); route.setConfigurationRevision("etl-create-pg-test"); route.setUsageType(1);
        var scope = new QualityTaskExecutor.EtlBatchScope(); scope.setBatchColumn("_etl_batch_key"); scope.setBatchValue("batch-a");
        scope.setStatusColumn("_quality_status"); scope.setAdmittedStatuses(List.of("PASS", "WARN")); scope.setAdmittedRowCount(2L);
        cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityAuditLogDO audit = ReflectionTestUtils.invokeMethod(real,
                "executeRule", 100L, task, control, route, object, rule, scope);
        assertNotNull(audit); assertEquals("PASS", audit.getResultType(), audit.getErrorDetail());
        assertEquals(2L, audit.getTotalCount()); assertEquals(0L, audit.getFailCount());
    }

    private <T> void registerMapper(Class<T> type, SqlSessionTemplate sessions) { app.registerBean(type, () -> sessions.getMapper(type)); }
    private QualityEtlTaskCreateReqVO request() {
        var req = new QualityEtlTaskCreateReqVO(); req.setRequestId(UUID.randomUUID().toString()); req.setTaskName("目标独立质检");
        req.setDatabaseId(60L); req.setSchemaName(schema); req.setTableName("target"); req.setRuleIds(List.of(1L)); return req;
    }
    private static void login(long actor) {
        TenantContextHolder.clear(); SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(actor), new MockHttpServletRequest());
    }
    private void createTable(String name, String columns) throws SQLException {
        sql("CREATE TABLE " + name + "(id bigint PRIMARY KEY," + columns + ",creator text,updater text,create_time timestamp DEFAULT now(),update_time timestamp DEFAULT now(),deleted smallint DEFAULT 0)");
    }
    private long count(String table) throws SQLException { try (var s = control.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + table)) { r.next(); return r.getLong(1); } }
    private void sql(String text) throws SQLException { try (var s = control.createStatement()) { s.execute(text); } }
}
