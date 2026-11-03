package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.supply.api.app.SupplyAppApiImpl;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyAppApiGrantReqDTO;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiSaveReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.app.SupplyAppController;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.SupplyGrantOpMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class SupplyLegacyGrantBoundaryTest extends SharingPostgresSupport {
    private AnnotationConfigApplicationContext context;
    private SupplyAppService service;
    private SupplyAppApiImpl rpc;
    private PermissionGate permissions;
    private SupplyAppController controller;
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class TxConfig { }

    public static class PermissionGate {
        String allowed;
        public boolean hasAnyPermissions(String... required) {
            return allowed != null && java.util.Arrays.asList(required).contains(allowed);
        }
    }

    @BeforeEach
    void prepare() throws Exception {
        for (String fixture : List.of("sharing-coordinator-prerequisites.sql", "sharing-legacy-entry-prerequisites.sql")) {
            try (var input = getClass().getResourceAsStream("/sql/" + fixture)) {
                sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        migrate("0041_supply_grant-source-protocol.sql");
        migrate("0095_supply_etl-batch-consumption.sql");
        migrate("0169_datahub_remove-business-tenancy.sql");
        sql("INSERT INTO dh_supply_app_api(id,app_id,api_id,protocol_version,aggregate_revision,"
                + "migration_status,grant_status,grant_revision) VALUES(90,1,10,2,3,'CONFIRMED',3,3)");
        var ds = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=15&connectTimeout=5", jdbcUser, "");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        for (var type : List.of(SupplyAppMapper.class, AppApiMapper.class, SupplyGrantOpMapper.class, DataApiMapper.class)) {
            config.addMapper(type);
        }
        config.setEnvironment(new Environment("legacy-boundary", new SpringManagedTransactionFactory(), ds));
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        context = new AnnotationConfigApplicationContext(); context.register(TxConfig.class);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        context.registerBean(SupplyAppMapper.class, () -> session.getMapper(SupplyAppMapper.class));
        context.registerBean(AppApiMapper.class, () -> session.getMapper(AppApiMapper.class));
        context.registerBean(SupplyGrantOpMapper.class, () -> session.getMapper(SupplyGrantOpMapper.class));
        context.registerBean(DataApiMapper.class, () -> session.getMapper(DataApiMapper.class));
        context.getBeanFactory().registerSingleton("adminUserApi", mock(AdminUserApi.class));
        // 本类验证旧协议管理边界；实际授权范围读取由独立专测覆盖。
        context.getBeanFactory().registerSingleton("grantReadService",
                mock(cn.iocoder.yudao.module.supply.service.app.grant.SupplyGrantReadService.class));
        permissions = new PermissionGate(); context.getBeanFactory().registerSingleton("ss",permissions);
        context.getBeanFactory().registerSingleton("etlGrantPreflight", mock(SupplyEtlGrantPreflight.class));
        context.getBeanFactory().registerSingleton("etlGrantAudit", mock(SupplyEtlGrantAuditService.class));
        context.getBeanFactory().registerSingleton("etlGrantHistory", mock(SupplyEtlGrantHistoryService.class));
        context.registerBean(SupplyAppServiceImpl.class); context.registerBean(SupplyAppController.class); context.refresh();
        service = context.getBean(SupplyAppService.class);
        controller = context.getBean(SupplyAppController.class);
        rpc = new SupplyAppApiImpl(); ReflectionTestUtils.setField(rpc, "supplyAppService", service);
        }

    @AfterEach
    void finish() {  if (context != null) context.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"PERSONAL", "ADMIN", ""})
    void legacyRpcCannotGrantEvenByClaimingAdmin(String sourceType) throws Exception {
        var req = new SupplyAppApiGrantReqDTO(); req.setAppId(1L); req.setApiId(10L);
        req.setSourceSystem("asset"); req.setSourceType(sourceType); req.setForeverFlag(1);
        code(() -> rpc.grantAppApi(req), 1_500_042_013);
        assertThat(scalar("SELECT grant_status FROM dh_supply_app_api WHERE id=90")).isEqualTo(3);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_op")).isZero();
    }

    @Test
    void legacyRpcCannotGuessWhichSourceToRevoke() throws Exception {
        var req = new SupplyAppApiGrantReqDTO(); req.setAppId(1L); req.setApiId(10L);
        req.setSourceSystem("asset"); req.setOperationId("legacy-revoke");
        code(() -> rpc.revokeAppApi(req), 1_500_042_013);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_op")).isZero();
    }

    @Test
    void adminCreateCannotOverwriteV2Aggregate() throws Exception {
        code(() -> service.createAppApi(request(1,10)), 1_500_042_011);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE id=90")).isEqualTo(3);
        assertThat(scalar("SELECT grant_status FROM dh_supply_app_api WHERE id=90")).isEqualTo(3);
    }

    @Test
    void adminIdentityAndRevisionAreChosenByServer() throws Exception {
        var req = request(2,20); req.setId(987L); req.setSourceSystem("asset"); req.setSourceType("PERSONAL");
        req.setSourceDecisionId("asset:apply:999"); req.setPermissionUserId(999L); req.setGrantRevision(999);
        req.setApprovedContractHash("forged"); req.setApiVersion("forged");
        Long id = service.createAppApi(req);
        assertThat(id).isNotEqualTo(987L);
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE app_id=2 AND source_type='ADMIN'"
                + " AND source_id='admin:app:2:api:20' AND permission_user_id IS NULL AND grant_revision=1"
                + " AND approved_contract_hash='approved-hash' AND api_version='v1'")).isEqualTo(1);
    }

    @Test
    void adminCannotTakeOverHistoricalMarketGrant() throws Exception {
        sql("UPDATE dh_supply_app_api SET protocol_version=0,source_type='DEPARTMENT',grant_status=3 WHERE id=90");
        code(() -> service.createAppApi(request(1,10)), 1_500_042_011);
    }

    @Test
    void adminCanCreateForAnyPlatformApplication() {
        assertThat(service.createAppApi(request(3, 20))).isNotNull();
    }

    @Test
    void adminDeleteDisablesWithoutChangingSourceOrReceipt() throws Exception {
        sql("INSERT INTO dh_supply_grant_source(source_system,source_key,app_id,api_id,source_revision,"
                + "source_status,payload_hash) VALUES('asset','A',1,10,4,'REVOKED','old')");
        service.deleteAppApi(90L);
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE id=90 AND deleted=0 AND aggregate_disabled"
                + " AND aggregate_revision=4 AND grant_revision=3 AND grant_status=3")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source WHERE source_revision=4 AND source_status='REVOKED'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_op")).isZero();
        service.deleteAppApi(90L);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE id=90")).isEqualTo(4);
    }

    @Test
    void controlFailureRollsBackDisableFlag() throws Exception {
        sql("ALTER TABLE dh_supply_app_api ADD CONSTRAINT test_revision_failure CHECK (aggregate_revision < 4)");
        assertThatThrownBy(() -> service.deleteAppApi(90L)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE id=90 AND NOT aggregate_disabled AND aggregate_revision=3")).isEqualTo(1);
    }

    @Test
    void aggregateDisableEnforcedByLegacyRuntime() {
        var app = SupplyAppDO.builder().appStatus(1).build();
        var grant = AppApiDO.builder().grantStatus(1).foreverFlag(1).aggregateDisabled(true).build();
        assertThat(SupplyGrantValidity.isEffective(app,grant,LocalDateTime.now())).isFalse();
        assertThat(SupplyGrantValidity.displayStatus(app,grant,LocalDateTime.now())).isNotEqualTo(1);
    }

    @Test
    void legacyUnknownCannotBeConfirmedFromCurrentAggregate() throws Exception {
        unknown(500,0); sql("UPDATE dh_supply_app_api SET grant_status=1 WHERE id=90");
        assertThat(service.reconcileUnknownGrantOps()).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_op WHERE id=500 AND result_status='UNKNOWN'"
                + " AND error_code='LEGACY_REVIEW_REQUIRED' AND next_retry_time IS NULL")).isEqualTo(1);
        assertThat(service.reconcileUnknownGrantOps()).isZero();
    }

    @Test
    void newProtocolUnknownIsNotLegacyWork() throws Exception {
        unknown(500, 2);
        assertThat(service.reconcileUnknownGrantOps()).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_op WHERE error_code IS NULL AND retry_count=0")).isEqualTo(1);
    }

    @Test
    void receiptQueryUsesGlobalSourceIdentity() throws Exception {
        unknown(500, 0);
        assertThat(service.getGrantOperation("asset", "old-500")).isNotNull();
    }

    @Test
    void platformAdminCreationNeedsNoTenantContext() {
        assertThat(service.createAppApi(request(2, 20))).isNotNull();
    }

    @Test
    void explicitEnableAdvancesVersionWithoutRestoringRevokedSources() throws Exception {
        sql("UPDATE dh_supply_app_api SET aggregate_disabled=true WHERE id=90");
        sql("INSERT INTO dh_supply_grant_source(source_system,source_key,app_id,api_id,source_revision,"
                + "source_status,payload_hash) VALUES('asset','A',1,10,4,'REVOKED','old')");
        service.enableAppApi(90L,3L);
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE id=90 AND NOT aggregate_disabled"
                + " AND aggregate_revision=4 AND grant_status=3")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source WHERE source_revision=4 AND source_status='REVOKED'")).isEqualTo(1);
        code(() -> service.enableAppApi(90L,3L),1_500_042_014);
        service.enableAppApi(90L,4L);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE id=90")).isEqualTo(4);
    }

    @Test
    void staleEnableCannotUndoNewerDisable() throws Exception {
        sql("UPDATE dh_supply_app_api SET aggregate_disabled=true,aggregate_revision=5 WHERE id=90");
        code(() -> service.enableAppApi(90L,3L),1_500_042_014);
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE aggregate_disabled AND aggregate_revision=5")).isEqualTo(1);
    }

    @Test
    void unconfirmedLegacyGrantCannotBeEnabled() throws Exception {
        sql("UPDATE dh_supply_app_api SET protocol_version=0,source_type='DEPARTMENT',aggregate_disabled=true WHERE id=90");
        code(() -> service.enableAppApi(90L,3L),1_500_042_011);
    }

    @Test
    void disabledApplicationCannotBeReenabledThroughGrantControl() throws Exception {
        sql("UPDATE dh_supply_app SET app_status=0 WHERE id=1");
        code(() -> service.enableAppApi(90L,3L),1_500_042_009);
    }

    @Test
    void missingGrantCannotBeEnabled() {
        assertThatThrownBy(() -> service.enableAppApi(999L, 3L)).isInstanceOf(ServiceException.class);
    }

    @Test
    void controllerRequiresGrantManagementPermission() throws Exception {
        permissions.allowed = "supply:app:query";
        sql("UPDATE dh_supply_app_api SET aggregate_disabled=true WHERE id=90");
        assertThatThrownBy(() -> controller.enableAppApi(90L,3L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE aggregate_disabled")).isEqualTo(1);
    }

    @Test
    void authorizedControllerEnablesAndExposesNewControlVersion() throws Exception {
        permissions.allowed = "supply:app:grant";
        sql("UPDATE dh_supply_app_api SET aggregate_disabled=true WHERE id=90");
        assertThat(controller.enableAppApi(90L,3L).getCheckedData()).isTrue();
        var effective = new cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiGrantScopeRespVO();
        effective.setMode("NONE"); // 本用例没有有效来源；范围计算另由 SupplyGrantAdminScopeTest 覆盖。
        org.mockito.Mockito.when(context.getBean(
                cn.iocoder.yudao.module.supply.service.app.grant.SupplyGrantReadService.class).describe( 90L))
                .thenReturn(effective);
        var grant = service.getAppApiList(1L).getFirst();
        assertThat(grant.getAuthStatus()).isZero();
        assertThat(grant.getAggregateDisabled()).isFalse();
        assertThat(grant.getAggregateRevision()).isEqualTo(4L);
        assertThat(grant.getProtocolVersion()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {0,2,-1})
    void invalidAdminValidityCannotBecomeUnlimited(int flag) throws Exception {
        var req = request(2,20); req.setForeverFlag(flag);
        code(() -> service.createAppApi(req),1_500_042_015);
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE app_id=2")).isZero();
    }

    @Test
    void missingPermanentFlagIsNotDefaultedToPermanent() {
        var req = request(2,20); req.setForeverFlag(null);
        code(() -> service.createAppApi(req),1_500_042_015);
    }

    @Test
    void invalidAdminDateOrderIsBusinessRejection() {
        var req = request(2,20); req.setForeverFlag(0);
        req.setStartDate(java.time.LocalDate.of(2030,2,2)); req.setEndDate(java.time.LocalDate.of(2030,2,1));
        code(() -> service.createAppApi(req),1_500_042_015);
    }

    @Test
    void concurrentFirstAdminGrantCreatesOnlyOneAggregate() throws Exception {
        var outcomes = race(() -> {
            try {
                service.createAppApi(request(2,20));
                return "CREATED";
            } catch (ServiceException failure) {
                assertThat(failure.getCode()).isEqualTo(
                        cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.SUPPLY_APP_API_DUPLICATE.getCode());
                return "DUPLICATE";
            } finally {

            }
        });
        assertThat(outcomes).containsExactlyInAnyOrder("CREATED","DUPLICATE");
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE app_id=2 AND api_id=20")).isEqualTo(1);
    }

    @Test
    void competingLegacyReconciliationMarksOnlyOnce() throws Exception {
        unknown(500,0);
        var mapper = context.getBean(SupplyGrantOpMapper.class);
        assertThat(race(() -> mapper.markLegacyUnknownForReview(500)))
                .containsExactlyInAnyOrder(1,0);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_op WHERE result_status='UNKNOWN'"
                + " AND error_code='LEGACY_REVIEW_REQUIRED'")).isEqualTo(1);
    }

    @Test
    void staleReconciliationScanCannotOverwriteConfirmedReceipt() throws Exception {
        unknown(500,0);
        var mapper = context.getBean(SupplyGrantOpMapper.class);
        assertThat(mapper.selectLegacyUnknownDue(100)).hasSize(1);
        sql("UPDATE dh_supply_grant_op SET result_status='DELIVERED' WHERE id=500");
        assertThat(mapper.markLegacyUnknownForReview(500)).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_op WHERE result_status='DELIVERED'"
                + " AND error_code IS NULL")).isEqualTo(1);
    }

    @Test
    void reconciliationIsBoundedAndRespectsDatabaseDueTime() throws Exception {
        sql("INSERT INTO dh_supply_grant_op(id,source_system,operation_id,payload_hash,aggregate_key,"
                + "target_status,result_status,protocol_version,next_retry_time)"
                + " SELECT id,'asset','old-'||id,'old','1:10',1,'UNKNOWN',0,"
                + "CURRENT_TIMESTAMP-interval '1 day' FROM generate_series(500,600) id");
        unknown(601,0);
        sql("UPDATE dh_supply_grant_op SET next_retry_time=CURRENT_TIMESTAMP+interval '1 day' WHERE id=601");
        assertThat(service.reconcileUnknownGrantOps()).isEqualTo(100);
        assertThat(service.reconcileUnknownGrantOps()).isEqualTo(1);
        assertThat(service.reconcileUnknownGrantOps()).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_op WHERE id=601 AND error_code IS NULL")).isEqualTo(1);
    }

    @Test
    void recreatingAdminAsPermanentClearsPreviousValidity() throws Exception {
        historicalDatedAdmin();
        assertThat(service.createAppApi(request(1,10))).isEqualTo(90L);
        var grant = context.getBean(AppApiMapper.class).selectGrantControlById(90);
        assertThat(grant.getValidTo()).isNull();
        assertThat(grant.getValidFrom()).isNull();
        assertThat(grant.getStartDate()).isNull();
        assertThat(grant.getEndDate()).isNull();
        assertThat(grant.getAggregateRevision()).isEqualTo(4L);
        assertThat(SupplyGrantValidity.isEffective(SupplyAppDO.builder().appStatus(1).build(),
                grant,LocalDateTime.of(2030,1,1,0,0))).isTrue();
    }

    @Test
    void recreatingAdminWithOnlyDeadlineClearsOldStartButPreservesDisable() throws Exception {
        historicalDatedAdmin();
        sql("UPDATE dh_supply_app_api SET aggregate_disabled=true WHERE id=90");
        var req = request(1,10); req.setForeverFlag(0); req.setEndDate(java.time.LocalDate.of(2030,1,1));
        service.createAppApi(req);
        var grant = context.getBean(AppApiMapper.class).selectGrantControlById(90);
        assertThat(grant.getValidFrom()).isNull();
        assertThat(grant.getStartDate()).isNull();
        assertThat(grant.getValidTo()).isEqualTo(LocalDateTime.of(2030,1,2,0,0));
        assertThat(grant.getAggregateDisabled()).isTrue();
        assertThat(grant.getAggregateRevision()).isEqualTo(4L);
    }

    @Test
    void platformGrantAdministratorMayControlApplicationOwnedByAnotherUser() throws Exception {
        // 此管理端范围是平台内全应用，不是 owner-only；仍受既有 grant/update 功能权限限制。
        permissions.allowed = "supply:app:grant";
        sql("UPDATE dh_supply_app SET owner_user_id=999,owning_dept_id=888 WHERE id=1");
        sql("UPDATE dh_supply_app_api SET aggregate_disabled=true WHERE id=90");
        assertThat(controller.enableAppApi(90L,3L).getCheckedData()).isTrue();
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE id=90 AND NOT aggregate_disabled")).isEqualTo(1);
        assertThatThrownBy(() -> controller.enableAppApi(90L,3L)).isInstanceOf(ServiceException.class);
        assertThat(controller.enableAppApi(90L,4L).getCheckedData()).isTrue();
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE id=90")).isEqualTo(4);
    }

    @Test
    void disablingAdminDoesNotSilentlyDiscardApiRetirementReference() throws Exception {
        historicalDatedAdmin();
        sql("UPDATE dh_supply_app_api SET grant_status=1 WHERE id=90");
        service.deleteAppApi(90L);
        assertThat(context.getBean(AppApiMapper.class).selectActiveCountByApiId(10L)).isEqualTo(1L);
        var apis = new cn.iocoder.yudao.module.supply.service.dataapi.DataApiServiceImpl();
        ReflectionTestUtils.setField(apis,"dataApiMapper",context.getBean(DataApiMapper.class));
        ReflectionTestUtils.setField(apis,"appApiMapper",context.getBean(AppApiMapper.class));
        sql("UPDATE dh_supply_api SET status=0 WHERE id=10");
        code(() -> apis.deleteDataApi(10L),
                cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_AUTHORIZED_CANNOT_DELETE.getCode());
        assertThat(scalar("SELECT count(*) FROM dh_supply_api WHERE id=10 AND deleted=0")).isEqualTo(1);
    }

    @Test
    void adminPermanentRevokeReleasesApiRetirementReference() throws Exception {
        historicalDatedAdmin();
        sql("UPDATE dh_supply_app_api SET grant_status=1,aggregate_disabled=true WHERE id=90");
        service.revokeAdminAppApi(90L, 3L);
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE id=90 AND deleted=0 AND grant_status=3"
                + " AND aggregate_revision=4 AND protocol_version=0 AND source_type='ADMIN'")).isEqualTo(1);
        assertThat(context.getBean(AppApiMapper.class).selectActiveCountByApiId(10L)).isZero();
        // 终态幂等：重复关闭不再推进版本；过期版本被拒绝。
        service.revokeAdminAppApi(90L, 4L);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE id=90")).isEqualTo(4);
        code(() -> service.revokeAdminAppApi(90L, 3L), 1_500_042_014);
        // 终态不可通过启用恢复；重新授权是新的修订而不是复活。
        code(() -> service.enableAppApi(90L, 4L), 1_500_042_009);
        var apis = retirementService();
        sql("UPDATE dh_supply_api SET status=0 WHERE id=10");
        apis.deleteDataApi(10L);
        assertThat(scalar("SELECT count(*) FROM dh_supply_api WHERE id=10 AND deleted=0")).isZero();
    }

    @Test
    void marketAggregateCannotBeClosedFromTheAdminEntry() throws Exception {
        sql("INSERT INTO dh_supply_grant_source(source_system,source_key,app_id,api_id,source_revision,"
                + "source_status,payload_hash) VALUES('asset','A',1,10,4,'SUSPENDED','p')");
        code(() -> service.revokeAdminAppApi(90L, 3L), 1_500_042_011);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source WHERE source_status='SUSPENDED'")).isEqualTo(1);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE id=90")).isEqualTo(3);
    }

    @Test
    void openMarketSourcesBlockApiRetirementUntilAllAreRevoked() throws Exception {
        sql("INSERT INTO dh_supply_grant_source(source_system,source_key,app_id,api_id,source_revision,"
                + "source_status,payload_hash) VALUES('asset','A',1,10,4,'SUSPENDED','p'),"
                + "('asset','B',1,10,2,'REVOKED','q')");
        var apis = retirementService();
        sql("UPDATE dh_supply_api SET status=0 WHERE id=10");
        // v2 聚合的 grant_status 固定为 3，不能因此绕过来源引用检查。
        code(() -> apis.deleteDataApi(10L),
                cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_AUTHORIZED_CANNOT_DELETE.getCode());
        sql("UPDATE dh_supply_grant_source SET source_status='REVOKED' WHERE source_key='A'");
        apis.deleteDataApi(10L);
        assertThat(scalar("SELECT count(*) FROM dh_supply_api WHERE id=10 AND deleted=0")).isZero();
    }

    @Test
    void controllerRevokeRequiresGrantManagementPermission() throws Exception {
        historicalDatedAdmin();
        sql("UPDATE dh_supply_app_api SET grant_status=1 WHERE id=90");
        permissions.allowed = "supply:app:query";
        assertThatThrownBy(() -> controller.revokeAdminAppApi(90L, 3L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE id=90 AND grant_status=1")).isEqualTo(1);
        permissions.allowed = "supply:app:grant";
        assertThat(controller.revokeAdminAppApi(90L, 3L).getCheckedData()).isTrue();
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE id=90 AND grant_status=3")).isEqualTo(1);
    }

    private cn.iocoder.yudao.module.supply.service.dataapi.DataApiServiceImpl retirementService() {
        var apis = new cn.iocoder.yudao.module.supply.service.dataapi.DataApiServiceImpl();
        ReflectionTestUtils.setField(apis, "dataApiMapper", context.getBean(DataApiMapper.class));
        ReflectionTestUtils.setField(apis, "appApiMapper", context.getBean(AppApiMapper.class));
        return apis;
    }

    private void historicalDatedAdmin() throws Exception {
        sql("UPDATE dh_supply_app_api SET protocol_version=0,source_type='ADMIN',permission_user_id=NULL,"
                + "forever_flag=0,start_date=DATE '2000-01-01',end_date=DATE '2000-01-02',"
                + "valid_from=TIMESTAMP '2000-01-01',valid_to=TIMESTAMP '2000-01-03' WHERE id=90");
    }

    private static <T> List<T> race(java.util.concurrent.Callable<T> action) throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<T> run = () -> {
                ready.countDown();
                if (!start.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                return action.call();
            };
            var first = workers.submit(run); var second = workers.submit(run);
            assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(20, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            start.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    private void unknown(long id,int protocol) throws Exception {
        sql("INSERT INTO dh_supply_grant_op(id,source_system,operation_id,payload_hash,aggregate_key,"
                + "target_status,result_status,protocol_version,next_retry_time) VALUES(" + id
                + ",'asset','old-" + id + "','old','1:10',1,'UNKNOWN'," + protocol + ",CURRENT_TIMESTAMP-interval '1 day')");
    }
    private static AppApiSaveReqVO request(long app,long api) {
        var req = new AppApiSaveReqVO(); req.setAppId(app); req.setApiId(api); req.setForeverFlag(1); return req;
    }
    private static void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(code));
    }
}
