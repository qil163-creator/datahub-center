package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.supply.controller.admin.app.SupplyAppController;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiUpdateReqVO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.SupplyGrantOpMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.SupplyGrantSourceMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.service.app.grant.SupplyGrantReadService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.mzt.logapi.beans.LogRecord;
import com.mzt.logapi.service.ILogRecordService;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.module.supply.enums.grant.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 编辑授权的真实 PostgreSQL 边界；夹具直接补齐当前字段，不依赖已归档的历史迁移。 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class SupplyAppApiUpdatePostgresTest extends SharingPostgresSupport {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableMethodSecurity
    static class Config { }

    public static class PermissionGate {
        Set<String> allowed = Set.of("supply:app:query", "supply:app:grant");
        public boolean hasPermission(String permission) { return allowed.contains(permission); }
        public boolean hasAnyPermissions(String... permissions) {
            return java.util.Arrays.stream(permissions).anyMatch(allowed::contains);
        }
    }

    private AnnotationConfigApplicationContext context;
    private SupplyAppService service;
    private SupplyAppController controller;
    private PermissionGate permissions;
    private SupplyEtlGrantPreflight etlPreflight;
    private SupplyEtlGrantAuditService etlAudit;
    private ILogRecordService operationLogs;

    @BeforeEach
    void prepare() throws Exception {
        for (var fixture : List.of("sharing-coordinator-prerequisites.sql", "sharing-legacy-entry-prerequisites.sql")) {
            try (var input = getClass().getResourceAsStream("/sql/" + fixture)) {
                sql(new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        sql("""
                ALTER TABLE dh_supply_app_api ADD COLUMN protocol_version integer NOT NULL DEFAULT 0,
                  ADD COLUMN aggregate_revision bigint NOT NULL DEFAULT 0,
                  ADD COLUMN aggregate_disabled boolean NOT NULL DEFAULT false,
                  ADD COLUMN migration_status varchar(32);
                INSERT INTO dh_supply_app_api(id,app_id,api_id,source_type,source_id,grant_revision,
                  grant_status,forever_flag,start_date,end_date,valid_from,valid_to,approved_contract_hash,
                  api_version,description,remark) VALUES(90,1,10,'ADMIN','fixed-admin-source',3,1,0,
                  '2026-09-01','2026-09-16','2026-09-01','2026-09-17','approved-hash','v1','旧说明','保留备注');
                """);
        migrate("0169_datahub_remove-business-tenancy.sql");
        var datasource = new DriverManagerDataSource(jdbcUrl + "?currentSchema=datahub-center&socketTimeout=15", jdbcUser, "");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        for (var type : List.of(AppApiMapper.class, SupplyAppMapper.class, DataApiMapper.class)) config.addMapper(type);
        config.setEnvironment(new Environment("app-api-update", new SpringManagedTransactionFactory(), datasource));
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        context = new AnnotationConfigApplicationContext(); context.register(Config.class);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(datasource));
        context.registerBean(AppApiMapper.class, () -> session.getMapper(AppApiMapper.class));
        context.registerBean(SupplyAppMapper.class, () -> session.getMapper(SupplyAppMapper.class));
        context.registerBean(DataApiMapper.class, () -> session.getMapper(DataApiMapper.class));
        context.registerBean(SupplyGrantSourceMapper.class, () -> mock(SupplyGrantSourceMapper.class));
        context.registerBean(SupplyGrantOpMapper.class, () -> mock(SupplyGrantOpMapper.class));
        context.registerBean(AdminUserApi.class, () -> mock(AdminUserApi.class));
        context.getBeanFactory().registerSingleton("etlGrantHistory", mock(SupplyEtlGrantHistoryService.class));
        etlPreflight = mock(SupplyEtlGrantPreflight.class); etlAudit = mock(SupplyEtlGrantAuditService.class);
        context.getBeanFactory().registerSingleton("etlGrantPreflight", etlPreflight);
        context.getBeanFactory().registerSingleton("etlGrantAudit", etlAudit);
        operationLogs = mock(ILogRecordService.class);
        context.getBeanFactory().registerSingleton("operationLogs", operationLogs);
        permissions = new PermissionGate(); context.getBeanFactory().registerSingleton("ss", permissions);
        context.registerBean(SupplyGrantReadService.class);
        context.registerBean(SupplyAppServiceImpl.class); context.registerBean(SupplyAppController.class);
        context.refresh();
        service = context.getBean(SupplyAppService.class); controller = context.getBean(SupplyAppController.class);
        authenticate();
    }

    @AfterEach
    void finish() {

        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    @Test
    void editingClearsOldBoundsAndDescriptionWithoutChangingIdentityOrDisable() throws Exception {
        sql("UPDATE dh_supply_app_api SET aggregate_disabled=true WHERE id=90");
        var request = request(0L); request.setDescription(null);
        assertThat(controller.updateAppApi(request).getCheckedData()).isTrue();
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE id=90 AND app_id=1 AND api_id=10"
                + " AND source_type='ADMIN' AND source_id='fixed-admin-source' AND permission_user_id IS NULL"
                + " AND approved_contract_hash='approved-hash' AND api_version='v1' AND grant_status=1"
                + " AND aggregate_disabled AND aggregate_revision=1 AND grant_revision=4 AND forever_flag=1"
                + " AND start_date IS NULL AND end_date IS NULL AND valid_from IS NULL AND valid_to IS NULL"
                + " AND description IS NULL AND remark='保留备注' AND updater='7'")).isEqualTo(1);
        assertThat(service.getAppApi(90L).getAggregateDisabled()).isTrue();
        assertThat(service.getAppApi(90L).getAuthStatus()).isEqualTo(0);
        verifyNoInteractions(etlPreflight, etlAudit);
    }

    @Test
    void finiteEndDateUsesInclusiveLastDayAndAuthoritativeReadUsesNewRevision() throws Exception {
        var request = request(0L); request.setForeverFlag(0);
        request.setEndDate(LocalDate.of(2099, 10, 14));
        service.updateAppApi(request);
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE id=90 AND start_date IS NULL"
                + " AND valid_from IS NULL AND end_date='2099-10-14' AND valid_to='2099-10-15'")).isEqualTo(1);
        var current = service.getAppApi(90L);
        assertThat(current.getAuthStatus()).isEqualTo(1);
        assertThat(current.getAggregateRevision()).isEqualTo(1L);
    }

    @Test
    void staleRevisionCannotOverwriteLaterEdit() throws Exception {
        service.updateAppApi(request(0L));
        var stale = request(0L); stale.setDescription("过期编辑");
        code(() -> service.updateAppApi(stale), GRANT_CONTROL_CONFLICT.getCode());
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE id=90 AND description='新说明'"
                + " AND aggregate_revision=1")).isEqualTo(1);
    }

    @Test
    void editingDetailReloadsCurrentDescriptionVersionAndAuthoritativeStatus() {
        var before = controller.getAppApi(90L).getCheckedData();
        assertThat(before.getDescription()).isEqualTo("旧说明");
        assertThat(before.getAuthStatus()).isEqualTo(0);
        service.updateAppApi(request(0L));
        var current = controller.getAppApi(90L).getCheckedData();
        assertThat(current.getDescription()).isEqualTo("新说明");
        assertThat(current.getForeverFlag()).isEqualTo(1);
        assertThat(current.getAggregateRevision()).isEqualTo(1L);
        assertThat(current.getAuthStatus()).isEqualTo(1);
        assertThat(current.getApiId()).isEqualTo(before.getApiId());
        assertThat(current.getAppId()).isEqualTo(before.getAppId());
    }

    @Test
    void editingDetailRequiresQueryPermissionAndRejectsMissingGrant() {
        permissions.allowed = Set.of("supply:app:grant");
        assertThatThrownBy(() -> controller.getAppApi(90L)).isInstanceOf(AccessDeniedException.class);
        permissions.allowed = Set.of("supply:app:query");
        assertThat(controller.getAppApi(90L).getCheckedData().getId()).isEqualTo(90L);
        assertThatThrownBy(() -> controller.getAppApi(91L)).isInstanceOf(ServiceException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ETL", "MARKET", "PERSONAL", "PROTOCOL", "SUBJECT"})
    void nonOrdinaryAdminSourcesCannotBeEdited(String type) throws Exception {
        switch (type) {
            case "ETL" -> sql("UPDATE dh_supply_app_api SET source_type='ADMIN_ETL_BATCH',permission_user_id=7 WHERE id=90");
            case "MARKET" -> sql("UPDATE dh_supply_app_api SET protocol_version=2,migration_status='CONFIRMED' WHERE id=90");
            case "PERSONAL" -> sql("UPDATE dh_supply_app_api SET source_type='PERSONAL' WHERE id=90");
            case "PROTOCOL" -> sql("UPDATE dh_supply_app_api SET protocol_version=1 WHERE id=90");
            case "SUBJECT" -> sql("UPDATE dh_supply_app_api SET permission_user_id=7 WHERE id=90");
            default -> throw new IllegalArgumentException(type);
        }
        code(() -> service.updateAppApi(request(0L)), GRANT_SCOPE_UNCONFIRMED.getCode());
        assertUnchanged(); verifyNoInteractions(etlPreflight, etlAudit);
    }

    @Test
    void revokedAdminCannotBeRestoredByEditing() throws Exception {
        sql("UPDATE dh_supply_app_api SET grant_status=3 WHERE id=90");
        code(() -> service.updateAppApi(request(0L)), GRANT_NOT_EFFECTIVE.getCode());
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTRACT", "ETL_API", "APP_DISABLED", "API_DISABLED"})
    void currentApplicationAndContractStillAuthorizeTheOriginalGrant(String change) throws Exception {
        switch (change) {
            case "CONTRACT" -> sql("UPDATE dh_supply_api SET contract_hash='new-contract' WHERE id=10");
            case "ETL_API" -> sql("UPDATE dh_supply_api SET config_type='ETL_BATCH' WHERE id=10");
            case "APP_DISABLED" -> sql("UPDATE dh_supply_app SET app_status=0 WHERE id=1");
            case "API_DISABLED" -> sql("UPDATE dh_supply_api SET status=0 WHERE id=10");
            default -> throw new IllegalArgumentException(change);
        }
        code(() -> service.updateAppApi(request(0L)), change.equals("CONTRACT") || change.equals("ETL_API")
                ? GRANT_SCOPE_UNCONFIRMED.getCode() : GRANT_NOT_EFFECTIVE.getCode());
        assertUnchanged();
    }

    @Test
    void invalidValidityCannotBecomeUnlimited() throws Exception {
        var request = request(0L); request.setForeverFlag(2);
        code(() -> service.updateAppApi(request), GRANT_VALIDITY_INVALID.getCode());
        request.setForeverFlag(0);
        code(() -> service.updateAppApi(request), GRANT_VALIDITY_INVALID.getCode());
        request.setStartDate(LocalDate.of(2026, 10, 15)); request.setEndDate(LocalDate.of(2026, 10, 14));
        code(() -> service.updateAppApi(request), GRANT_VALIDITY_INVALID.getCode());
        assertUnchanged();
    }

    @Test
    void knownGrantCanBeEditedWithoutTenantContext() throws Exception {
        service.updateAppApi(request(0L));
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE id=90")).isEqualTo(1);
    }

    @Test
    void managementScopeNeedsQueryAndGrantOrUpdateButIsNotOwnerOnly() throws Exception {
        permissions.allowed = Set.of("supply:app:query");
        assertThatThrownBy(() -> controller.updateAppApi(request(0L))).isInstanceOf(AccessDeniedException.class);
        permissions.allowed = Set.of("supply:app:grant");
        assertThatThrownBy(() -> controller.updateAppApi(request(0L))).isInstanceOf(AccessDeniedException.class);
        assertUnchanged();
        permissions.allowed = Set.of("supply:app:query", "supply:app:update");
        sql("UPDATE dh_supply_app SET owner_user_id=999,owning_dept_id=888 WHERE id=1");
        assertThat(controller.updateAppApi(request(0L)).getCheckedData()).isTrue();
    }

    @Test
    void callerRollbackUndoesValidityAndRevisionTogether() throws Exception {
        var transactions = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        assertThatThrownBy(() -> transactions.execute(status -> {
            service.updateAppApi(request(0L));
            throw new IllegalStateException("测试调用方回滚");
        })).isInstanceOf(IllegalStateException.class);
        assertUnchanged();
        verifyNoInteractions(operationLogs);
    }

    @Test
    void successfulCommitRecordsActorBoundsAndDescriptionDigest() throws Exception {
        doAnswer(call -> {
            // 独立连接已能看见新修订，证明成功日志没有早于本地事务提交。
            assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE id=90")).isEqualTo(1);
            return null;
        }).when(operationLogs).record(org.mockito.ArgumentMatchers.any());
        service.updateAppApi(request(0L));
        var record = org.mockito.ArgumentCaptor.forClass(LogRecord.class);
        verify(operationLogs).record(record.capture());
        assertThat(record.getValue().getOperator()).isEqualTo("7");
        assertThat(record.getValue().getTenant()).isNull();
        assertThat(record.getValue().getBizNo()).isEqualTo("90");
        assertThat(record.getValue().getExtra()).contains("\"before\"", "\"after\"", "\"actorId\":7",
                "\"aggregateRevision\":0", "\"aggregateRevision\":1", "\"startDate\"", "\"endDate\"",
                "\"descriptionHash\"", "\"reason\"", "\"result\":\"SUCCESS\"", "\"traceId\"")
                .doesNotContain("旧说明", "新说明");
    }

    @Test
    void logDeliveryFailureDoesNotTurnCommittedEditIntoRetryableFailure() throws Exception {
        doThrow(new IllegalStateException("测试日志投递失败")).when(operationLogs).record(org.mockito.ArgumentMatchers.any());
        assertThat(controller.updateAppApi(request(0L)).getCheckedData()).isTrue();
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE id=90")).isEqualTo(1);
    }

    @Test
    void missingActorCannotPersistAnUnattributedEdit() throws Exception {
        SecurityContextHolder.clearContext();
        code(() -> service.updateAppApi(request(0L)), GRANT_SUBJECT_INVALID.getCode());
        assertUnchanged(); verifyNoInteractions(operationLogs);
    }

    @Test
    void twoConcurrentEditsCanCommitOnlyOneExpectedRevision() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
            var task = (java.util.concurrent.Callable<Boolean>) () -> {
                ready.countDown();
                authenticate();
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试并发启动超时");
                    service.updateAppApi(request(0L)); return true;
                } catch (ServiceException failure) {
                    assertThat(failure.getCode()).isEqualTo(GRANT_CONTROL_CONFLICT.getCode()); return false;
                } finally {  SecurityContextHolder.clearContext(); }
            };
            var first = executor.submit(task); var second = executor.submit(task);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE id=90")).isEqualTo(1);
    }

    private static AppApiUpdateReqVO request(long revision) {
        var request = new AppApiUpdateReqVO(); request.setId(90L); request.setExpectedRevision(revision);
        request.setForeverFlag(1); request.setDescription("新说明"); return request;
    }

    private static void authenticate() {
        var actor = new LoginUser(); actor.setId(7L); actor.setUserType(2);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }

    private void assertUnchanged() throws Exception {
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE id=90 AND aggregate_revision=0"
                + " AND grant_revision=3 AND forever_flag=0 AND start_date='2026-09-01'"
                + " AND end_date='2026-09-16' AND description='旧说明'")).isEqualTo(1);
    }

    private static void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(code));
    }
}
