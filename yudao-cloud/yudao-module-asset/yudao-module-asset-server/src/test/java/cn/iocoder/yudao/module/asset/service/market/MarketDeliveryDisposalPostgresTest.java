package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryDisposalReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryDisposalReqVO.Action;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryDisposalBatchReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryEvidenceRespVO.ApprovalStatus;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryReviewReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryReviewRespVO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetGrantSourceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.*;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantSourceApi;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceResultDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceResultDTO.Outcome;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * #18 历史交付实际处置：真实 PG 事务/锁、现网 INPUT 主键、真实 Guard/证据核对/收回链路/批准凭证/审计与 0069 约束；
 * 权限 RPC、Supply 回执查询、站内信与提交后领取为替身。确认原批准不重发命令，收回复用普通收回链路。
 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketDeliveryDisposalPostgresTest extends SharingPostgresSupport {
    private MarketApplyService applyService;
    private MarketDeliveryDisposalService disposals;
    private MarketDeliveryReviewService reviews;
    private MarketDeliveryEvidenceMapper evidenceMapper;
    private MarketApprovalEvidenceMapper approvalWriter;
    private MarketApprovalEvidenceMapper approvalStore;
    private DataSourceTransactionManager transactions;
    private final PermissionApi permissions = mock(PermissionApi.class);
    private final MarketDeliveryCoordinator coordinator = mock(MarketDeliveryCoordinator.class);
    private final NotifyMessageSendApi notifications = mock(NotifyMessageSendApi.class);
    private final SupplyGrantSourceApi remote = mock(SupplyGrantSourceApi.class);
    private Outcome outcome = Outcome.APPLIED;
    private String receiptHash;

    @BeforeEach
    void prepare() throws Exception {
        fixture("market-freeze-fixture.sql");
        migrate("0042_asset_grant-source-delivery.sql");
        fixture("market-approval-fixture.sql");
        migrate("0066_asset_delivery-review-evidence.sql");
        migrate("0067_asset_grant-approval-evidence.sql");
        migrate("0069_asset_delivery-disposal.sql");
        fixture("market-demand-asset-fixture.sql");
        migrate("0047_asset_renewal-request-evidence.sql");
        migrate("0050_asset_market-active-application-uniqueness.sql");
        sql("""
                CREATE SEQUENCE dh_asset_grant_source_seq;
                ALTER TABLE dh_market_resource_apply ALTER COLUMN snapshot TYPE jsonb USING snapshot::jsonb;
                UPDATE dh_market_resource_apply SET apply_start_time='2020-01-01', apply_end_time='2040-01-01'
                  WHERE id=7;
                INSERT INTO dh_asset_data_asset(id,status,asset_name,asset_type,published_version,
                    published_snapshot_id,published_catalog_version_id,datasource_id,schema_name,table_name,
                    tech_leader_user_id,business_leader_user_id,owner_dept_id,update_time)
                  VALUES (5,1,'citizen',1,'v1',11,12,4,'public','citizen',11,12,100,'2026-01-01');
                """);
        var source = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=10", "sharing_test", "");
        transactions = new DataSourceTransactionManager(source);
        var config = singleTenantConfiguration();
        var global = new GlobalConfig().setMetaObjectHandler(new DefaultDBFieldHandler());
        global.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.INPUT)
                .setKeyGenerators(List.of(new PostgreKeyGenerator())));
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("delivery-disposal", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        config.addInterceptor(interceptor);
        for (Class<?> mapper : List.of(MarketResourceApplyMapper.class, MarketCheckProcessMapper.class,
                AssetGrantSourceMapper.class, MarketDeliveryOperationMapper.class, MarketApprovalEvidenceMapper.class,
                MarketAuditEventMapper.class, DataAssetMapper.class, MarketDeliveryEvidenceMapper.class,
                MarketDeliveryReviewMapper.class)) {
            config.addMapper(mapper);
        }
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var applications = session.getMapper(MarketResourceApplyMapper.class);
        var assets = session.getMapper(DataAssetMapper.class);
        var operations = session.getMapper(MarketDeliveryOperationMapper.class);
        var approvals = session.getMapper(MarketApprovalEvidenceMapper.class);
        var reviewMapper = session.getMapper(MarketDeliveryReviewMapper.class);
        evidenceMapper = spy(session.getMapper(MarketDeliveryEvidenceMapper.class));
        approvalStore = approvals;
        approvalWriter = spy(approvals);
        var recorder = new MarketAuditRecorder();
        ReflectionTestUtils.setField(recorder, "auditEventMapper", session.getMapper(MarketAuditEventMapper.class));
        var delivery = new MarketDeliveryService();
        ReflectionTestUtils.setField(delivery, "applyMapper", applications);
        ReflectionTestUtils.setField(delivery, "grantSourceMapper", session.getMapper(AssetGrantSourceMapper.class));
        ReflectionTestUtils.setField(delivery, "operationMapper", operations);
        ReflectionTestUtils.setField(delivery, "approvalEvidenceMapper", approvalWriter);
        ReflectionTestUtils.setField(delivery, "coordinator", coordinator);
        when(coordinator.claim( anyLong(), anyString())).thenReturn(Optional.empty());
        when(permissions.hasAnyPermissions(anyLong(), any(String[].class))).thenReturn(CommonResult.success(true));
        var guard = new MarketObjectGuard();
        ReflectionTestUtils.setField(guard, "permissionApi", permissions);
        ReflectionTestUtils.setField(guard, "assetMapper", assets);
        var notify = new MarketNotifyHelper();
        ReflectionTestUtils.setField(notify, "notifyMessageSendApi", notifications);
        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applications);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "deliveryService", delivery);
        ReflectionTestUtils.setField(target, "assetMonitorService", mock(AssetMonitorService.class));
        ReflectionTestUtils.setField(target, "auditRecorder", recorder);
        ReflectionTestUtils.setField(target, "notifyHelper", notify);
        ReflectionTestUtils.setField(target, "transactionManager", transactions);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        applyService = (MarketApplyService) proxy.getProxy();
        var evidence = new MarketDeliveryEvidenceService(evidenceMapper, guard, remote, transactions, approvals);
        reviews = new MarketDeliveryReviewService(evidence, evidenceMapper, reviewMapper, guard, transactions, approvals);
        disposals = new MarketDeliveryDisposalService(evidence, evidenceMapper, reviewMapper, operations, approvals,
                guard, applyService, transactions);
        when(remote.querySourceOperation(eq("asset"), anyString())).thenAnswer(call -> {
            String operationId = call.getArgument(1);
            long revision = scalar("SELECT source_revision FROM dh_market_delivery_operation WHERE operation_id='"
                    + operationId + "'");
            String hash = receiptHash != null ? receiptHash
                    : text("SELECT payload_hash FROM dh_market_delivery_operation WHERE operation_id='" + operationId + "'");
            return CommonResult.success(new SupplyGrantSourceResultDTO(operationId, outcome, null, revision, 42, hash));
        });
        TenantContextHolder.clear();
        login(11L, 100L);
    }

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
        verify(remote, never()).applySource(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void confirmOriginalSettlesOnlyTheLedgerWhenReceiptAndFormalApprovalMatch(boolean manualUnknown) throws Exception {
        String operationId = approveAndHold(manualUnknown);
        var decision = reviews.record(new MarketDeliveryReviewReqVO(UUID.randomUUID().toString(), operationId,
                MarketDeliveryReviewReqVO.Decision.REAPPLY_REQUIRED, "处置前核查结论", "工单 T-9"));
        String decisionId = decision.requestId();
        var response = disposals.dispose(request(operationId, Action.CONFIRM_ORIGINAL, decisionId));
        assertThat(response.kind()).isEqualTo(MarketDeliveryReviewRespVO.KIND_DISPOSAL);
        assertThat(response.decision()).isEqualTo("CONFIRM_ORIGINAL");
        assertThat(response.classification()).isEqualTo("ORIGINAL_APPLIED");
        assertThat(response.approvalStatus()).isEqualTo(ApprovalStatus.MATCHED);
        assertThat(response.resultingOperationId()).isNull();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE operation_id='" + operationId
                + "' AND status='SUCCESS' AND resolution_status='CONFIRMED' AND result_ref='42' AND row_version=6"))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND status=2 AND if_auth=1"))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_DISPOSAL'"
                + " AND result='CONFIRM_ORIGINAL' AND actor_user_id=11 AND dept_id=100"
                + " AND from_status='" + (manualUnknown ? "MANUAL" : "PENDING") + "' AND to_status='SUCCESS'"
                + " AND review_operation_id='" + operationId + "' AND creator='11'"
                + " AND review_evidence->>'disposalAction'='CONFIRM_ORIGINAL'"
                + " AND review_evidence->>'basisReviewRequestId'='" + decisionId + "'"
                + " AND review_evidence->>'basisReviewDecision'='REAPPLY_REQUIRED'"
                + " AND review_evidence->>'evidenceHash' ~ '^[0-9a-f]{64}$'")).isEqualTo(1);
        assertThat(text("SELECT review_evidence::text FROM dh_market_audit_event WHERE event_type='DELIVERY_DISPOSAL'"))
                .doesNotContain("frozenColumns", "citizen", "public");
        assertThat(disposals.dispose(new MarketDeliveryDisposalReqVO(response.requestId(), operationId,
                Action.CONFIRM_ORIGINAL, "确认原批准", "工单 T-1", decisionId))).isEqualTo(response);
        assertThat(reviews.history(operationId, 0).items()).containsExactly(decision, response);
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"NOT_FOUND", "UNKNOWN", "STALE", "REJECTED"})
    void confirmRequiresTheOriginalGrantToHaveBeenApplied(Outcome receipt) throws Exception {
        String operationId = approveAndHold(false);
        outcome = receipt;
        assertConfirmDenied(operationId, decide(operationId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HASH_MISMATCH", "APPROVAL_MISSING", "APPROVAL_CONFLICT", "LEGACY", "PAUSED",
            "SUPERSEDED", "SETTLED", "SUSPEND_OPERATION"})
    void confirmRejectsEveryIncompleteOrStaleEvidencePath(String scenario) throws Exception {
        if (scenario.equals("APPROVAL_MISSING")) {
            doReturn(1).when(approvalWriter).insertApproval( anyLong(), anyLong(), anyString(), anyString(),
                    anyLong(), any(), any());
        } else if (scenario.equals("APPROVAL_CONFLICT")) {
            // 写入格式合法但与冻结命令不符的批准摘要，模拟批准凭证与交付范围冲突。
            doAnswer(call -> approvalStore.insertApproval( call.getArgument(0),
                    call.getArgument(1), call.getArgument(2), "e".repeat(64), call.getArgument(4),
                    call.getArgument(5), call.getArgument(6))).when(approvalWriter).insertApproval( anyLong(),
                    anyLong(), anyString(), anyString(), anyLong(), any(), any());
        }
        String operationId = approveAndHold(false);
        // SETTLED 会把操作转成已结清，核查决定必须在此之前记录；其余场景先变异再记录决定，保留原确认门槛语义。
        String decisionId = scenario.equals("SETTLED") ? decide(operationId) : null;
        switch (scenario) {
            case "HASH_MISMATCH" -> receiptHash = "f".repeat(64);
            case "LEGACY" -> sql("UPDATE dh_market_delivery_operation SET protocol_version=0");
            case "PAUSED" -> sql("UPDATE dh_market_resource_apply SET paused=1 WHERE id=7");
            case "SUPERSEDED" -> sql("""
                    INSERT INTO dh_market_delivery_operation(operation_id,apply_id,source_grant_id,op_type,
                      app_id,api_id,expected_revision,status,protocol_version,source_revision,payload_json,payload_hash,
                      row_version,attempt_count,resolution_status)
                    SELECT operation_id||'-later',apply_id,source_grant_id,op_type,app_id,api_id,
                      expected_revision,'PENDING',2,source_revision,payload_json,payload_hash,0,0,'UNCONFIRMED'
                      FROM dh_market_delivery_operation
                    """);
            case "SETTLED" -> sql("UPDATE dh_market_delivery_operation SET status='SUCCESS',resolution_status='CONFIRMED'");
            case "SUSPEND_OPERATION" -> {
                applyService.pauseApply(7L, "prepared pause");
                sql("UPDATE dh_market_delivery_operation SET resolution_status='REVIEW_REQUIRED'");
                operationId = text("SELECT operation_id FROM dh_market_delivery_operation ORDER BY id DESC LIMIT 1");
                clearInvocations(coordinator, notifications);
            }
            default -> { }
        }
        assertConfirmDenied(operationId, decisionId != null ? decisionId : decide(operationId));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revokeUsesTheRealRevocationPathAndRecordsTheResultingCommand(boolean manualUnknown) throws Exception {
        String operationId = approveAndHold(manualUnknown);
        String decisionId = decide(operationId);
        long revision = scalar("SELECT source_revision FROM dh_market_delivery_operation");
        var response = disposals.dispose(request(operationId, Action.REVOKE, decisionId));
        assertThat(response.decision()).isEqualTo("REVOKE");
        assertThat(response.resultingOperationId()).isNotNull().isNotEqualTo(operationId);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND status=6 AND if_auth=0"
                + " AND check_note='处置收回' AND source_revision=" + (revision + 1))).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE operation_id='"
                + response.resultingOperationId() + "' AND op_type='REVOKE' AND status='PENDING'"
                + " AND resolution_status='UNCONFIRMED' AND source_revision=" + (revision + 1))).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE operation_id='" + operationId
                + "' AND status='" + (manualUnknown ? "MANUAL" : "PENDING") + "' AND resolution_status='"
                + (manualUnknown ? "UNKNOWN" : "REVIEW_REQUIRED") + "' AND row_version=5")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source WHERE apply_id=7 AND revoked=1"
                + " AND revision=" + (revision + 1))).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=7"
                + " AND action='REVOKE' AND operator_user_id=11 AND note='处置收回'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_DISPOSAL'"
                + " AND result='REVOKE' AND review_evidence->>'resultingOperationId'='" + response.resultingOperationId()
                + "' AND (review_evidence->>'resultingSourceRevision')::bigint=" + (revision + 1)
                + " AND review_evidence->>'basisReviewRequestId'='" + decisionId + "'")).isEqualTo(1);
        // 正式批准凭证只读引用，收回不得改写或补造。
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='GRANT_APPROVAL_EVIDENCE'"))
                .isEqualTo(1);
        verify(notifications).sendSingleMessageToAdmin(any());
        verify(coordinator).claim( anyLong(), anyString());
        assertThatThrownBy(() -> disposals.dispose(request(operationId, Action.REVOKE, decisionId)))
                .isInstanceOfSatisfying(ServiceException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(MARKET_DELIVERY_DISPOSAL_DENIED.getCode()));
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 5, 6})
    void revokeRequiresAnApplicationThatStillHoldsApproval(int status) throws Exception {
        String operationId = approveAndHold(false);
        sql("UPDATE dh_market_resource_apply SET status=" + status + " WHERE id=7");
        String decisionId = decide(operationId);
        String before = stored();
        deny(() -> disposals.dispose(request(operationId, Action.REVOKE, decisionId)), MARKET_DELIVERY_DISPOSAL_DENIED);
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(notifications, coordinator);
    }

    @ParameterizedTest
    @CsvSource({"REVOKE,SKIP", "REVOKE,RAISE", "CONFIRM_ORIGINAL,SKIP", "CONFIRM_ORIGINAL,RAISE"})
    void disposalAuditFailureRollsBackTheAuthorizationChange(Action action, String failure) throws Exception {
        String operationId = approveAndHold(false);
        String decisionId = decide(operationId);
        String before = stored();
        sql("""
                CREATE FUNCTION fail_disposal_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN %s END $$;
                CREATE TRIGGER fail_disposal_audit BEFORE INSERT ON dh_market_audit_event FOR EACH ROW
                  WHEN (NEW.event_type='DELIVERY_DISPOSAL') EXECUTE FUNCTION fail_disposal_audit();
                """.formatted(failure.equals("SKIP") ? "RETURN NULL;" : "RAISE EXCEPTION 'test disposal failure';"));
        if (failure.equals("SKIP")) {
            deny(() -> disposals.dispose(request(operationId, action, decisionId)), MARKET_OPERATION_NOT_PERSISTED);
        } else {
            assertThatThrownBy(() -> disposals.dispose(request(operationId, action, decisionId)))
                    .isInstanceOf(DataAccessException.class).hasStackTraceContaining("test disposal failure");
        }
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(notifications, coordinator);
    }

    @ParameterizedTest
    @CsvSource({"REVOKE,PAUSE", "REVOKE,OPERATION", "CONFIRM_ORIGINAL,PAUSE", "CONFIRM_ORIGINAL,OPERATION"})
    void localChangeAfterEvidenceReadRejectsTheStaleDisposal(Action action, String change) throws Exception {
        String operationId = approveAndHold(false);
        String decisionId = decide(operationId);
        doAnswer(call -> {
            try (var other = open(); var statement = other.createStatement()) {
                statement.executeUpdate(change.equals("PAUSE")
                        ? "UPDATE dh_market_resource_apply SET paused=1 WHERE id=7"
                        : "UPDATE dh_market_delivery_operation SET row_version=row_version+1");
            }
            return call.callRealMethod();
        }).when(evidenceMapper).boundLockWait();
        deny(() -> disposals.dispose(request(operationId, action, decisionId)), MARKET_APPLY_STATUS_DENY);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_DISPOSAL'")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND status=2")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE status='SUCCESS'")).isZero();
        verifyNoInteractions(notifications, coordinator);
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void providerReplacedDuringThePermissionCheckCannotDispose(Action action) throws Exception {
        String operationId = approveAndHold(false);
        String decisionId = decide(operationId);
        when(permissions.hasAnyPermissions(anyLong(), any(String[].class))).thenAnswer(call -> {
            try (var other = open(); var statement = other.createStatement()) {
                statement.executeUpdate("UPDATE dh_asset_data_asset SET tech_leader_user_id=99 WHERE id=5");
            }
            return CommonResult.success(true);
        });
        deny(() -> disposals.dispose(request(operationId, action, decisionId)), MARKET_APPLY_STATUS_DENY);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_DISPOSAL'")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND status=2")).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void nonProviderOrOuterTransactionCannotDispose(Action action) throws Exception {
        String operationId = approveAndHold(false);
        String decisionId = decide(operationId);
        String before = stored();
        login(13L, 300L);
        deny(() -> disposals.dispose(request(operationId, action, decisionId)), MARKET_APPLY_FORBIDDEN);
        login(11L, 100L);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx ->
                disposals.dispose(request(operationId, action, decisionId)))).isInstanceOf(IllegalStateException.class);
        assertThat(stored()).isEqualTo(before);
    }

    @Test
    void historyListsPendingDecisionsAndDisposalsForTheOriginalOperation() throws Exception {
        String operationId = approveAndHold(false);
        var decision = reviews.record(new MarketDeliveryReviewReqVO(UUID.randomUUID().toString(), operationId,
                MarketDeliveryReviewReqVO.Decision.REAPPLY_REQUIRED, "需撤销", "工单 T-2"));
        var disposal = disposals.dispose(request(operationId, Action.REVOKE, decision.requestId()));
        assertThat(decision.kind()).isEqualTo(MarketDeliveryReviewRespVO.KIND_DECISION);
        assertThat(reviews.history(operationId, 0).items()).containsExactly(decision, disposal);
    }

    @Test
    void singleTenantMigrationReplaysAndDisposalAuditIsAppendOnly() throws Exception {
        singleTenantConfiguration();
        String operationId = approveAndHold(false);
        String decisionId = decide(operationId);
        disposals.dispose(request(operationId, Action.CONFIRM_ORIGINAL, decisionId));
        for (String statement : List.of(
                "UPDATE dh_market_audit_event SET reason='x' WHERE event_type='DELIVERY_DISPOSAL'",
                "UPDATE dh_market_audit_event SET deleted=1 WHERE event_type='DELIVERY_DISPOSAL'",
                "DELETE FROM dh_market_audit_event WHERE event_type='DELIVERY_DISPOSAL'",
                "TRUNCATE dh_market_audit_event",
                "UPDATE dh_market_audit_event SET event_type='DELIVERY_DISPOSAL' WHERE event_type='APPROVE'",
                """
                INSERT INTO dh_market_audit_event(event_id,event_type,actor_user_id,object_type,object_id,from_status,
                  to_status,reason,result) VALUES('x','DELIVERY_DISPOSAL',11,'DELIVERY',1,'PENDING','SUCCESS','r','REVOKE')
                """)) {
            assertThatThrownBy(() -> sql(statement)).isInstanceOfSatisfying(SQLException.class,
                    failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_DISPOSAL'"
                + " AND reason='确认原批准' AND deleted=0")).isEqualTo(1);
        // 普通审计事件仍可按原生命周期维护。
        assertThat(connection.createStatement().executeUpdate(
                "UPDATE dh_market_audit_event SET reason=reason WHERE event_type='APPROVE'")).isEqualTo(1);
    }

    @Test
    void disposeRequiresABoundReviewDecisionForTheSameOperation() throws Exception {
        String operationId = approveAndHold(false);
        String before = stored();
        // 无对应决定记录的编号：绑定失败，不能处置。
        deny(() -> disposals.dispose(request(operationId, Action.CONFIRM_ORIGINAL, UUID.randomUUID().toString())),
                MARKET_DELIVERY_DISPOSAL_DENIED);
        // 缺少绑定编号：请求形状直接拒绝。
        deny(() -> disposals.dispose(new MarketDeliveryDisposalReqVO(UUID.randomUUID().toString(), operationId,
                Action.CONFIRM_ORIGINAL, "确认原批准", "工单 T-1", null)), MARKET_APPLY_STATUS_DENY);
        // 非法编号格式拒绝。
        deny(() -> disposals.dispose(new MarketDeliveryDisposalReqVO(UUID.randomUUID().toString(), operationId,
                Action.CONFIRM_ORIGINAL, "确认原批准", "工单 T-1", "not-a-uuid")), MARKET_APPLY_STATUS_DENY);
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(notifications, coordinator);
    }

    @Test
    void evidenceChangeAfterDecisionBlocksDisposalUntilANewDecisionIsRecorded() throws Exception {
        String operationId = approveAndHold(false);
        String firstDecision = decide(operationId);
        sql("UPDATE dh_market_delivery_operation SET row_version=row_version+1");
        String before = stored();
        // 决定记录后证据已变化，旧决定不能作为处置依据。
        deny(() -> disposals.dispose(request(operationId, Action.CONFIRM_ORIGINAL, firstDecision)),
                MARKET_DELIVERY_DISPOSAL_DENIED);
        assertThat(stored()).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_DISPOSAL'")).isZero();
        // 重新记录核查决定后，同一证据即可处置。
        String secondDecision = decide(operationId);
        var response = disposals.dispose(request(operationId, Action.CONFIRM_ORIGINAL, secondDecision));
        assertThat(response.decision()).isEqualTo("CONFIRM_ORIGINAL");
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE status='SUCCESS'"
                + " AND resolution_status='CONFIRMED'")).isEqualTo(1);
    }

    @Test
    void batchDisposesEachItemIndependentlyAndReportsPerItemResults() throws Exception {
        String operationId = approveAndHold(false);
        String decisionId = decide(operationId);
        var batch = new MarketDeliveryDisposalBatchReqVO(List.of(
                new MarketDeliveryDisposalBatchReqVO.Item(UUID.randomUUID().toString(), operationId,
                        Action.REVOKE, "批量收回", "工单 B-1", decisionId),
                new MarketDeliveryDisposalBatchReqVO.Item(UUID.randomUUID().toString(), "missing-operation",
                        Action.CONFIRM_ORIGINAL, "确认原批准", "工单 B-2", UUID.randomUUID().toString())));
        var response = disposals.disposeBatch(batch);
        assertThat(response.items()).hasSize(2);
        var first = response.items().get(0);
        assertThat(first.success()).isTrue();
        assertThat(first.result().decision()).isEqualTo("REVOKE");
        var second = response.items().get(1);
        assertThat(second.success()).isFalse();
        assertThat(second.errorCode()).isNotBlank();
        // 第一条真实生效，第二条失败不改变其他状态。
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND status=6")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_DISPOSAL'"
                + " AND result='REVOKE'")).isEqualTo(1);
    }

    @Test
    void batchRejectsDuplicateOperationsAndShapeErrorsUpFront() throws Exception {
        String operationId = approveAndHold(false);
        String decisionId = decide(operationId);
        var item = new MarketDeliveryDisposalBatchReqVO.Item(UUID.randomUUID().toString(), operationId,
                Action.CONFIRM_ORIGINAL, "确认原批准", "工单 B-1", decisionId);
        deny(() -> disposals.disposeBatch(new MarketDeliveryDisposalBatchReqVO(List.of(item, item))),
                MARKET_APPLY_STATUS_DENY);
        assertThatThrownBy(() -> disposals.disposeBatch(new MarketDeliveryDisposalBatchReqVO(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        deny(() -> disposals.disposeBatch(new MarketDeliveryDisposalBatchReqVO(
                List.of(new MarketDeliveryDisposalBatchReqVO.Item("not-a-uuid", operationId,
                        Action.CONFIRM_ORIGINAL, "确认原批准", "工单 B-1", decisionId)))),
                MARKET_APPLY_STATUS_DENY);
        // 形状与重复在处置前整体拒绝，没有任何审计写入。
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_DISPOSAL'")).isZero();
    }

    @Test
    void batchReplayReturnsEachItemsFirstResult() throws Exception {
        String operationId = approveAndHold(false);
        String decisionId = decide(operationId);
        var batch = new MarketDeliveryDisposalBatchReqVO(List.of(
                new MarketDeliveryDisposalBatchReqVO.Item(UUID.randomUUID().toString(), operationId,
                        Action.CONFIRM_ORIGINAL, "确认原批准", "工单 B-3", decisionId)));
        var first = disposals.disposeBatch(batch);
        assertThat(first.items().get(0).success()).isTrue();
        var replay = disposals.disposeBatch(batch);
        assertThat(replay.items().get(0).success()).isTrue();
        assertThat(replay.items().get(0).result()).isEqualTo(first.items().get(0).result());
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_DISPOSAL'")).isEqualTo(1);
    }

    /** 真实批准后，把原 GRANT 交付停在待核查状态；返回原操作号。 */
    private String approveAndHold(boolean manualUnknown) throws Exception {
        var check = new MarketCheckReqVO();
        check.setId(7L);
        check.setStatus(2);
        check.setCheckNote("original approval");
        applyService.checkApply(check);
        sql("UPDATE dh_market_delivery_operation SET row_version=5, "
                + (manualUnknown ? "status='MANUAL', resolution_status='UNKNOWN'" : "resolution_status='REVIEW_REQUIRED'"));
        clearInvocations(coordinator, notifications, permissions);
        return text("SELECT operation_id FROM dh_market_delivery_operation");
    }

    private void assertConfirmDenied(String operationId, String decisionId) throws Exception {
        String before = stored();
        deny(() -> disposals.dispose(request(operationId, Action.CONFIRM_ORIGINAL, decisionId)),
                MARKET_DELIVERY_DISPOSAL_DENIED);
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(notifications, coordinator);
    }

    /** 记录一条待办核查决定并返回其请求编号，供处置绑定。 */
    private String decide(String operationId) {
        return reviews.record(new MarketDeliveryReviewReqVO(UUID.randomUUID().toString(), operationId,
                MarketDeliveryReviewReqVO.Decision.REAPPLY_REQUIRED, "处置前核查结论", "工单 T-9")).requestId();
    }

    private static MarketDeliveryDisposalReqVO request(String operationId, Action action, String basisReviewRequestId) {
        return new MarketDeliveryDisposalReqVO(UUID.randomUUID().toString(), operationId, action,
                action == Action.REVOKE ? " 处置收回 " : " 确认原批准 ", " 工单 T-1 ", basisReviewRequestId);
    }

    private void login(long userId, Long department) {
        var login = new LoginUser();
        login.setId(userId);
        login.setTenantId(1L);
        login.setInfo(department == null ? Map.of() : Map.of(LoginUser.INFO_KEY_DEPT_ID, department.toString()));
        SecurityFrameworkUtils.setLoginUser(login, new MockHttpServletRequest());
    }

    private String stored() throws Exception {
        StringBuilder all = new StringBuilder();
        for (String table : List.of("dh_market_resource_apply", "dh_market_check_process", "dh_asset_grant_source",
                "dh_market_delivery_operation", "dh_market_audit_event")) {
            all.append(table).append('=').append(text("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text"
                    + " FROM " + table + " t")).append('\n');
        }
        return all.toString();
    }

    private String text(String query) throws Exception {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(query)) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private void fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/" + name)) {
            if (input == null) throw new IllegalStateException("Missing fixture " + name);
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void deny(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(code.getCode()));
    }
}
