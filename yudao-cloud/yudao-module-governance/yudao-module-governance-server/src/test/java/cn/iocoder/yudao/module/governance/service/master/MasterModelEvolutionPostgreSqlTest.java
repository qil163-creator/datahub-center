package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterColumnVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.service.metadata.MetaImportService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.mzt.logapi.service.ILogRecordService;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** 私有本机基线克隆；真实 Mapper、租户、事务代理及 DB Switch PostgreSQL 增量发布。 */
@EnabledIfSystemProperty(named = "datahub.test.master.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class MasterModelEvolutionPostgreSqlTest {
    private Connection control;
    private String schema;
    private String url;
    private MasterModelMapper models;
    private MasterColumnMapper columns;
    private MasterDistributeMapper distributions;
    private MasterModelService service;
    private DataSourceTransactionManager manager;
    private MasterDatabaseResolver resolver;
    private DbSwitchRuntime runtime;
    private ILogRecordService audit;
    private final List<String> successfulDdl = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        url = System.getProperty("datahub.test.master.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        control = DriverManager.getConnection(url, "codex_test", "");
        schema = "masterevolve_" + UUID.randomUUID().toString().replace("-", "");
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
        sql("INSERT INTO dh_governance_master_model(id,domain_id,database_id,table_name,table_cn,status,version,publish_state,release_time,release_user_id) VALUES(100,10,11,'md_person','person',1,1,'READY',CURRENT_TIMESTAMP,99),(200,20,22,'md_other','other',1,1,'READY',CURRENT_TIMESTAMP,98)");
        sql("INSERT INTO dh_governance_master_column(id,master_model_id,name,field_type,field_length,nullable,pk,sort) VALUES(101,100,'code',2,64,0,1,0),(102,100,'name',2,64,1,0,1),(201,200,'code',2,64,0,1,0)");
        sql("INSERT INTO dh_governance_master_data_change(id,model_id,request_id,operation,status,payload_hash,rows_json,before_json,row_keys_json,applicant_id,approval_required) VALUES(9,100,'00000000-0000-0000-0000-000000000009','UPDATE','APPLIED','fixture','[]','[]','[]',99,false)");
        sql("INSERT INTO dh_governance_master_change_log(id,change_id,model_id,row_ordinal,data_id,change_type,property_name,before_value,after_value,changed_by,changed_at) VALUES(19,9,100,0,'P1','UPDATE','name','old','physical-data-kept',99,CURRENT_TIMESTAMP)");
        sql("CREATE TABLE md_person(code varchar(64) PRIMARY KEY, name varchar(64))");
        sql("INSERT INTO md_person VALUES('P1','physical-data-kept')");

        var dataSource = new DriverManagerDataSource(url + "?currentSchema=" + schema, "codex_test", "");
        manager = new DataSourceTransactionManager(dataSource);
        var config = new MybatisConfiguration();
        GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new DefaultDBFieldHandler());
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("master-evolution-test", new SpringManagedTransactionFactory(), dataSource));
        var interceptor = new MybatisPlusInterceptor();
        config.addInterceptor(interceptor);
        for (Class<?> mapper : List.of(MasterDomainMapper.class, MasterModelMapper.class, MasterColumnMapper.class,
                MasterDistributeMapper.class, MasterCodeRuleMapper.class, MasterDataChangeMapper.class)) config.addMapper(mapper);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        models = session.getMapper(MasterModelMapper.class);
        columns = session.getMapper(MasterColumnMapper.class);
        distributions = session.getMapper(MasterDistributeMapper.class);
        var target = new MasterModelServiceImpl();
        resolver = mock(MasterDatabaseResolver.class);
        runtime = mock(DbSwitchRuntime.class);
        var connectionInfo = new MasterDatabaseResolver.ConnectionInfo();
        connectionInfo.setDatabaseId(11L);
        connectionInfo.setProductType(ProductTypeEnum.POSTGRESQL);
        connectionInfo.setJdbcUrl(url);
        connectionInfo.setUsername("codex_test");
        connectionInfo.setPassword("");
        connectionInfo.setSchema(schema);
        when(resolver.resolve(eq(11L), any())).thenReturn(connectionInfo);
        when(runtime.products()).thenReturn(DbSwitchProductRegistry.discover());
        when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL)).thenReturn(mock(MetaDataByJdbcServiceImpl.class));
        when(runtime.openConnection(ProductTypeEnum.POSTGRESQL, null, url, "codex_test", "")).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "外部 DDL 不能处于平台事务中");
            // NOT_SUPPORTED 可创建空同步上下文；独立连接读到领取结果证明平台写入已提交。
            assertEquals(MasterModelDO.PUBLISH_APPLYING, text("SELECT publish_state FROM dh_governance_master_model WHERE id=100"));
            return observedConnection();
        });
        var publisher = new MasterModelSchemaPublisher();
        ReflectionTestUtils.setField(publisher, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(target, "modelMapper", models);
        ReflectionTestUtils.setField(target, "columnMapper", columns);
        ReflectionTestUtils.setField(target, "domainMapper", session.getMapper(MasterDomainMapper.class));
        ReflectionTestUtils.setField(target, "distributeMapper", distributions);
        ReflectionTestUtils.setField(target, "codeRuleMapper", session.getMapper(MasterCodeRuleMapper.class));
        ReflectionTestUtils.setField(target, "dataChangeMapper", session.getMapper(MasterDataChangeMapper.class));
        ReflectionTestUtils.setField(target, "databaseResolver", resolver);
        ReflectionTestUtils.setField(target, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(target, "schemaPublisher", publisher);
        audit = mock(ILogRecordService.class);
        ReflectionTestUtils.setField(target, "logRecordService", audit);
        var imports = mock(MetaImportService.class);
        when(imports.registerTable(any())).thenReturn(900L);
        ReflectionTestUtils.setField(target, "metaImportService", imports);
        ReflectionTestUtils.setField(target, "metaTableMapper", mock(MetaTableMapper.class));
        ReflectionTestUtils.setField(target, "metaColumnMapper", mock(MetaColumnMapper.class));
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
                if (schema != null && schema.matches("masterevolve_[a-f0-9]{32}")) sql("DROP SCHEMA " + schema + " CASCADE");
            } finally { control.close(); }
        }
    }

    @Test
    void publishedStructureEditBecomesDraftAndOnlyRepublishingAddsNullableColumn() throws Exception {
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
        MasterModelDO previous = models.selectById(100L);
        service.updateModel(request(1, field("phone", 0, 1)));
        MasterModelDO draft = models.selectById(100L);
        assertEquals(0, draft.getStatus());
        assertEquals(2, draft.getVersion());
        assertEquals(previous.getReleaseTime(), draft.getReleaseTime());
        assertEquals(previous.getReleaseUserId(), draft.getReleaseUserId());
        assertEquals(List.of("code", "name", "phone"), columns.selectListByModelId(100L).stream().map(column -> column.getName()).toList());
        assertEquals(0, physicalColumnCount("phone"));
        verifyNoInteractions(resolver, runtime);

        service.releaseModel(100L);
        MasterModelDO released = models.selectById(100L);
        assertEquals(1, released.getStatus());
        assertEquals(2, released.getVersion());
        assertEquals(MasterModelDO.PUBLISH_READY, released.getPublishState());
        assertEquals(1, physicalColumnCount("phone"));
        assertEquals("YES", text("SELECT is_nullable FROM information_schema.columns WHERE table_schema='" + schema + "' AND table_name='md_person' AND column_name='phone'"));
        assertEquals(1, number("SELECT count(*) FROM md_person WHERE code='P1' AND phone IS NULL"));
        assertProtectedRows();
    }

    @Test
    void failedNotNullAdditionRollsBackEarlierDdlAndKeepsEditableDraftForRetry() throws Exception {
        service.updateModel(request(1, field("added_first", 0, 1), field("must_have", 0, 0)));
        assertCode(MASTER_MODEL_RELEASE_FAILED.getCode(), () -> service.releaseModel(100L));
        assertTrue(successfulDdl.stream().anyMatch(statement -> statement.contains("ADD COLUMN \"added_first\"")), "已成功执行的前序 ADD 也必须回滚");
        assertEquals(0, physicalColumnCount("added_first"));
        assertEquals(0, physicalColumnCount("must_have"));
        MasterModelDO failed = models.selectById(100L);
        assertEquals(0, failed.getStatus());
        assertEquals(2, failed.getVersion());
        assertEquals(MasterModelDO.PUBLISH_READY, failed.getPublishState());
        assertProtectedRows();

        service.updateModel(request(2, field("added_first", 0, 1), field("must_have", 0, 1)));
        assertEquals(3, models.selectById(100L).getVersion());
        service.releaseModel(100L);
        assertEquals(1, models.selectById(100L).getStatus());
        assertEquals(1, physicalColumnCount("added_first"));
        assertEquals(1, physicalColumnCount("must_have"));
        assertProtectedRows();
    }

    @Test
    void staleExpectedVersionCannotOverwriteNewDraftOrColumns() throws Exception {
        service.updateModel(request(1, field("phone", 0, 1)));
        List<Long> savedColumnIds = columns.selectListByModelId(100L).stream().map(column -> column.getId()).toList();
        assertCode(MASTER_MODEL_PUBLISH_CONFLICT.getCode(), () -> service.updateModel(request(1, field("stale", 0, 1))));
        assertEquals(2, models.selectById(100L).getVersion());
        assertEquals(savedColumnIds, columns.selectListByModelId(100L).stream().map(column -> column.getId()).toList());
        assertEquals(0, number("SELECT count(*) FROM dh_governance_master_column WHERE master_model_id=100 AND name='stale'"));
        verifyNoInteractions(resolver, runtime);
        assertProtectedRows();
    }

    @Test
    void staleDraftSnapshotCannotClaimPublicationAfterAnotherStructuralSave() throws Exception {
        service.updateModel(request(1, field("phone", 0, 1)));
        MasterModelDO snapshot = models.selectById(100L);
        service.updateModel(request(2, field("phone", 0, 1), field("address", 0, 1)));
        assertEquals(0, models.claimPublish(snapshot));
        assertEquals(3, models.selectById(100L).getVersion());
        assertEquals(MasterModelDO.PUBLISH_READY, models.selectById(100L).getPublishState());
        verifyNoInteractions(resolver, runtime);
        assertEquals(0, physicalColumnCount("address"));
        assertProtectedRows();
    }

    @Test
    void missingModelCannotBeModifiedOrRepublished() throws Exception {
        var request = request(1, field("missing_model", 0, 1));
        request.setId(9999L);
        request.setDomainId(20L);
        request.setDatabaseId(22L);
        request.setTableName("missing_model");
        assertCode(MASTER_MODEL_NOT_EXISTS.getCode(), () -> service.updateModel(request));
        assertCode(MASTER_MODEL_NOT_EXISTS.getCode(), () -> service.releaseModel(9999L));
        assertEquals(1, number("SELECT version FROM dh_governance_master_model WHERE id=200"));
        assertEquals(1, number("SELECT status FROM dh_governance_master_model WHERE id=200"));
        assertEquals(0, number("SELECT count(*) FROM dh_governance_master_column WHERE master_model_id=200 AND name='missing_model'"));
        verifyNoInteractions(resolver, runtime);
        assertProtectedRows();
    }

    @Test
    void platformSaveCommitsBeforeExternalPublicationStarts() throws Exception {
        new TransactionTemplate(manager).executeWithoutResult(transaction -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            service.updateModel(request(1, field("phone", 0, 1)));
            assertEquals(0, models.selectById(100L).getStatus());
            verifyNoInteractions(resolver, runtime);
            verifyNoInteractions(audit);
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        verify(audit).record(any());
        assertEquals(0, number("SELECT status FROM dh_governance_master_model WHERE id=100"));
        service.releaseModel(100L);
        verify(runtime).openConnection(ProductTypeEnum.POSTGRESQL, null, url, "codex_test", "");
        assertEquals(1, models.selectById(100L).getStatus());
        verify(audit, times(2)).record(any());
        assertProtectedRows();
    }

    @Test
    void distributionReferenceWaitsForStructuralDraftCommitAndThenRejects() throws Exception {
        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch referenceEntered = new CountDownLatch(1);
        MasterModelMapper referenceModels = mock(MasterModelMapper.class, delegatesTo(models));
        doAnswer(invocation -> {
            referenceEntered.countDown();
            return models.selectForUpdate(invocation.getArgument(0));
        }).when(referenceModels).selectForUpdate(100L);
        var distributeTarget = new MasterDistributeServiceImpl();
        ReflectionTestUtils.setField(distributeTarget, "modelMapper", referenceModels);
        ReflectionTestUtils.setField(distributeTarget, "distributeMapper", distributions);
        var proxy = new ProxyFactory(distributeTarget);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        MasterDistributeService distribute = (MasterDistributeService) proxy.getProxy();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> editor = pool.submit(() -> {
                login();
                try {
                    new TransactionTemplate(manager).executeWithoutResult(transaction -> {
                        service.updateModel(request(1, field("phone", 0, 1)));
                        saved.countDown();
                        await(commit);
                    });
                } finally { clearContext(); }
            });
            assertTrue(saved.await(5, TimeUnit.SECONDS));
            Future<?> creator = pool.submit(() -> {
                login();
                try { distribute.createDistribute(distributeRequest()); }
                finally { clearContext(); }
            });
            try {
                assertTrue(referenceEntered.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> creator.get(200, TimeUnit.MILLISECONDS));
            } finally { commit.countDown(); }
            editor.get(5, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> creator.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ServiceException.class, failure.getCause());
            assertEquals(MASTER_DISTRIBUTE_MODEL_NOT_RELEASED.getCode(), ((ServiceException) failure.getCause()).getCode());
        }
        assertEquals(0, distributions.selectCountByModelId(100L));
        assertEquals(0, models.selectById(100L).getStatus());
        assertEquals(0, physicalColumnCount("phone"));
        verifyNoInteractions(resolver, runtime);
        assertProtectedRows();
    }

    /** 仅包装真实连接以记录已完成的 DDL；组件 SQL、元数据和回滚均实际执行。 */
    private Connection observedConnection() throws Exception {
        Connection real = DriverManager.getConnection(url, "codex_test", "");
        Connection connection = mock(Connection.class, delegatesTo(real));
        doAnswer(invocation -> {
            Statement realStatement = real.createStatement();
            Statement statement = mock(Statement.class, delegatesTo(realStatement));
            doAnswer(execution -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                String query = execution.getArgument(0);
                boolean result = realStatement.execute(query);
                if (query.startsWith("ALTER TABLE ")) successfulDdl.add(query);
                return result;
            }).when(statement).execute(anyString());
            return statement;
        }).when(connection).createStatement();
        return connection;
    }

    private static MasterColumnVO field(String name, int pk, int nullable) {
        var field = new MasterColumnVO();
        field.setName(name);
        field.setFieldType(2);
        field.setFieldLength(64);
        field.setPk(pk);
        field.setNullable(nullable);
        return field;
    }

    private static MasterModelSaveReqVO request(int version, MasterColumnVO... additional) {
        var request = new MasterModelSaveReqVO();
        request.setId(100L);
        request.setDomainId(10L);
        request.setDatabaseId(11L);
        request.setExpectedVersion(version);
        request.setTableName("md_person");
        request.setTableCn("person");
        var fields = new ArrayList<>(List.of(field("code", 1, 0), field("name", 0, 1)));
        fields.addAll(List.of(additional));
        request.setColumns(fields);
        return request;
    }

    private static MasterDistributeSaveReqVO distributeRequest() {
        var request = new MasterDistributeSaveReqVO();
        request.setMasterModelId(100L);
        request.setName("synthetic-reference");
        request.setDistributeType(1);
        request.setTaskType(1);
        var db = new MasterDistributeConfigVO.DbConfig();
        db.setDatabaseId(999L);
        var config = new MasterDistributeConfigVO();
        config.setDb(db);
        request.setConfig(config);
        return request;
    }

    private long physicalColumnCount(String column) throws Exception {
        assertTrue(column.matches("[a-z][a-z0-9_]*"));
        return number("SELECT count(*) FROM information_schema.columns WHERE table_schema='" + schema
                + "' AND table_name='md_person' AND column_name='" + column + "'");
    }

    private void assertProtectedRows() throws Exception {
        assertEquals(1, number("SELECT count(*) FROM md_person WHERE code='P1' AND name='physical-data-kept'"));
        assertEquals(1, number("SELECT count(*) FROM dh_governance_master_data_change WHERE id=9 AND status='APPLIED' AND deleted=0"));
        assertEquals(1, number("SELECT count(*) FROM dh_governance_master_change_log WHERE id=19 AND after_value='physical-data-kept' AND deleted=0"));
    }

    private static void assertCode(Integer code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(ServiceException.class, operation).getCode());
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
    private String text(String query) throws Exception {
        try (var statement = control.createStatement(); var result = statement.executeQuery(query)) {
            assertTrue(result.next()); return result.getString(1);
        }
    }
}
