package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiOperationMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class DataApiOperationPostgreSqlTest extends cn.iocoder.yudao.module.supply.service.app.SharingPostgresSupport {
    @Override protected boolean useLegacyFixture() { return false; }
    DataApiOperationService service;
    DataApiOperationMapper mapper;
    JdbcTemplate jdbc;
    DataApiDO api;
    String migration; String singleTenantMigration;

    @BeforeEach void prepare() throws Exception {
        var ds = new UnpooledDataSource("org.postgresql.Driver", jdbcUrl
                + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=datahub-center",
                jdbcUser, jdbcPassword);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE; CREATE SCHEMA \"datahub-center\"");
        jdbc.execute("""
            CREATE TABLE dh_supply_api_log(id bigint PRIMARY KEY, tenant_id bigint);
            CREATE SEQUENCE system_menu_seq START 100;
            CREATE TABLE system_menu(id bigint PRIMARY KEY, name text, permission text, type int, sort int,
              parent_id bigint, path text, icon text, component text, component_name text, status int,
              visible boolean, keep_alive boolean, always_show boolean, creator text, updater text,
              create_time timestamp, update_time timestamp, deleted smallint DEFAULT 0);
            INSERT INTO system_menu(id,parent_id,name,type,component) VALUES
              (1,10,'API',2,'supply/dataapi/index'),(2,20,'数据源',2,'dataforge/database/index');
            CREATE TABLE target_counter(id int PRIMARY KEY, value int NOT NULL);
            INSERT INTO target_counter VALUES(1,0);
            """);
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        migration = migrationSql("0090_supply_write-operation-control.sql");
        jdbc.execute(migration);
        singleTenantMigration = migrationSql("0169_datahub_remove-business-tenancy.sql");
        jdbc.execute(singleTenantMigration);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("operations", new SpringManagedTransactionFactory(), ds));
        config.addMapper(DataApiOperationMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(DataApiOperationMapper.class);
        service = new DataApiOperationService();
        ReflectionTestUtils.setField(service, "operationMapper", mapper);
        ReflectionTestUtils.setField(service, "transactionManager", new DataSourceTransactionManager(ds));
        api = DataApiDO.builder().id(4L).activeReleaseId(9L).apiType("UPDATE").configType("SQL")
                .sqlText("update target_counter set value=value+1 where id=1").build();
        }
    @AfterEach void clear() {

        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    Map<String,Object> params(String key) { return Map.of("_operationKey", key, "value", "123456789012345678.12"); }
    DataApiExecuteRespVO write() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        jdbc.update("UPDATE target_counter SET value=value+1 WHERE id=1");
        var result = new DataApiExecuteRespVO();
        result.setRows(List.of(Map.of("affectedRows", 1L, "secret", "MUST_NOT_PERSIST")));
        return result;
    }

    @Test void migrationReplaysAndCommittedWriteReplaysWithoutRepeatingExternalSideEffect() {
        jdbc.execute(singleTenantMigration);
        var first = service.execute(api, 3L, params("operation-0001"), this::write);
        var second = service.execute(api, 3L, params("operation-0001"), () -> { throw new AssertionError("must not run twice"); });
        assertThat(first.getOperationStatus()).isEqualTo("SUCCEEDED");
        assertThat(second.getRows()).isEqualTo(first.getRows());
        assertThat(second.getReplayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT value FROM target_counter", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT result_json FROM dh_supply_api_operation", String.class)).doesNotContain("MUST_NOT_PERSIST");
        assertThatThrownBy(() -> service.execute(api, 3L, Map.of("_operationKey", "operation-0001", "value", "changed"), this::write))
                .hasMessageContaining("其他参数或发布版本");
        api.setActiveReleaseId(10L);
        assertThatThrownBy(() -> service.execute(api, 3L, params("operation-0001"), this::write)).hasMessageContaining("其他参数或发布版本");
        assertThat(service.execute(api, 3L, Map.of("_operationKey", "operation-0001", "_operationMode", "STATUS"), this::write)
                .getOperationStatus()).isEqualTo("SUCCEEDED");
    }

    @Test void committedRemoteWriteThenLostReplyStaysUnknownAndCannotBeRepeated() {
        var result = service.execute(api, 3L, params("operation-0002"), () -> {
            write(); throw new IllegalStateException("lost remote reply WITH_SECRET");
        });
        assertThat(result.getOperationStatus()).isEqualTo("UNKNOWN");
        assertThat(service.execute(api, 3L, params("operation-0002"), this::write).getOperationStatus()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT value FROM target_counter", Integer.class)).isEqualTo(1);
        assertThat(result.getRows()).isEmpty();
        assertThat(mapper.find(3L,4L,"operation-0002").getResultJson()).isNull();
    }

    @Test void crashedWorkerLeaseNeverPermitsAutomaticRedispatch() {
        mapper.claim(3L,4L,9L,"operation-0003",DataApiOperationService.requestHash(api, Map.of("value","123456789012345678.12")),null);
        jdbc.update("UPDATE dh_supply_api_operation SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 minute'");
        assertThat(service.reconcileExpired()).isEqualTo(1);
        assertThat(service.execute(api,3L,params("operation-0003"),this::write).getOperationStatus()).isEqualTo("UNKNOWN");
        assertThat(service.history(null,null,null)).hasSize(1);
        assertThat(service.execute(api,3L,params("operation-0003"),this::write).getOperationStatus()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dh_supply_api_operation",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT value FROM target_counter",Integer.class)).isZero();
    }

    @Test void concurrentNodesOnlyClaimOnce() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(6)) {
            var start = new CountDownLatch(1);
            List<Future<DataApiExecuteRespVO>> futures = new ArrayList<>();
            for (int index=0; index<6; index++) futures.add(executor.submit(() -> {
                try { start.await(); return service.execute(api,3L,params("operation-0004"),() -> { actions.incrementAndGet(); return write(); }); }
                finally { }
            }));
            start.countDown();
            for (var future : futures) assertThat(future.get(20, TimeUnit.SECONDS).getOperationStatus()).isIn("RUNNING","SUCCEEDED");
        }
        assertThat(actions).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT value FROM target_counter",Integer.class)).isEqualTo(1);
    }

    @Test void failedReceiptPersistenceDoesNotPermitSecondWrite() {
        var broken = org.mockito.Mockito.spy(mapper);
        org.mockito.Mockito.doThrow(new IllegalStateException("receipt unavailable")).when(broken).finish( org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("SUCCEEDED"), org.mockito.ArgumentMatchers.anyString());
        ReflectionTestUtils.setField(service,"operationMapper",broken);
        assertThat(service.execute(api,3L,params("operation-0005"),this::write).getOperationStatus()).isEqualTo("UNKNOWN");
        assertThat(service.execute(api,3L,params("operation-0005"),this::write).getOperationStatus()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT value FROM target_counter",Integer.class)).isEqualTo(1);
    }

    @Test void postForwardIsGuardedAndRetainsOnlyOutcome() {
        api.setApiType("QUERY"); api.setConfigType("FORWARD"); api.setMethod("POST");
        assertThat(DataApiOperationService.requiresOperation(api)).isTrue();
        var first=service.execute(api,3L,params("operation-0006"),() -> {
            var response=new DataApiExecuteRespVO(); response.setRaw("PRIVATE_UPSTREAM_BODY"); return response;
        });
        assertThat(first.getRaw()).isEqualTo("PRIVATE_UPSTREAM_BODY");
        var replay=service.execute(api,3L,params("operation-0006"),() -> { throw new AssertionError("must not resend upstream"); });
        assertThat(replay.getRaw()).isNull();
        assertThat(replay.getOperationStatus()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT result_json FROM dh_supply_api_operation",String.class)).doesNotContain("PRIVATE_UPSTREAM_BODY");
        api.setForwardMethod("GET");
        assertThat(DataApiOperationService.requiresOperation(api)).isFalse();
    }

    @Test void manualDispositionRequiresActorAndRejectsConflictingUpdates() {
        var unknown = service.execute(api,3L,params("operation-0007"),() -> {
            throw new IllegalStateException("remote outcome unknown");
        });
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.resolve(unknown.getOperationId(),"CONFIRMED_NOT_APPLIED","已核验","ticket-001"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        var login = new cn.iocoder.yudao.framework.security.core.LoginUser();
        login.setId(42L); cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.setLoginUser(login,
                new org.springframework.mock.web.MockHttpServletRequest());
        var result = service.resolve(unknown.getOperationId(),"CONFIRMED_NOT_APPLIED","已核验","ticket-001");
        assertThat(result.getState()).isEqualTo("CONFIRMED_NOT_APPLIED");
        assertThat(result.getResolvedBy()).isEqualTo(42L);
        assertThat(service.resolve(unknown.getOperationId(),"CONFIRMED_NOT_APPLIED","已核验","ticket-001").getState())
                .isEqualTo("CONFIRMED_NOT_APPLIED");
        assertThatThrownBy(() -> service.resolve(unknown.getOperationId(),"CONFIRMED_SUCCEEDED","更改结论","ticket-002"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(service.execute(api,3L,params("operation-0007"),this::write).getOperationStatus())
                .isEqualTo("CONFIRMED_NOT_APPLIED");
        assertThat(service.history(null,null,null,"operation-0007")).hasSize(1);
        assertThat(service.history(null,null,null,"other-operation")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT value FROM target_counter",Integer.class)).isZero();
    }
}
