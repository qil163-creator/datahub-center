package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryRedeliverReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketAuditEventMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryReviewMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryReviewRow;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantSourceApi;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceResultDTO;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.MockedStatic;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_DELIVERY_REDELIVER_DENIED;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * #19 交付受控重投：真实 PG 验证只对完整匹配的撤销/暂停 MANUAL/UNKNOWN 原操作按原 operationId 重发一次；
 * GRANT 拒绝、必绑核查决定、意图审计唯一请求幂等、未知结果保持 MANUAL/UNKNOWN。
 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketDeliveryRedeliverPostgresTest extends SharingPostgresSupport {
    private static final String OPERATION = "asset:asset:apply:7:r2:REVOKE";
    private final SupplyGrantSourceApi remote = mock(SupplyGrantSourceApi.class);
    private final PermissionApi permissions = mock(PermissionApi.class);
    private MarketDeliveryOperationMapper operations;
    private MarketDeliveryEvidenceMapper evidenceMapper;
    private MarketDeliveryReviewMapper reviewMapper;
    private MarketDeliveryCoordinator coordinator;
    private DataSourceTransactionManager transactions;
    private MockedStatic<SecurityFrameworkUtils> security;
    private SupplyGrantSourceCommandDTO command;
    private MarketApplyServiceImpl applyService;
    private String decisionRequestId;

    @BeforeEach
    void prepare() throws Exception {
        migrate("0042_asset_grant-source-delivery.sql");
        try (var input = getClass().getResourceAsStream("/sql/market-review-fixture.sql")) {
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        migrate("0066_asset_delivery-review-evidence.sql");
        migrate("0067_asset_grant-approval-evidence.sql");
        migrate("0072_asset_delivery-redeliver-audit.sql");
        var source = new UnpooledDataSource("org.postgresql.Driver", jdbcUrl + "?currentSchema=datahub-center",
                "sharing_test", "");
        transactions = new DataSourceTransactionManager(source);
        transactions.setEnforceReadOnly(true);
        var config = singleTenantConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("manual-redeliver-test", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        config.addInterceptor(interceptor);
        config.addMapper(MarketDeliveryOperationMapper.class);
        config.addMapper(MarketAuditEventMapper.class);
        config.addMapper(MarketDeliveryEvidenceMapper.class);
        config.addMapper(MarketApprovalEvidenceMapper.class);
        config.addMapper(MarketDeliveryReviewMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        operations = spy(session.getMapper(MarketDeliveryOperationMapper.class));
        evidenceMapper = spy(session.getMapper(MarketDeliveryEvidenceMapper.class));
        reviewMapper = session.getMapper(MarketDeliveryReviewMapper.class);
        var guard = new MarketObjectGuard();
        ReflectionTestUtils.setField(guard, "permissionApi", permissions);
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(11L);
        security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(100L);
        TenantContextHolder.clear();
        when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH)).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return CommonResult.success(true);
        });
        var evidence = new MarketDeliveryEvidenceService(evidenceMapper, guard, remote, transactions,
                session.getMapper(MarketApprovalEvidenceMapper.class));
        coordinator = new MarketDeliveryCoordinator(operations, session.getMapper(MarketAuditEventMapper.class),
                remote, transactions, evidence, reviewMapper, evidenceMapper);
        applyService = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(applyService, "objectGuard", guard);
        ReflectionTestUtils.setField(applyService, "deliveryCoordinator", coordinator);
        ReflectionTestUtils.setField(applyService, "deliveryOperationMapper", operations);
        ReflectionTestUtils.setField(applyService, "deliveryEvidenceService", evidence);
        command = new SupplyGrantSourceCommandDTO(2, OPERATION, "asset", "asset:apply:7", 2, 1L, 2L,
                SupplyGrantSourceCommandDTO.Action.REVOKE, null, "原撤销");
        operations.insertFrozen( MarketDeliveryOperationDO.builder().id(11L).operationId(OPERATION).applyId(7L)
                .sourceGrantId("apply:7:r1").opType("REVOKE").appId(1L).apiId(2L).sourceRevision(2L)
                .payloadJson(MarketDeliveryPayload.toJson(command)).payloadHash(MarketDeliveryPayload.hash( command)).build());
        sql("UPDATE dh_market_delivery_operation SET status='MANUAL',resolution_status='UNKNOWN',row_version=5 WHERE id=11");
        // 记录一条核查决定供重投绑定。
        decisionRequestId = UUID.randomUUID().toString();
        var decision = new MarketDeliveryReviewRow();
        decision.setRequestId(decisionRequestId);
        decision.setOperationId(OPERATION);
        decision.setRequestHash("d".repeat(64));
        decision.setDecision("REAPPLY_REQUIRED");
        decision.setActorUserId(11L);
        decision.setActorDeptId(100L);
        decision.setReason("需重新投递撤销命令");
        assertThat(reviewMapper.insertDecision( decision, 11L, "MANUAL",
                "{\"schemaVersion\":1,\"evidenceHash\":\"" + "e".repeat(64) + "\",\"basis\":\"工单 T-19\"}"))
                .isEqualTo(1);
        when(remote.applySource(any())).thenAnswer(ignored ->
                CommonResult.success(receipt(SupplyGrantSourceResultDTO.Outcome.APPLIED)));
        when(remote.querySourceOperation(eq("asset"), anyString())).thenAnswer(ignored ->
                CommonResult.success(receipt(SupplyGrantSourceResultDTO.Outcome.APPLIED)));
    }

    @AfterEach
    void cleanContext() {
        security.close();
        TenantContextHolder.clear();
    }

    private SupplyGrantSourceResultDTO receipt(SupplyGrantSourceResultDTO.Outcome outcome) {
        return new SupplyGrantSourceResultDTO(OPERATION, outcome, null, 2, 8,
                outcome == SupplyGrantSourceResultDTO.Outcome.NOT_FOUND ? null : MarketDeliveryPayload.hash( command));
    }

    private MarketDeliveryRedeliverReqVO request() {
        return new MarketDeliveryRedeliverReqVO(UUID.randomUUID().toString(), OPERATION, "人工重投撤销命令", decisionRequestId);
    }

    @Test
    void redeliverResendsTheFrozenRevokeOnceAndConvergesTheLedger() throws Exception {
        var response = applyService.redeliverDelivery(request());
        assertThat(response.status()).isEqualTo(MarketDeliveryOperationDO.STATUS_SUCCESS);
        assertThat(response.resolutionStatus()).isEqualTo(MarketDeliveryCoordinator.RESOLUTION_CONFIRMED);
        assertThat(response.resultRef()).isEqualTo("8");
        assertThat(response.action()).isEqualTo("REVOKE");
        verify(remote, times(1)).applySource(any());
        verify(remote, never()).querySourceOperation(anyString(), anyString());
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE id=11 AND status='SUCCESS'"
                + " AND resolution_status='CONFIRMED' AND result_ref='8' AND row_version=6")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_REDELIVERY'"
                + " AND actor_user_id=11 AND dept_id=100 AND review_operation_id='" + OPERATION + "'"
                + " AND result='REVOKE' AND from_status='MANUAL' AND to_status='MANUAL'"
                + " AND review_request_hash ~ '^[0-9a-f]{64}$'")).isEqualTo(1);
    }

    @Test
    void replayOfTheSameRequestNeverResends() throws Exception {
        var request = request();
        applyService.redeliverDelivery(request);
        clearInvocations(remote);
        var replay = applyService.redeliverDelivery(request);
        assertThat(replay.status()).isEqualTo(MarketDeliveryOperationDO.STATUS_SUCCESS);
        verify(remote, never()).applySource(any());
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_REDELIVERY'"))
                .isEqualTo(1);
    }

    @Test
    void grantCommandsAreAlwaysRejected() throws Exception {
        var scope = new SupplyGrantSourceCommandDTO.Scope("apply:7:r1", "PERSONAL", 7L,
                new SupplyGrantSourceCommandDTO.Binding(9L, "public", "citizen"), "approved-hash", "v1",
                "purpose", "duty", "CURRENT_USER_GOVERNANCE", List.of("id"), true, 0L, null);
        var grantCommand = new SupplyGrantSourceCommandDTO(2, "asset:asset:apply:7:r3:GRANT", "asset", "asset:apply:7",
                3, 1L, 2L, SupplyGrantSourceCommandDTO.Action.GRANT, scope, "原批准");
        operations.insertFrozen( MarketDeliveryOperationDO.builder().id(12L)
                .operationId("asset:asset:apply:7:r3:GRANT").applyId(7L).sourceGrantId("apply:7:r1").opType("GRANT")
                .appId(1L).apiId(2L).sourceRevision(3L).payloadJson(MarketDeliveryPayload.toJson(grantCommand))
                .payloadHash(MarketDeliveryPayload.hash( grantCommand)).build());
        sql("UPDATE dh_market_delivery_operation SET status='MANUAL',resolution_status='UNKNOWN',row_version=1 WHERE id=12");
        var denied = new MarketDeliveryRedeliverReqVO(UUID.randomUUID().toString(),
                "asset:asset:apply:7:r3:GRANT", "人工重投", decisionRequestId);
        assertThatThrownBy(() -> applyService.redeliverDelivery(denied)).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(MARKET_DELIVERY_REDELIVER_DENIED.getCode()));
        verify(remote, never()).applySource(any());
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_REDELIVERY'")).isZero();
    }

    @Test
    void missingOrForeignReviewDecisionIsRejected() throws Exception {
        var denied = new MarketDeliveryRedeliverReqVO(UUID.randomUUID().toString(), OPERATION,
                "人工重投撤销命令", UUID.randomUUID().toString());
        assertThatThrownBy(() -> applyService.redeliverDelivery(denied)).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(MARKET_DELIVERY_REDELIVER_DENIED.getCode()));
        verify(remote, never()).applySource(any());
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_REDELIVERY'")).isZero();
    }

    @Test
    void transportFailureKeepsTheOperationUnknownAndStillAuditsTheIntent() throws Exception {
        doThrow(new IllegalStateException("远端不可达")).when(remote).applySource(any());
        var response = applyService.redeliverDelivery(request());
        assertThat(response.status()).isEqualTo(MarketDeliveryOperationDO.STATUS_MANUAL);
        assertThat(response.resolutionStatus()).isEqualTo(MarketDeliveryCoordinator.RESOLUTION_UNKNOWN);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE id=11 AND status='MANUAL'"
                + " AND resolution_status='UNKNOWN' AND row_version=5")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_REDELIVERY'"))
                .isEqualTo(1);
    }

    @Test
    void unknownReceiptDoesNotAdvanceTheLedger() throws Exception {
        when(remote.applySource(any())).thenAnswer(ignored ->
                CommonResult.success(receipt(SupplyGrantSourceResultDTO.Outcome.UNKNOWN)));
        var response = applyService.redeliverDelivery(request());
        assertThat(response.status()).isEqualTo(MarketDeliveryOperationDO.STATUS_MANUAL);
        assertThat(response.resolutionStatus()).isEqualTo(MarketDeliveryCoordinator.RESOLUTION_UNKNOWN);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE id=11 AND last_error"
                + " IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackTheIntentAndNeverResends() throws Exception {
        sql("""
                CREATE FUNCTION fail_redeliver_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test redeliver audit failure'; END $$;
                CREATE TRIGGER fail_redeliver_audit BEFORE INSERT ON dh_market_audit_event FOR EACH ROW
                  WHEN (NEW.event_type='DELIVERY_REDELIVERY') EXECUTE FUNCTION fail_redeliver_audit();
                """);
        assertThatThrownBy(() -> applyService.redeliverDelivery(request())).hasStackTraceContaining("test redeliver audit failure");
        verify(remote, never()).applySource(any());
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_REDELIVERY'")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE id=11 AND status='MANUAL'"
                + " AND resolution_status='UNKNOWN'")).isEqualTo(1);
    }

    @Test
    void stateChangeBeforeIntentWriteStopsTheRedelivery() throws Exception {
        doAnswer(call -> {
            sql("UPDATE dh_market_delivery_operation SET resolution_status='REVIEW_REQUIRED' WHERE id=11");
            return call.callRealMethod();
        }).when(evidenceMapper).lockOperation( OPERATION);
        assertThatThrownBy(() -> applyService.redeliverDelivery(request())).isInstanceOf(ServiceException.class);
        verify(remote, never()).applySource(any());
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_REDELIVERY'")).isZero();
    }

    @Test
    void migrationReplaysAndRedeliverAuditIsAppendOnly() throws Exception {
        singleTenantConfiguration(); // replay current schema before auditing append-only delivery history
        applyService.redeliverDelivery(request());
        for (String statement : new String[]{
                "UPDATE dh_market_audit_event SET reason='x' WHERE event_type='DELIVERY_REDELIVERY'",
                "UPDATE dh_market_audit_event SET deleted=1 WHERE event_type='DELIVERY_REDELIVERY'",
                "DELETE FROM dh_market_audit_event WHERE event_type='DELIVERY_REDELIVERY'",
                "TRUNCATE dh_market_audit_event"}) {
            assertThatThrownBy(() -> sql(statement)).isInstanceOfSatisfying(java.sql.SQLException.class,
                    failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_REDELIVERY'"
                + " AND deleted=0")).isEqualTo(1);
    }
}
