package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.dataforge.api.etl.EtlTargetBatchApi;
import cn.iocoder.yudao.module.dataforge.api.etl.dto.EtlTargetBatchDescribeRespDTO;
import cn.iocoder.yudao.module.supply.dal.mysql.apicategory.ApiCategoryMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.*;
import cn.iocoder.yudao.module.supply.framework.config.SupplyDataApiProperties;
import com.baomidou.mybatisplus.core.*;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataApiEtlBatchPostgreSqlTest extends cn.iocoder.yudao.module.supply.service.app.SharingPostgresSupport {
    @Override protected boolean useLegacyFixture() { return false; }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    AnnotationConfigApplicationContext app;
    Connection connection;
    EtlTargetBatchApi remote;
    DataApiService service;
    String migration;

    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection(jdbcUrl,jdbcUser,jdbcPassword);
        sql("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE; CREATE SCHEMA \"datahub-center\"; SET search_path TO \"datahub-center\"");
        sql("""
            CREATE TABLE dh_supply_api(
             id bigint PRIMARY KEY,category_id bigint,name text,version text,path text,method text,result_format text,
             description text,ip_whitelist text,ip_rule_type text,limit_enabled int,limit_seconds int,limit_count int,status int,api_type text,
             auth_type text,cache_enabled int,cache_seconds int,breaker_enabled int,breaker_error_count int,breaker_window_seconds int,
             breaker_open_seconds int,alarm_enabled int,config_type text,database_id bigint,table_name text,sql_text text,forward_url text,
             forward_method text,request_params jsonb,response_fields jsonb,remark text,provider_dept_id bigint,resource_ref text,
             owner_user_id bigint,active_release_id bigint,draft_revision int,draft_json jsonb,contract_hash text,row_version int,
             creator text,updater text,create_time timestamp default now(),update_time timestamp default now(),deleted smallint default 0);
            CREATE SEQUENCE dh_supply_api_release_seq;
            CREATE TABLE dh_supply_api_release(id bigint PRIMARY KEY,api_id bigint,revision int,api_version text,
             config_json jsonb,config_hash text,contract_hash text,test_evidence_hash text,publisher text,publish_time timestamp,
             creator text,updater text,create_time timestamp default now(),update_time timestamp default now(),deleted smallint default 0,
             UNIQUE(api_id,revision));
            """);
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(root!=null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) root=root.getParent();
        migration = migrationSql("0095_supply_etl-batch-consumption.sql");
        sql(migration); sql(migration);
        sql("INSERT INTO dh_supply_api(id,name,version,path,method,result_format,status,api_type,auth_type,config_type,etl_batch_id,row_version,owner_user_id,provider_dept_id) VALUES(1,'batch','v1','/batch','GET','LIST',0,'QUERY','APPKEY','ETL_BATCH',10,0,99,1)");
        var ds = new DriverManagerDataSource(jdbcUrl+(jdbcUrl.contains("?")?"&":"?")+"currentSchema=datahub-center",jdbcUser,jdbcPassword);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("etl-publish",new SpringManagedTransactionFactory(),ds));
        config.addMapper(DataApiMapper.class); config.addMapper(DataApiReleaseMapper.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        app = new AnnotationConfigApplicationContext(); app.register(Transactions.class);
        app.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor() {
            @Override public boolean postProcessAfterInstantiation(Object bean, String name) {
                // Mockito 替身没有真实依赖；保留被测 Service 的真实 @Resource 与事务代理。
                return !mockingDetails(bean).isMock();
            }
        });
        app.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(ds));
        app.registerBean(DataApiMapper.class,()->sessions.getMapper(DataApiMapper.class));
        app.registerBean(DataApiReleaseMapper.class,()->sessions.getMapper(DataApiReleaseMapper.class));
        app.registerBean(ApiCategoryMapper.class,()->mock(ApiCategoryMapper.class));
        app.registerBean(AppApiMapper.class,()->mock(AppApiMapper.class));
        app.registerBean(SupplyDatabaseResolver.class,()->mock(SupplyDatabaseResolver.class));
        app.registerBean(DbSwitchRuntime.class,()->mock(DbSwitchRuntime.class));
        app.registerBean(DataApiExecutor.class,()->mock(DataApiExecutor.class));
        app.registerBean(SecurityFrameworkService.class,()->mock(SecurityFrameworkService.class));
        app.registerBean(SupplyDataApiProperties.class);
        remote=mock(EtlTargetBatchApi.class); app.registerBean(EtlTargetBatchApi.class,()->remote);
        app.registerBean(DataApiEtlBatchService.class); app.registerBean(DataApiServiceImpl.class); app.refresh();
        service=app.getBean(DataApiService.class); }
    @AfterEach void cleanup() throws Exception { if(app!=null)app.close(); if(connection!=null)connection.close();  }

    @Test void preflightHasNoTransactionOrRowLockAndPublicationPersistsFrozenBatch() throws Exception {
        when(remote.describe(any())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            try(var independent=DriverManager.getConnection(jdbcUrl,jdbcUser,jdbcPassword);var s=independent.createStatement()) {
                independent.setAutoCommit(false); s.execute("SELECT id FROM \"datahub-center\".dh_supply_api WHERE id=1 FOR UPDATE NOWAIT"); independent.rollback();
            }
            return CommonResult.success(description());
        });
        Long release=service.publishDataApi(1L,0); assertNotNull(release);
        assertEquals("a".repeat(64),scalarText("SELECT etl_batch_hash FROM dh_supply_api WHERE id=1"));
        assertEquals("10",scalarText("SELECT config_json->>'etlBatchId' FROM dh_supply_api_release"));
        assertEquals("dwd",scalarText("SELECT config_json->>'etlSchemaName' FROM dh_supply_api_release"));
        assertEquals("1",scalarText("SELECT row_version FROM dh_supply_api WHERE id=1"));
        assertEquals("3",scalarText("SELECT count(*) FROM pg_attribute WHERE attrelid='dh_supply_api'::regclass AND attname IN('etl_batch_id','etl_batch_hash','etl_schema_name') AND col_description(attrelid,attnum) IS NOT NULL"));
    }

    @Test void batchChangedDuringPreflightCannotBePublishedUnderOldEvidence() throws Exception {
        when(remote.describe(any())).thenAnswer(call->{ sql("UPDATE dh_supply_api SET etl_batch_id=11 WHERE id=1"); return CommonResult.success(description()); });
        assertThrows(RuntimeException.class,()->service.publishDataApi(1L,0));
        assertEquals("0",scalarText("SELECT count(*) FROM dh_supply_api_release"));
        assertEquals("0",scalarText("SELECT status FROM dh_supply_api WHERE id=1"));
    }

    @Test void unavailableBatchCannotBeActivatedOrRolledBackAndOldReleaseRemainsImmutable() throws Exception {
        when(remote.describe(any())).thenReturn(CommonResult.success(description()));
        var release=service.publishDataApi(1L,0);
        var before=scalarText("SELECT config_json::text FROM dh_supply_api_release");
        service.updateDataApiStatus(1L,0,1);
        var unavailable=description(); unavailable.setAvailable(false);
        when(remote.describe(any())).thenReturn(CommonResult.success(unavailable));
        assertThrows(RuntimeException.class,()->service.updateDataApiStatus(1L,1,2));
        assertThrows(RuntimeException.class,()->service.rollbackDataApi(1L,release,2));
        assertEquals(before,scalarText("SELECT config_json::text FROM dh_supply_api_release"));
        assertEquals("0",scalarText("SELECT status FROM dh_supply_api WHERE id=1"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void concurrentWhitelistOrDraftEditSurvivesAndRejectsStalePublication(boolean whitelist) throws Exception {
        when(remote.describe(any())).thenAnswer(call -> {
            if (whitelist) sql("UPDATE dh_supply_api SET ip_whitelist='127.0.0.1' WHERE id=1");
            else sql("UPDATE dh_supply_api SET draft_revision=1, draft_json=jsonb_build_object('name','new draft') WHERE id=1");
            return CommonResult.success(description());
        });
        assertThrows(RuntimeException.class, () -> service.publishDataApi(1L, 0));
        assertEquals("0", scalarText("SELECT count(*) FROM dh_supply_api_release"));
        assertEquals("0", scalarText("SELECT status FROM dh_supply_api WHERE id=1"));
        if (whitelist) assertEquals("127.0.0.1", scalarText("SELECT ip_whitelist FROM dh_supply_api WHERE id=1"));
        else assertEquals("new draft", scalarText("SELECT draft_json->>'name' FROM dh_supply_api WHERE id=1"));
    }
    private EtlTargetBatchDescribeRespDTO description(){var d=new EtlTargetBatchDescribeRespDTO();d.setBatchId(10L);d.setAvailable(true);d.setPayloadHash("a".repeat(64));d.setDatabaseId(3L);d.setSchemaName("dwd");d.setTableName("orders");return d;}
    protected void sql(String sql)throws SQLException{try(var s=connection.createStatement()){s.execute(sql);}}
    private String scalarText(String sql)throws SQLException{try(var s=connection.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
}
