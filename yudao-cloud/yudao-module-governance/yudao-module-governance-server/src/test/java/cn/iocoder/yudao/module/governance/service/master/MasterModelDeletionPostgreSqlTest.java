package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 私有本机基线克隆与随机 schema；真实事务、租户插件和模型发布行锁。 */
@EnabledIfSystemProperty(named = "datahub.test.master.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class MasterModelDeletionPostgreSqlTest {
    private Connection control;
    private String schema;
    private MasterModelMapper models;
    private MasterColumnMapper columns;
    private MasterModelService service;
    private DataSourceTransactionManager manager;
    private MasterDatabaseResolver resolver;
    private DbSwitchRuntime runtime;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getProperty("datahub.test.master.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        control = DriverManager.getConnection(url, "codex_test", "");
        schema = "masterdelete_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("SET search_path TO " + schema);
        for (String table : List.of("dh_governance_master_domain", "dh_governance_master_model", "dh_governance_master_column",
                "dh_governance_master_distribute", "dh_governance_master_code_rule", "dh_governance_master_data_change",
                "dh_governance_master_change_log")) {
            sql("CREATE TABLE " + table + " (LIKE \"datahub-center\"." + table + " INCLUDING ALL)");
            sql("CREATE SEQUENCE " + table + "_seq");
            sql("ALTER TABLE " + table + " ALTER COLUMN id SET DEFAULT nextval('" + schema + "." + table + "_seq')");
        }
        sql("INSERT INTO dh_governance_master_domain(id,code,name) VALUES(10,'person','person'),(20,'other','other')");
        sql("INSERT INTO dh_governance_master_model(id,domain_id,database_id,table_name,table_cn,status,version,publish_state,release_time) VALUES(100,10,11,'md_person','person',2,1,'READY',CURRENT_TIMESTAMP),(200,20,22,'md_other','other',2,1,'READY',CURRENT_TIMESTAMP),(300,10,11,'md_released','released',1,1,'READY',CURRENT_TIMESTAMP)");
        sql("INSERT INTO dh_governance_master_column(id,master_model_id,name,field_type,field_length,nullable,pk,sort) VALUES(101,100,'code',2,64,0,1,0),(201,200,'code',2,64,0,1,0),(301,300,'code',2,64,0,1,0)");
        sql("INSERT INTO dh_governance_master_data_change(id,model_id,request_id,operation,status,payload_hash,rows_json,before_json,row_keys_json,applicant_id,approval_required) VALUES(9,100,'00000000-0000-0000-0000-000000000009','UPDATE','APPLIED','fixture','[]','[]','[]',99,false)");
        sql("INSERT INTO dh_governance_master_change_log(id,change_id,model_id,row_ordinal,data_id,change_type,property_name,before_value,after_value,changed_by,changed_at) VALUES(19,9,100,0,'P1','UPDATE','name','old','kept',99,CURRENT_TIMESTAMP)");
        sql("CREATE TABLE md_person(code varchar(64) PRIMARY KEY, name varchar(64))");
        sql("INSERT INTO md_person VALUES('P1','physical-data-kept')");

        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, "codex_test", "");
        manager = new DataSourceTransactionManager(source);
        var config = new MybatisConfiguration();
        GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new DefaultDBFieldHandler());
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("master-delete-test", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();
        config.addInterceptor(interceptor);
        for (Class<?> mapper : List.of(MasterModelMapper.class, MasterColumnMapper.class, MasterDistributeMapper.class,
                MasterCodeRuleMapper.class, MasterDataChangeMapper.class)) config.addMapper(mapper);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        models = session.getMapper(MasterModelMapper.class);
        columns = session.getMapper(MasterColumnMapper.class);
        var target = new MasterModelServiceImpl();
        ReflectionTestUtils.setField(target, "logRecordService", mock(com.mzt.logapi.service.ILogRecordService.class));
        resolver = mock(MasterDatabaseResolver.class);
        runtime = mock(DbSwitchRuntime.class);
        ReflectionTestUtils.setField(target, "modelMapper", models);
        ReflectionTestUtils.setField(target, "columnMapper", columns);
        ReflectionTestUtils.setField(target, "distributeMapper", session.getMapper(MasterDistributeMapper.class));
        ReflectionTestUtils.setField(target, "codeRuleMapper", session.getMapper(MasterCodeRuleMapper.class));
        ReflectionTestUtils.setField(target, "dataChangeMapper", session.getMapper(MasterDataChangeMapper.class));
        ReflectionTestUtils.setField(target, "databaseResolver", resolver);
        ReflectionTestUtils.setField(target, "dbSwitchRuntime", runtime);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (MasterModelService) proxy.getProxy();
        login();
    }

    @AfterEach
    void cleanup() throws Exception {
        clearContext();
        if (control != null) {
            try {
                if (schema != null && schema.matches("masterdelete_[a-f0-9]{32}")) sql("DROP SCHEMA " + schema + " CASCADE");
            } finally { control.close(); }
        }
    }

    @Test
    void offlineDeletionOnlyChangesRequestedModelAndKeepsPhysicalDataAndHistory() throws Exception {
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
        service.deleteModel(100L);
        assertNull(models.selectById(100L));
        assertTrue(columns.selectListByModelId(100L).isEmpty());
        assertEquals(1, number("SELECT deleted FROM dh_governance_master_model WHERE id=100"));
        assertEquals(1, number("SELECT deleted FROM dh_governance_master_column WHERE id=101"));
        ServiceException missing = assertThrows(ServiceException.class, () -> service.deleteModel(9999L));
        assertEquals(MASTER_MODEL_NOT_EXISTS.getCode(), missing.getCode());
        assertEquals(0, number("SELECT deleted FROM dh_governance_master_model WHERE id=200"));
        assertEquals(0, number("SELECT deleted FROM dh_governance_master_column WHERE id=201"));
        assertProtectedRows();
    }

    @Test
    void batchFailureRollsBackEarlierModelAndColumnDeletion() throws Exception {
        ServiceException failure = assertThrows(ServiceException.class, () -> service.deleteModelList(List.of(300L, 100L, 100L)));
        assertEquals(MASTER_MODEL_STRUCTURE_FROZEN.getCode(), failure.getCode());
        assertNotNull(models.selectById(100L));
        assertEquals(1, columns.selectListByModelId(100L).size());
        assertEquals(0, number("SELECT deleted FROM dh_governance_master_model WHERE id IN (100,300) ORDER BY id LIMIT 1"));
        assertEquals(0, number("SELECT deleted FROM dh_governance_master_column WHERE id=101"));
        assertProtectedRows();
    }

    @Test
    void deletionWaitsForPublishClaimThenRejectsApplyingModel() throws Exception {
        MasterModelDO snapshot = models.selectById(100L);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch deleteEntered = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> publisher = pool.submit(() -> {
                login();
                try {
                    new TransactionTemplate(manager).executeWithoutResult(status -> {
                        assertEquals(1, models.claimPublish(snapshot));
                        claimed.countDown();
                        await(release);
                    });
                } finally { clearContext(); }
            });
            assertTrue(claimed.await(5, TimeUnit.SECONDS));
            Future<?> deletion = pool.submit(() -> {
                login();
                try { deleteEntered.countDown(); service.deleteModel(100L); }
                finally { clearContext(); }
            });
            assertTrue(deleteEntered.await(5, TimeUnit.SECONDS));
            try { assertThrows(TimeoutException.class, () -> deletion.get(200, TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            publisher.get(5, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> deletion.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ServiceException.class, failure.getCause());
            assertEquals(MASTER_MODEL_PUBLISH_UNCERTAIN.getCode(), ((ServiceException) failure.getCause()).getCode());
        }
        assertEquals(MasterModelDO.PUBLISH_APPLYING, models.selectById(100L).getPublishState());
        assertEquals(0, number("SELECT deleted FROM dh_governance_master_column WHERE id=101"));
        assertProtectedRows();
    }

    @Test
    void publishClaimWaitsForDeletionThenCannotReviveModel() throws Exception {
        MasterModelDO snapshot = models.selectById(100L);
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch publishEntered = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> deletion = pool.submit(() -> {
                login();
                try {
                    new TransactionTemplate(manager).executeWithoutResult(status -> {
                        service.deleteModel(100L);
                        deleted.countDown();
                        await(release);
                    });
                } finally { clearContext(); }
            });
            assertTrue(deleted.await(5, TimeUnit.SECONDS));
            Future<Integer> publisher = pool.submit(() -> {
                login();
                try { publishEntered.countDown(); return models.claimPublish(snapshot); }
                finally { clearContext(); }
            });
            assertTrue(publishEntered.await(5, TimeUnit.SECONDS));
            try { assertThrows(TimeoutException.class, () -> publisher.get(200, TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            deletion.get(5, TimeUnit.SECONDS);
            assertEquals(0, publisher.get(5, TimeUnit.SECONDS));
        }
        assertNull(models.selectById(100L));
        assertEquals(1, number("SELECT deleted FROM dh_governance_master_column WHERE id=101"));
        assertProtectedRows();
    }

    private void assertProtectedRows() throws Exception {
        assertEquals(1, number("SELECT count(*) FROM md_person WHERE code='P1' AND name='physical-data-kept'"));
        assertEquals(1, number("SELECT count(*) FROM dh_governance_master_data_change WHERE id=9 AND status='APPLIED' AND deleted=0"));
        assertEquals(1, number("SELECT count(*) FROM dh_governance_master_change_log WHERE id=19 AND after_value='kept' AND deleted=0"));
        verifyNoInteractions(resolver, runtime);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private static void login() {
        TenantContextHolder.clear();
        TenantContextHolder.setIgnore(false);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L), new MockHttpServletRequest());
    }

    private static void clearContext() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
    private void sql(String query) throws Exception { try (var statement = control.createStatement()) { statement.execute(query); } }
    private long number(String query) throws Exception {
        try (var statement = control.createStatement(); var result = statement.executeQuery(query)) {
            assertTrue(result.next()); return result.getLong(1);
        }
    }
}
