package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.controller.admin.market.MarketApplyController;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryReviewReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryReviewRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryEvidenceRespVO.ApprovalStatus;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryReviewMapper;
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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryReviewReqVO.Decision.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketDeliveryReviewPostgresTest extends SharingPostgresSupport {
    private static final String OPERATION = "asset:asset:apply:7:r2:GRANT";
    private static final String MIGRATION = "0066_asset_delivery-review-evidence.sql";
    private final SupplyGrantSourceApi remote = mock(SupplyGrantSourceApi.class);
    private final PermissionApi permissions = mock(PermissionApi.class);
    private MarketDeliveryEvidenceMapper evidenceMapper;
    private MarketDeliveryReviewMapper reviewMapper;
    private MarketApprovalEvidenceMapper approvalMapper;
    private MarketDeliveryReviewService service;
    private DataSourceTransactionManager transactions;
    private MockedStatic<SecurityFrameworkUtils> security;
    private SupplyGrantSourceCommandDTO command;

    @BeforeEach
    void prepare() throws Exception {
        migrate("0042_asset_grant-source-delivery.sql");
        try (var input = getClass().getResourceAsStream("/sql/market-review-fixture.sql")) {
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        migrate(MIGRATION);
        migrate("0067_asset_grant-approval-evidence.sql");
        var scope = new SupplyGrantSourceCommandDTO.Scope("apply:7:r1", "PERSONAL", 9L,
                new SupplyGrantSourceCommandDTO.Binding(3L, "private_schema", "sensitive_table"), "secret-contract", "v1",
                "purpose", "duty", "CURRENT_USER_GOVERNANCE", List.of("secret_id", "secret_name"), false, 1000L, 2000L);
        command = new SupplyGrantSourceCommandDTO(2, OPERATION, "asset", "asset:apply:7", 2, 1L, 2L,
                SupplyGrantSourceCommandDTO.Action.GRANT, scope, "original");
        try (var statement = connection.prepareStatement("""
                INSERT INTO dh_market_delivery_operation(id,operation_id,apply_id,source_grant_id,op_type,app_id,api_id,
                  status,protocol_version,source_revision,payload_json,payload_hash,row_version,resolution_status)
                VALUES(11,?,7,'apply:7:r1','GRANT',1,2,'MANUAL',2,2,CAST(? AS jsonb),?,5,'REVIEW_REQUIRED')
                """)) {
            statement.setString(1, OPERATION);
            statement.setString(2, MarketDeliveryPayload.toJson(command));
            statement.setString(3, MarketDeliveryPayload.hash( command));
            statement.executeUpdate();
        }
        var source = new UnpooledDataSource("org.postgresql.Driver", jdbcUrl + "?currentSchema=datahub-center", "sharing_test", "");
        transactions = new DataSourceTransactionManager(source);
        transactions.setEnforceReadOnly(true);
        var configuration = singleTenantConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("review-test", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        configuration.addInterceptor(interceptor);
        configuration.addMapper(MarketDeliveryEvidenceMapper.class);
        configuration.addMapper(MarketDeliveryReviewMapper.class);
        configuration.addMapper(MarketApprovalEvidenceMapper.class);
        var template = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        evidenceMapper = spy(template.getMapper(MarketDeliveryEvidenceMapper.class));
        reviewMapper = spy(template.getMapper(MarketDeliveryReviewMapper.class));
        approvalMapper = spy(template.getMapper(MarketApprovalEvidenceMapper.class));
        var guard = new MarketObjectGuard();
        ReflectionTestUtils.setField(guard, "permissionApi", permissions);
        security = mockStatic(SecurityFrameworkUtils.class);
        identity(security);
        TenantContextHolder.clear();
        when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return CommonResult.success(true);
        });
        when(remote.querySourceOperation("asset", OPERATION)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return CommonResult.success(receipt());
        });
        var evidence = new MarketDeliveryEvidenceService(evidenceMapper, guard, remote, transactions, approvalMapper);
        service = new MarketDeliveryReviewService(evidence, evidenceMapper, reviewMapper, guard, transactions, approvalMapper);
    }

    private void identity(MockedStatic<SecurityFrameworkUtils> context) {
        context.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(11L);
        context.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(100L);
    }

    private SupplyGrantSourceResultDTO receipt() {
        return new SupplyGrantSourceResultDTO(OPERATION, SupplyGrantSourceResultDTO.Outcome.APPLIED, null,
                2, 8, MarketDeliveryPayload.hash( command));
    }

    private MarketDeliveryReviewReqVO request() {
        return new MarketDeliveryReviewReqVO(UUID.randomUUID().toString(), OPERATION, KEEP_PENDING, " 继续核查 ", " 工单：测试复核 ");
    }

    @AfterEach
    void cleanupContext() {
        if (security != null) security.close();
        TenantContextHolder.clear();
        verify(remote, never()).applySource(any());
    }

    @Test
    void persistsActorReasonBasisAndServerEvidenceWithoutChangingGrantState() throws Exception {
        var response = service.record(request());
        assertThat(response.id()).isPositive();
        assertThat(response.actorUserId()).isEqualTo(11);
        assertThat(response.actorDeptId()).isEqualTo(100);
        assertThat(response.reason()).isEqualTo("继续核查");
        assertThat(response.basis()).isEqualTo("工单：测试复核");
        assertThat(response.classification()).isEqualTo("ORIGINAL_APPLIED");
        assertThat(response.approvalStatus()).isEqualTo(ApprovalStatus.MISSING);
        assertThat(response.decision()).isEqualTo("KEEP_PENDING");
        assertThat(response.occurredAt()).isNotNull();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE status='MANUAL' AND resolution_status='REVIEW_REQUIRED' AND row_version=5")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE status=2 AND if_auth=1 AND source_revision=2")).isEqualTo(1);
        try (var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT review_evidence::text FROM dh_market_audit_event")) {
            rs.next();
            assertThat(rs.getString(1)).contains("evidenceHash", "receiptDigest", "contextDigest", "payloadDigest", "operationRowVersion")
                    .doesNotContain("secret_name", "secret_id", "private_schema", "sensitive_table", "secret-contract");
        }
        assertThat(JsonUtils.toJsonString(response)).doesNotContain("Digest", "Hash", "payload", "evidence", "secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MATCHED", "CONFLICT"})
    void storesFormalApprovalObservationSeparatelyFromAppliedReceipt(String status) throws Exception {
        approvalMapper.insertApproval( 7, 8, "apply:7:r1",
                status.equals("MATCHED") ? MarketDeliveryPayload.approvalHash( 8L, command) : "e".repeat(64), 11, 100L, "原批准");
        var request = request();
        var response = service.record(request);
        assertThat(response.classification()).isEqualTo("ORIGINAL_APPLIED");
        assertThat(response.approvalStatus().name()).isEqualTo(status);
        assertThat(service.history(OPERATION, 0).items()).containsExactly(response);
        assertThat(service.record(request)).isEqualTo(response);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_REVIEW_DECISION'"
                + " AND (review_evidence->>'approvalEvidenceId')::bigint IN (SELECT id FROM dh_market_audit_event WHERE event_type='GRANT_APPROVAL_EVIDENCE')"
                + " AND review_evidence->>'approvalEvidenceDigest' ~ '^[0-9a-f]{64}$'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE status='MANUAL' AND resolution_status='REVIEW_REQUIRED'")).isEqualTo(1);
        assertThat(JsonUtils.toJsonString(response)).doesNotContain("approvalEvidenceId", "Digest", "Hash", "secret");
        verify(remote, times(1)).querySourceOperation("asset", OPERATION);
    }

    @Test
    void laterApprovalCannotRewritePreviousMissingObservation() {
        var request = request();
        var first = service.record(request);
        assertThat(first.approvalStatus()).isEqualTo(ApprovalStatus.MISSING);
        approvalMapper.insertApproval( 7, 8, "apply:7:r1", MarketDeliveryPayload.approvalHash( 8L, command), 11, 100L, "原批准");
        assertThat(service.record(request)).isEqualTo(first);
        assertThat(service.history(OPERATION, 0).items()).containsExactly(first);
        assertThat(service.record(request()).approvalStatus()).isEqualTo(ApprovalStatus.MATCHED);
        verify(remote, times(2)).querySourceOperation("asset", OPERATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unknown"})
    void oldOrUnrecognizedAuditStatusIsUnverifiableAndNeverBackfilled(String value) throws Exception {
        var request = request();
        service.record(request);
        // 模拟升级前记录/后续未知扩展；只读历史不得根据现有凭证重新推导。
        sql(value.equals("missing")
                ? "UPDATE dh_market_audit_event SET review_evidence=review_evidence-'approvalStatus' WHERE event_type='DELIVERY_REVIEW_DECISION'"
                : "UPDATE dh_market_audit_event SET review_evidence=jsonb_set(review_evidence,'{approvalStatus}','\"FUTURE_STATUS\"') WHERE event_type='DELIVERY_REVIEW_DECISION'");
        approvalMapper.insertApproval( 7, 8, "apply:7:r1", MarketDeliveryPayload.approvalHash( 8L, command), 11, 100L, "原批准");
        assertThat(service.record(request).approvalStatus()).isEqualTo(ApprovalStatus.UNVERIFIABLE);
        assertThat(service.history(OPERATION, 0).items().getFirst().approvalStatus()).isEqualTo(ApprovalStatus.UNVERIFIABLE);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_REVIEW_DECISION'"
                + " AND review_evidence->>'approvalStatus'='MATCHED'")).isZero();
    }

    @Test
    void approvalAppearingBeforeFinalWriteRejectsStaleAudit() throws Exception {
        doAnswer(call -> {
            // 模拟另一个已提交审批发生在观察完成后、取得申请行锁前。
            var approvalTx = new TransactionTemplate(transactions);
            approvalTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            approvalTx.executeWithoutResult(ignored -> approvalMapper.insertApproval( 7, 8, "apply:7:r1",
                    MarketDeliveryPayload.approvalHash( 8L, command), 11, 100L, "原批准"));
            return call.callRealMethod();
        }).when(evidenceMapper).lockContext( 7);
        assertThatThrownBy(() -> service.record(request())).isInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_REVIEW_DECISION'")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='GRANT_APPROVAL_EVIDENCE'")).isEqualTo(1);
    }

    @Test
    void lostResponseRetryReturnsOriginalAuditWithoutRequeryingRemote() throws Exception {
        var request = request();
        var first = service.record(request);
        sql("UPDATE dh_market_delivery_operation SET status='SUCCESS',resolution_status='CONFIRMED',row_version=6 WHERE id=11");
        var replay = service.record(request);
        assertThat(replay).isEqualTo(first);
        verify(remote, times(1)).querySourceOperation("asset", OPERATION);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"decision", "reason", "basis", "actor", "deleted"})
    void reusedRequestIdentityCannotOverwriteOrResurrectAudit(String change) throws Exception {
        var original = request();
        service.record(original);
        var changed = new MarketDeliveryReviewReqVO(original.requestId(), OPERATION,
                change.equals("decision") ? REAPPLY_REQUIRED : KEEP_PENDING,
                change.equals("reason") ? "另一原因" : original.reason(),
                change.equals("basis") ? "另一依据" : original.basis());
        if (change.equals("actor")) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(12L);
            when(permissions.hasAnyPermissions(12L, MarketObjectGuard.PERM_APPLY_AUTH)).thenReturn(CommonResult.success(true));
        }
        if (change.equals("deleted")) sql("UPDATE dh_market_audit_event SET deleted=1");
        assertThatThrownBy(() -> service.record(changed)).isInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
    }

    @Test
    void legacyAndUnknownCanRecordPendingConclusionWithoutPretendingToRevoke() throws Exception {
        sql("UPDATE dh_market_delivery_operation SET protocol_version=0,payload_json=NULL,status='PENDING' WHERE id=11");
        var request = request();
        var response = service.record(new MarketDeliveryReviewReqVO(request.requestId(), OPERATION, REAPPLY_REQUIRED, "缺原审批", "旧记录核查工单"));
        assertThat(response.classification()).isEqualTo("LEGACY_REVIEW_REQUIRED");
        assertThat(response.decision()).isEqualTo("REAPPLY_REQUIRED");
        verifyNoInteractions(remote);
        sql("UPDATE dh_market_delivery_operation SET protocol_version=2,status='MANUAL',resolution_status='UNKNOWN' WHERE id=11");
        assertThat(service.record(request()).classification()).isEqualTo("FROZEN_COMMAND_UNAVAILABLE");
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE status='MANUAL' AND resolution_status='UNKNOWN'")).isEqualTo(1);
    }

    @Test
    void ordinaryConfirmedOperationCannotCreatePendingReview() throws Exception {
        sql("UPDATE dh_market_delivery_operation SET status='SUCCESS',resolution_status='CONFIRMED' WHERE id=11");
        assertThatThrownBy(() -> service.record(request())).isInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE dh_market_resource_apply SET status=6 WHERE id=7",
            "UPDATE dh_market_delivery_operation SET row_version=6 WHERE id=11",
            "UPDATE dh_asset_data_asset SET tech_leader_user_id=99,owner_dept_id=999 WHERE id=8"})
    void changeBetweenEvidenceAndPersistenceRejectsOldObservation(String mutation) throws Exception {
        doAnswer(call -> { sql(mutation); return call.callRealMethod(); }).when(evidenceMapper).lockContext( 7);
        assertThatThrownBy(() -> service.record(request())).isInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isZero();
    }

    @Test
    void evidenceChangedDuringRemoteQueryCannotBePersisted() throws Exception {
        when(remote.querySourceOperation("asset", OPERATION)).thenAnswer(call -> {
            sql("UPDATE dh_market_delivery_operation SET payload_hash='changed' WHERE id=11");
            return CommonResult.success(receipt());
        });
        assertThatThrownBy(() -> service.record(request())).isInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isZero();
    }

    @Test
    void finalSnapshotLocksPreventConcurrentOwnershipChangesUntilAuditCommits() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            try (var concurrent = open(); var statement = concurrent.createStatement()) {
                statement.execute("SET lock_timeout='100ms'");
                assertThatThrownBy(() -> statement.execute("UPDATE dh_asset_data_asset SET owner_dept_id=999 WHERE id=8"))
                        .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("55P03");
            }
            return call.callRealMethod();
        }).when(reviewMapper).insertDecision( any(), anyLong(), anyString(), anyString());
        assertThat(service.record(request()).id()).isPositive();
    }

    @Test
    void failureAfterInsertRollsBackAuditAndRetryIsSafe() throws Exception {
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("test failure after insert"); })
                .when(reviewMapper).insertDecision( any(), anyLong(), anyString(), anyString());
        var request = request();
        assertThatThrownBy(() -> service.record(request)).isInstanceOf(IllegalStateException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isZero();
        doCallRealMethod().when(reviewMapper).insertDecision( any(), anyLong(), anyString(), anyString());
        assertThat(service.record(request).id()).isPositive();
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
    }

    @Test
    void concurrentNodesPersistOnlyOneAuditAndReturnSameIdentity() throws Exception {
        var arrivals = new CountDownLatch(2);
        when(remote.querySourceOperation("asset", OPERATION)).thenAnswer(call -> {
            arrivals.countDown();
            assertThat(arrivals.await(5, TimeUnit.SECONDS)).isTrue();
            return CommonResult.success(receipt());
        });
        var request = request();
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<MarketDeliveryReviewRespVO> node = () -> {
                TenantContextHolder.clear();
                try (var threadSecurity = mockStatic(SecurityFrameworkUtils.class)) {
                    identity(threadSecurity);
                    return service.record(request);
                } finally { TenantContextHolder.clear(); }
            };
            var a = executor.submit(node);
            var b = executor.submit(node);
            assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(b.get(15, TimeUnit.SECONDS));
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
    }

    @Test
    void historyIsBoundedAndObjectScopedAndDoesNotRequeryRemote() throws Exception {
        service.record(request());
        clearInvocations(remote);
        sql("""
                INSERT INTO dh_market_audit_event(event_id,event_type,actor_user_id,dept_id,object_type,object_id,
                    from_status,to_status,reason,result,review_operation_id,review_request_hash,review_evidence)
                SELECT md5(event_id||n::text),'DELIVERY_REVIEW_DECISION',actor_user_id,dept_id,object_type,object_id,
                    from_status,to_status,reason,result,review_operation_id,review_request_hash,review_evidence
                FROM dh_market_audit_event CROSS JOIN generate_series(1,55) n;
                """);
        var first = service.history(OPERATION, 0);
        assertThat(first.items()).hasSize(50);
        assertThat(first.hasMore()).isTrue();
        var next = service.history(OPERATION, first.nextId());
        assertThat(next.items()).hasSize(6);
        assertThat(next.hasMore()).isFalse();
        assertThat(next.nextId()).isNull();
        verifyNoInteractions(remote);
        TenantContextHolder.clear();
        sql("UPDATE dh_asset_data_asset SET tech_leader_user_id=99,owner_dept_id=999 WHERE id=8");
        assertThatThrownBy(() -> service.history(OPERATION, 0)).isInstanceOf(ServiceException.class);
    }

    @Test
    void lackOfPermissionAndAmbientTransactionsCannotWrite() throws Exception {
        when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH)).thenReturn(CommonResult.success(false));
        assertThatThrownBy(() -> service.record(request())).isInstanceOf(ServiceException.class);
        new TransactionTemplate(transactions).executeWithoutResult(ignored ->
                assertThatThrownBy(() -> service.record(request())).isInstanceOf(IllegalStateException.class));
        verifyNoInteractions(remote);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isZero();
    }

    @Test
    void singleTenantMigrationReplayPreservesReviewConstraintsAndAudit() throws Exception {
        singleTenantConfiguration();
        var request = request();
        service.record(request);
        singleTenantConfiguration();
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_market_audit_event' AND column_name LIKE 'review_%'")).isEqualTo(3);
        String constraint = constraint();
        assertThat(constraint()).isEqualTo(constraint);
        assertThat(service.record(request()).id()).isPositive();
        singleTenantConfiguration();
    }

    private String constraint() throws Exception {
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='dh_market_audit_event'::regclass AND conname='ck_market_delivery_review_evidence'")) {
            rs.next(); return rs.getString(1);
        }
    }

    @Test
    void databaseRejectsInvalidDecisionOrMissingEvidenceAndPreservesOldAuditTypes() throws Exception {
        sql("INSERT INTO dh_market_audit_event(event_id,event_type,object_type) VALUES('old-event','DELIVERY_RECHECK','DELIVERY')");
        service.record(request());
        assertThatThrownBy(() -> sql("UPDATE dh_market_audit_event SET result='CONFIRM_GRANT' WHERE event_type='DELIVERY_REVIEW_DECISION'"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> sql("UPDATE dh_market_audit_event SET review_evidence='{}'::jsonb WHERE event_type='DELIVERY_REVIEW_DECISION'"))
                .isInstanceOf(SQLException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(2);
    }

    @Test
    void ownershipLostWhileReadingReplayCannotExposeSavedAudit() throws Exception {
        var request = request();
        service.record(request);
        doAnswer(call -> {
            var saved = call.callRealMethod();
            sql("UPDATE dh_asset_data_asset SET tech_leader_user_id=99,owner_dept_id=999 WHERE id=8");
            return saved;
        }).when(reviewMapper).selectRequest( request.requestId());
        assertThatThrownBy(() -> service.record(request)).isInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE dh_asset_data_asset SET tech_leader_user_id=99,owner_dept_id=999 WHERE id=8",
            "UPDATE dh_asset_data_asset SET deleted=1 WHERE id=8"})
    void replayRejectsAssetChangedDuringFinalPermissionRpc(String mutation) throws Exception {
        var request = request();
        service.record(request);
        var saved = reviewMapper.selectRequest( request.requestId());
        clearInvocations(permissions, remote, reviewMapper);
        var permissionCalls = mutateDuringFinalPermissionRpc(mutation);

        assertThatThrownBy(() -> service.record(request)).isInstanceOf(ServiceException.class);

        assertThat(permissionCalls.get()).isEqualTo(2);
        verify(permissions, times(2)).hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH);
        verify(reviewMapper).selectRequest( request.requestId());
        verifyNoInteractions(remote);
        assertThat(reviewMapper.selectRequest( request.requestId())).isEqualTo(saved);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE dh_asset_data_asset SET tech_leader_user_id=99,owner_dept_id=999 WHERE id=8",
            "UPDATE dh_market_resource_apply SET api_id=99 WHERE id=7"})
    void historyRejectsOwnershipOrBindingChangedDuringFinalPermissionRpc(String mutation) throws Exception {
        var request = request();
        service.record(request);
        var saved = reviewMapper.selectRequest( request.requestId());
        clearInvocations(permissions, remote, reviewMapper);
        var permissionCalls = mutateDuringFinalPermissionRpc(mutation);

        assertThatThrownBy(() -> service.history(OPERATION, 0)).isInstanceOf(ServiceException.class);

        assertThat(permissionCalls.get()).isEqualTo(2);
        verify(permissions, times(2)).hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH);
        verify(reviewMapper).selectHistory( OPERATION, 0);
        verifyNoInteractions(remote);
        assertThat(reviewMapper.selectRequest( request.requestId())).isEqualTo(saved);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"actor", "department"})
    void replayRejectsIdentityChangedBetweenAuthorizationsEvenWhenBothCanProvide(String change) throws Exception {
        var request = request();
        service.record(request);
        clearInvocations(remote, reviewMapper);
        doAnswer(call -> {
            var saved = call.callRealMethod();
            switchToAnotherAllowedIdentity(change);
            return saved;
        }).when(reviewMapper).selectRequest( request.requestId());

        assertThatThrownBy(() -> service.record(request)).isInstanceOf(ServiceException.class);

        verify(reviewMapper).selectRequest( request.requestId());
        verifyNoInteractions(remote);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"actor", "department"})
    void historyRejectsIdentityChangedBetweenAuthorizationsEvenWhenBothCanProvide(String change) throws Exception {
        service.record(request());
        clearInvocations(remote, reviewMapper);
        doAnswer(call -> {
            var rows = call.callRealMethod();
            switchToAnotherAllowedIdentity(change);
            return rows;
        }).when(reviewMapper).selectHistory( OPERATION, 0);

        assertThatThrownBy(() -> service.history(OPERATION, 0)).isInstanceOf(ServiceException.class);

        verify(reviewMapper).selectHistory( OPERATION, 0);
        verifyNoInteractions(remote);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
    }

    private void switchToAnotherAllowedIdentity(String change) {
        if (change.equals("actor")) {
            // 新用户有相同功能权限且部门仍是资产 owner，第二次 Guard 本身能够通过。
            doAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return CommonResult.success(true);
            }).when(permissions).hasAnyPermissions(12L, MarketObjectGuard.PERM_APPLY_AUTH);
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(12L);
        } else {
            // 用户 11 仍是 tech leader，部门变更也不能仅靠 isProvider 拒绝来掩盖身份窗口。
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(999L);
        }
    }

    private AtomicInteger mutateDuringFinalPermissionRpc(String mutation) {
        var calls = new AtomicInteger();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // 两条读取路径均先授权、读取已保存审计，再最后授权；不在首次授权或 Supply RPC 中变更。
            if (calls.incrementAndGet() == 2) {
                try (var concurrent = open(); var statement = concurrent.createStatement()) {
                    assertThat(concurrent.getAutoCommit()).isTrue();
                    statement.execute("SET lock_timeout='1s'");
                    assertThat(statement.executeUpdate(mutation)).isEqualTo(1);
                }
            }
            return CommonResult.success(true);
        }).when(permissions).hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH);
        return calls;
    }

    @Test
    void sameRequestIdReusesPlatformRecordWithoutTenantContext() throws Exception {
        TenantContextHolder.clear();
        var request = request();
        var first = service.record(request);
        assertThat(service.record(request).id()).isEqualTo(first.id());
        assertThat(service.history(OPERATION, 0).items()).containsExactly(first);
    }

    @Test
    void databaseRequiresUnchangedNonNullAfterState() throws Exception {
        service.record(request());
        assertThatThrownBy(() -> sql("UPDATE dh_market_audit_event SET to_status=NULL WHERE event_type='DELIVERY_REVIEW_DECISION'"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void controllerUsesAuthPermissionAndRequestValidationRejectsUnboundedInput() throws Exception {
        assertThat(MarketApplyController.class.getMethod("recordDeliveryReview", MarketDeliveryReviewReqVO.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermission('asset:market:auth')");
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request())).isEmpty();
            assertThat(factory.getValidator().validate(new MarketDeliveryReviewReqVO("bad", "x".repeat(129), null, "", "x".repeat(501))))
                    .hasSize(5);
        }
        assertThatThrownBy(() -> service.record(new MarketDeliveryReviewReqVO("bad", OPERATION, KEEP_PENDING, "", "")))
                .isInstanceOf(ServiceException.class);
        verifyNoInteractions(remote);
    }
}
