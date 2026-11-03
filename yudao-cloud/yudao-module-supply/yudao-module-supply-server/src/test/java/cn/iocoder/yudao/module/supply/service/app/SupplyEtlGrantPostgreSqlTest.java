package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.api.etl.EtlTargetBatchApi;
import cn.iocoder.yudao.module.dataforge.api.etl.dto.EtlTargetBatchDescribeRespDTO;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiSaveReqVO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.*;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.service.app.grant.SupplyGrantReadService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import net.sf.jsqlparser.expression.*;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用本任务隔离 PostgreSQL；远端权限接口替身，本地授权、锁和 CAS 使用真实事务。 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class SupplyEtlGrantPostgreSqlTest extends SharingPostgresSupport {
    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement static class Transactions { }
    AnnotationConfigApplicationContext app;
    SupplyAppService service;
    EtlTargetBatchApi batches;
    AdminUserApi users;
    AdminUserRespDTO actor;
    MockedStatic<SecurityFrameworkUtils> security;

    @BeforeEach void prepare() throws Exception {
        for (String fixture : List.of("sharing-coordinator-prerequisites.sql", "sharing-legacy-entry-prerequisites.sql")) {
            try (var in = getClass().getResourceAsStream("/sql/" + fixture)) {
                sql(new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        migrate("0041_supply_grant-source-protocol.sql");
        migrate("0095_supply_etl-batch-consumption.sql");
        migrate("0097_supply_etl-grant-audit.sql"); migrate("0097_supply_etl-grant-audit.sql");
        migrate("0169_datahub_remove-business-tenancy.sql");
        sql("UPDATE dh_supply_api SET config_type='ETL_BATCH',auth_type='APPKEY',result_format='LIST',"
                + "etl_batch_id=50,etl_batch_hash='" + "a".repeat(64) + "',etl_schema_name='dwd',"
                + "contract_hash='" + "b".repeat(64) + "' WHERE id=10");
        var ds = new DriverManagerDataSource(jdbcUrl + "?currentSchema=datahub-center", jdbcUser, "");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("etl-admin-grant", new SpringManagedTransactionFactory(), ds));
        for (var type : List.of(SupplyAppMapper.class, AppApiMapper.class, DataApiMapper.class,
                SupplyGrantOpMapper.class, SupplyGrantSourceMapper.class, SupplyEtlGrantAuditMapper.class)) config.addMapper(type);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        app = new AnnotationConfigApplicationContext(); app.register(Transactions.class);
        app.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        app.registerBean(SupplyAppMapper.class, () -> sessions.getMapper(SupplyAppMapper.class));
        app.registerBean(AppApiMapper.class, () -> sessions.getMapper(AppApiMapper.class));
        app.registerBean(DataApiMapper.class, () -> sessions.getMapper(DataApiMapper.class));
        app.registerBean(SupplyGrantOpMapper.class, () -> sessions.getMapper(SupplyGrantOpMapper.class));
        app.registerBean(SupplyGrantSourceMapper.class, () -> sessions.getMapper(SupplyGrantSourceMapper.class));
        app.registerBean(SupplyEtlGrantAuditMapper.class, () -> sessions.getMapper(SupplyEtlGrantAuditMapper.class));
        users = mock(AdminUserApi.class); actor = user(7L);
        when(users.getUser(7L)).thenAnswer(call -> { assertNoTransactionOrLocks(); return CommonResult.success(actor); });
        when(users.getUser(88L)).thenAnswer(call -> { assertNoTransactionOrLocks(); return CommonResult.success(user(88L)); });
        batches = mock(EtlTargetBatchApi.class);
        when(batches.describe(any())).thenAnswer(call -> { assertNoTransactionOrLocks(); return CommonResult.success(description()); });
        app.registerBean(AdminUserApi.class, () -> users); app.registerBean(EtlTargetBatchApi.class, () -> batches);
        app.registerBean(SupplyEtlGrantPreflight.class); app.registerBean(SupplyGrantReadService.class);
        app.registerBean(SupplyEtlGrantAuditService.class);
        app.registerBean(SupplyAppServiceImpl.class); app.refresh(); service = app.getBean(SupplyAppService.class);
        security = mockStatic(SecurityFrameworkUtils.class); security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(7L);
    }

    @AfterEach void finish() {
        if (security != null) security.close(); if (app != null) app.close();
    }

    @Test void formalCreationFreezesCurrentActorAndIgnoresForgedSubjectAndSource() throws Exception {
        var request = request(10L); request.setPermissionUserId(999L); request.setSourceType("PERSONAL");
        Long id = service.createAppApi(request);
        assertEquals(1, scalar("SELECT count(*) FROM dh_supply_app_api WHERE id=" + id
                + " AND source_type='ADMIN_ETL_BATCH' AND protocol_version=0 AND permission_user_id=7"
                + " AND creator='7' AND updater='7' AND approved_contract_hash='" + "b".repeat(64)
                + "' AND source_id='admin-etl:user:7:app:1:api:10' AND purpose='ETL_BATCH:50:" + "a".repeat(64) + "'"));
        verify(batches).describe(argThat(req -> req.getExecutionUserId().equals(7L) && req.getBatchId().equals(50L)));
        assertEquals("ACTIVE", app.getBean(SupplyGrantReadService.class).describe( id).getMode());
        assertEquals(1, service.getAppApiList(1L).getFirst().getAuthStatus());
    }

    @ParameterizedTest @ValueSource(strings = {"inactive", "anonymous", "unavailable", "wrong-table"})
    void subjectOrSourceFailureCannotPersistAuthorization(String failure) throws Exception {
        if (failure.equals("inactive")) actor.setStatus(1);
        if (failure.equals("anonymous")) security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(null);
        if (failure.equals("unavailable") || failure.equals("wrong-table")) {
            var description = description();
            if (failure.equals("unavailable")) description.setAvailable(false); else description.setTableName("another_table");
            when(batches.describe(any())).thenReturn(CommonResult.success(description));
        }
        assertThrows(RuntimeException.class, () -> service.createAppApi(request(10L)));
        assertEquals(0, scalar("SELECT count(*) FROM dh_supply_app_api"));
        assertEquals(0, scalar("SELECT count(*) FROM dh_supply_etl_grant_audit"));
    }

    @Test void publicationChangedDuringPermissionRpcCannotReceiveOldEvidence() throws Exception {
        when(batches.describe(any())).thenAnswer(call -> {
            assertNoTransactionOrLocks();
            sql("UPDATE dh_supply_api SET active_release_id=101,row_version=row_version+1 WHERE id=10");
            return CommonResult.success(description());
        });
        assertThrows(RuntimeException.class, () -> service.createAppApi(request(10L)));
        assertEquals(0, scalar("SELECT count(*) FROM dh_supply_app_api"));
    }

    @Test void resumePreservesGrantedSubjectAndPermanentRevokeRequiresFreshAuthorization() throws Exception {
        Long id = service.createAppApi(request(10L));
        service.setGrantControl(id, 0L, true);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(88L);
        clearInvocations(batches);
        service.enableAppApi(id, 1L);
        verify(batches).describe(argThat(req -> req.getExecutionUserId().equals(7L)));
        assertEquals(3, scalar("SELECT grant_revision FROM dh_supply_app_api WHERE id=" + id));
        service.revokeAdminAppApi(id, 2L);
        assertThrows(RuntimeException.class, () -> service.enableAppApi(id, 3L));
        assertEquals("NONE", app.getBean(SupplyGrantReadService.class).describe( id).getMode());
        assertEquals(id, service.createAppApi(request(10L)));
        assertEquals(88, scalar("SELECT permission_user_id FROM dh_supply_app_api WHERE id=" + id));
        assertEquals(5, scalar("SELECT grant_revision FROM dh_supply_app_api WHERE id=" + id));
        assertEquals(5, scalar("SELECT count(*) FROM dh_supply_etl_grant_audit WHERE grant_id=" + id));
        assertEquals(1, scalar("SELECT count(*) FROM dh_supply_etl_grant_audit WHERE action='CREATE'"
                + " AND actor_id=7 AND before_json='{}'::jsonb AND after_json->>'permissionUserId'='7'"));
        assertEquals(1, scalar("SELECT count(*) FROM dh_supply_etl_grant_audit WHERE action='REGRANT' AND actor_id=88"
                + " AND before_json->>'permissionUserId'='7' AND after_json->>'permissionUserId'='88'"
                + " AND before_json->>'purpose'=after_json->>'purpose' AND revision=4"));
        assertEquals(1, scalar("SELECT count(*) FROM dh_supply_etl_grant_audit WHERE action='ENABLE' AND actor_id=88"
                + " AND before_json->>'aggregateDisabled'='true' AND after_json->>'aggregateDisabled'='false'"
                + " AND after_json->>'permissionUserId'='7'"));
    }

    @Test void revokedSourcePermissionOrDisabledSubjectCannotResumeExistingGrant() throws Exception {
        Long id = service.createAppApi(request(10L)); service.setGrantControl(id, 0L, true);
        actor.setStatus(1);
        assertThrows(RuntimeException.class, () -> service.enableAppApi(id, 1L));
        actor.setStatus(0);
        when(batches.describe(any())).thenThrow(new IllegalStateException("source access revoked"));
        assertThrows(RuntimeException.class, () -> service.enableAppApi(id, 1L));
        assertEquals(1, scalar("SELECT count(*) FROM dh_supply_app_api WHERE id=" + id + " AND aggregate_disabled=true AND aggregate_revision=1"));
    }

    @Test void applicationPauseAndResumeAdvanceRuntimeRevision() throws Exception {
        Long id = service.createAppApi(request(10L));
        service.updateAppStatus(1L, 0); service.updateAppStatus(1L, 1);
        assertEquals(3, scalar("SELECT grant_revision FROM dh_supply_app_api WHERE id=" + id));
        assertEquals(2, scalar("SELECT count(*) FROM dh_supply_etl_grant_audit WHERE action IN('APP_ENABLE','APP_DISABLE')"
                + " AND before_json->>'applicationStatus'<>after_json->>'applicationStatus'"));
        assertThrows(RuntimeException.class, () -> service.revokeAdminAppApi(id, 1L));
        assertEquals(1, scalar("SELECT count(*) FROM dh_supply_app_api WHERE grant_status=1"));
    }

    @Test void ordinaryAdminGrantStillHasNoPersonalSubjectAndDoesNotUseEtlRpc() throws Exception {
        Long id = service.createAppApi(request(20L));
        assertEquals(1, scalar("SELECT count(*) FROM dh_supply_app_api WHERE id=" + id + " AND source_type='ADMIN' AND permission_user_id IS NULL"));
        verifyNoInteractions(batches, users);
        assertEquals(0, scalar("SELECT count(*) FROM dh_supply_etl_grant_audit"));
    }

    @Test void auditAppendFailureRollsBackPermissionChangeAndLeavesNoSuccessfulHistory() throws Exception {
        Long id = service.createAppApi(request(10L));
        sql("CREATE FUNCTION reject_test_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test audit unavailable'; END; $$;"
                + "CREATE TRIGGER reject_test_audit BEFORE INSERT ON dh_supply_etl_grant_audit FOR EACH ROW EXECUTE FUNCTION reject_test_audit()");
        assertThrows(RuntimeException.class, () -> service.setGrantControl(id, 0L, true));
        assertEquals(1, scalar("SELECT count(*) FROM dh_supply_app_api WHERE id=" + id + " AND aggregate_disabled=false AND aggregate_revision=0"));
        assertEquals(1, scalar("SELECT count(*) FROM dh_supply_etl_grant_audit"));
    }

    @Test void outerTransactionRollbackRemovesBothGrantAndItsAudit() throws Exception {
        var transactions = new org.springframework.transaction.support.TransactionTemplate(app.getBean(DataSourceTransactionManager.class));
        assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
            service.createAppApi(request(10L)); throw new IllegalStateException("caller rolls back");
        }));
        assertEquals(0, scalar("SELECT count(*) FROM dh_supply_app_api"));
        assertEquals(0, scalar("SELECT count(*) FROM dh_supply_etl_grant_audit"));
    }

    @Test void ignoredGrantRevisionUpdateCannotProduceInventedApplicationAudit() throws Exception {
        service.createAppApi(request(10L));
        sql("CREATE FUNCTION ignore_test_grant_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END; $$;"
                + "CREATE TRIGGER ignore_test_grant_update BEFORE UPDATE ON dh_supply_app_api FOR EACH ROW EXECUTE FUNCTION ignore_test_grant_update()");
        assertThrows(RuntimeException.class, () -> service.updateAppStatus(1L, 0));
        assertEquals(1, scalar("SELECT app_status FROM dh_supply_app WHERE id=1"));
        assertEquals(1, scalar("SELECT count(*) FROM dh_supply_etl_grant_audit"));
        assertEquals(0, scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE app_id=1 AND api_id=10"));
    }

    @Test void migratedAuditIsAppendOnlyAndRevisionUniquenessIsGlobal() throws Exception {
        service.createAppApi(request(10L));
        assertThrows(java.sql.SQLException.class, () -> sql("UPDATE dh_supply_etl_grant_audit SET reason='rewrite'"));
        assertThrows(java.sql.SQLException.class, () -> sql("DELETE FROM dh_supply_etl_grant_audit"));
        String fields = "grant_id,app_id,api_id,actor_id,revision,action,before_json,after_json,reason,trace_id,result,creator,updater";
        assertThrows(java.sql.SQLException.class, () -> sql("INSERT INTO dh_supply_etl_grant_audit(" + fields + ") SELECT " + fields + " FROM dh_supply_etl_grant_audit"));
        assertEquals(1, scalar("SELECT count(*) FROM dh_supply_etl_grant_audit"));
    }

    private void assertNoTransactionOrLocks() throws Exception {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        try (var independent = open(); var statement = independent.createStatement()) {
            independent.setAutoCommit(false);
            statement.execute("SELECT id FROM dh_supply_app WHERE id=1 FOR UPDATE NOWAIT");
            statement.execute("SELECT id FROM dh_supply_api WHERE id=10 FOR UPDATE NOWAIT");
            independent.rollback();
        }
    }
    private static AdminUserRespDTO user(Long id) { var u = new AdminUserRespDTO(); u.setId(id); u.setStatus(0); return u; }
    private static AppApiSaveReqVO request(Long apiId) { var r = new AppApiSaveReqVO(); r.setAppId(1L); r.setApiId(apiId); r.setForeverFlag(1); return r; }
    private static EtlTargetBatchDescribeRespDTO description() {
        var d = new EtlTargetBatchDescribeRespDTO(); d.setBatchId(50L); d.setAvailable(true); d.setPayloadHash("a".repeat(64));
        d.setDatabaseId(9L); d.setSchemaName("dwd"); d.setTableName("people"); return d;
    }
}
