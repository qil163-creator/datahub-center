package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryRecheckReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketAuditEventMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 原回执只查询；真实 PG 验证授权变化后不得返回旧详情或写账本/审计，不冒充多服务部署验收。 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketDeliveryRecheckPostgresTest extends SharingPostgresSupport {
    private static final String OPERATION = "asset:asset:apply:7:r2:REVOKE";
    private final SupplyGrantSourceApi remote = mock(SupplyGrantSourceApi.class);
    private final PermissionApi permissions = mock(PermissionApi.class);
    private MarketDeliveryOperationMapper operations;
    private MarketDeliveryEvidenceMapper evidenceMapper;
    private MarketApprovalEvidenceMapper approvals;
    private MarketDeliveryEvidenceService evidence;
    private MarketDeliveryCoordinator coordinator;
    private DataSourceTransactionManager transactions;
    private MockedStatic<SecurityFrameworkUtils> security;
    private SupplyGrantSourceCommandDTO command;
    private MarketApplyServiceImpl applyService;

    @BeforeEach
    void prepare() throws Exception {
        migrate("0042_asset_grant-source-delivery.sql");
        try (var input = getClass().getResourceAsStream("/sql/market-review-fixture.sql")) {
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        migrate("0066_asset_delivery-review-evidence.sql");
        migrate("0067_asset_grant-approval-evidence.sql");
        var source = new UnpooledDataSource("org.postgresql.Driver", jdbcUrl + "?currentSchema=datahub-center",
                "sharing_test", "");
        transactions = new DataSourceTransactionManager(source);
        transactions.setEnforceReadOnly(true);
        var config = singleTenantConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("manual-recheck-test", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        config.addInterceptor(interceptor);
        config.addMapper(MarketDeliveryOperationMapper.class);
        config.addMapper(MarketAuditEventMapper.class);
        config.addMapper(MarketDeliveryEvidenceMapper.class);
        config.addMapper(MarketApprovalEvidenceMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        operations = spy(session.getMapper(MarketDeliveryOperationMapper.class));
        evidenceMapper = spy(session.getMapper(MarketDeliveryEvidenceMapper.class));
        approvals = session.getMapper(MarketApprovalEvidenceMapper.class);
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
        evidence = new MarketDeliveryEvidenceService(evidenceMapper, guard, remote, transactions, approvals);
        coordinator = new MarketDeliveryCoordinator(operations, session.getMapper(MarketAuditEventMapper.class),
                remote, transactions, evidence, mock(MarketDeliveryReviewMapper.class), evidenceMapper);
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
        when(remote.querySourceOperation("asset", OPERATION)).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return CommonResult.success(receipt(SupplyGrantSourceResultDTO.Outcome.APPLIED));
        });
    }

    @AfterEach
    void cleanContext() {
        security.close();
        TenantContextHolder.clear();
        verify(remote, never()).applySource(any());
    }

    private SupplyGrantSourceResultDTO receipt(SupplyGrantSourceResultDTO.Outcome outcome) {
        return new SupplyGrantSourceResultDTO(OPERATION, outcome, null, 2, 8,
                outcome == SupplyGrantSourceResultDTO.Outcome.NOT_FOUND ? null : MarketDeliveryPayload.hash( command));
    }

    private boolean recheck() {
        return coordinator.reconcileManualUnknown( operations.selectByOperationId( OPERATION), 11, 100L, "核对原回执");
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "assetDeleted", "applyDeleted", "binding", "revision", "operation", "approval",
            "attempt", "result", "error"})
    void deliveryDetailRejectsLocalChangesDuringPermissionQuery(String change) throws Exception {
        when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH)).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            changeLocal(change);
            return CommonResult.success(true);
        });
        assertThatThrownBy(() -> applyService.getDeliveryOperation(OPERATION)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(remote);
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "assetDeleted", "applyDeleted", "binding", "revision", "operation", "approval",
            "attempt", "result", "error"})
    void manualRecheckReadbackRejectsNewLocalChangesWithoutUndoingCommittedReceipt(String change) throws Exception {
        var queries = new AtomicInteger();
        when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH)).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (queries.incrementAndGet() == 4) changeLocal(change);
            return CommonResult.success(true);
        });
        assertThatThrownBy(() -> applyService.recheckDelivery(new MarketDeliveryRecheckReqVO(OPERATION, "核对原回执")))
                .isInstanceOf(ServiceException.class);
        assertThat(queries.get()).isEqualTo(4);
        // 回显不是写事务：已提交的原回执及审计必须保留，不能报告成回滚或再次发送。
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE id=11 AND status='SUCCESS'"
                + " AND resolution_status='CONFIRMED' AND row_version=6")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_RECHECK'"))
                .isEqualTo(1);
        verify(remote).querySourceOperation("asset", OPERATION);
    }

    @Test
    void deliveryDetailPreservesAllResponseFieldsWithoutQueryingOrSendingSupply() throws Exception {
        sql("UPDATE dh_market_delivery_operation SET attempt_count=3,result_ref='receipt-8',last_error='待核查',"
                + "update_time=TIMESTAMP '2026-09-28 08:00:00' WHERE id=11");
        var result = applyService.getDeliveryOperation(OPERATION);
        assertThat(result.id()).isEqualTo(11L);
        assertThat(result.operationId()).isEqualTo(OPERATION);
        assertThat(result.applyId()).isEqualTo(7L);
        assertThat(result.opType()).isEqualTo("REVOKE");
        assertThat(result.sourceRevision()).isEqualTo(2L);
        assertThat(result.status()).isEqualTo("MANUAL");
        assertThat(result.resolutionStatus()).isEqualTo("UNKNOWN");
        assertThat(result.attemptCount()).isEqualTo(3);
        assertThat(result.resultRef()).isEqualTo("receipt-8");
        assertThat(result.lastError()).isEqualTo("待核查");
        assertThat(result.updateTime()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 28, 8, 0));
        assertThat(result.protocolVersion()).isEqualTo(2);
        assertThat(result.action()).isEqualTo("REVOKE");
        verifyNoInteractions(remote);
        assertUnchanged();
    }

    @Test
    void deliveryDetailRejectsAnOuterTransactionBeforePermissionRpc() {
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            assertThatThrownBy(() -> applyService.getDeliveryOperation(OPERATION)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(remote, permissions);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "userCleared", "department", "departmentCleared"})
    void deliveryDetailRejectsCallerDriftDuringPermissionQuery(String change) throws Exception {
        when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH)).thenAnswer(ignored -> {
            switch (change) {
                case "user" -> security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(12L);
                case "userCleared" -> security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(null);
                case "department" -> security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(101L);
                case "departmentCleared" -> security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(null);
                default -> throw new AssertionError(change);
            }
            return CommonResult.success(true);
        });
        assertThatThrownBy(() -> applyService.getDeliveryOperation(OPERATION))
                .isInstanceOfAny(ServiceException.class, IllegalStateException.class);
        verifyNoInteractions(remote);
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"permission", "owner"})
    void deliveryDetailStillRequiresPermissionAndProviderScope(String denied) throws Exception {
        if (denied.equals("permission")) {
            when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH))
                    .thenReturn(CommonResult.success(false));
        } else {
            changeLocal("owner");
        }
        assertThatThrownBy(() -> applyService.getDeliveryOperation(OPERATION)).isInstanceOf(ServiceException.class)
                .satisfies(failure -> assertThat(((ServiceException) failure).getCode()).isEqualTo(
                        cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FORBIDDEN.getCode()));
        verifyNoInteractions(remote);
        assertUnchanged();
    }



    @ParameterizedTest
    @ValueSource(strings = {"legacy", "missing", "oversized"})
    void unavailableFrozenEvidenceStillAllowsAuthorizedStatusReadWithoutLoadingOversizedPayload(String mode) throws Exception {
        switch (mode) {
            case "legacy" -> sql("UPDATE dh_market_delivery_operation SET protocol_version=0 WHERE id=11");
            case "missing" -> sql("UPDATE dh_market_delivery_operation SET payload_json=NULL WHERE id=11");
            case "oversized" -> sql("UPDATE dh_market_delivery_operation SET payload_json="
                    + "jsonb_build_object('test',repeat('x',4194305)) WHERE id=11");
            default -> throw new AssertionError(mode);
        }
        var result = applyService.getDeliveryOperation(OPERATION);
        assertThat(result.status()).isEqualTo("MANUAL");
        assertThat(result.resolutionStatus()).isEqualTo("UNKNOWN");
        assertThat(result.action()).isEqualTo("UNKNOWN");
        if (mode.equals("oversized")) {
            assertThat(evidenceMapper.selectOperation( OPERATION).getPayloadJson()).isNull();
        }
        verifyNoInteractions(remote);
        assertUnchanged();
    }

    @Test
    void manualRecheckReturnsAuthorizedConfirmedReceiptWithNullableDepartment() throws Exception {
        security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(null);
        var result = applyService.recheckDelivery(new MarketDeliveryRecheckReqVO(OPERATION, "核对原回执"));
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.resolutionStatus()).isEqualTo("CONFIRMED");
        assertThat(result.action()).isEqualTo("REVOKE");
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_RECHECK'"
                + " AND actor_user_id=11 AND dept_id IS NULL")).isEqualTo(1);
        verify(permissions, times(4)).hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH);
        verify(remote).querySourceOperation("asset", OPERATION);
    }

    @Test
    void unchangedAuthorizationConfirmsReceiptAndAuditTogetherWithoutSending() throws Exception {
        assertThat(recheck()).isTrue();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE status='SUCCESS'"
                + " AND resolution_status='CONFIRMED' AND row_version=6")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_RECHECK'"
                + " AND actor_user_id=11 AND dept_id=100")).isEqualTo(1);
        verify(remote).querySourceOperation("asset", OPERATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "assetDeleted", "applyDeleted", "binding", "revision", "approval", "operation"})
    void changedLocalAuthorizationDuringReceiptQueryCannotWrite(String change) throws Exception {
        when(remote.querySourceOperation("asset", OPERATION)).thenAnswer(ignored -> {
            changeLocal(change);
            return CommonResult.success(receipt(SupplyGrantSourceResultDTO.Outcome.APPLIED));
        });
        assertThatThrownBy(this::recheck).isInstanceOf(RuntimeException.class);
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "department", "permission"})
    void changedCallerOrPermissionDuringReceiptQueryCannotWrite(String change) throws Exception {
        when(remote.querySourceOperation("asset", OPERATION)).thenAnswer(ignored -> {
            switch (change) {
                case "user" -> security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(12L);
                case "department" -> security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(101L);
                case "permission" -> when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH))
                        .thenReturn(CommonResult.success(false));
                default -> throw new AssertionError(change);
            }
            return CommonResult.success(receipt(SupplyGrantSourceResultDTO.Outcome.APPLIED));
        });
        assertThatThrownBy(this::recheck).isInstanceOf(RuntimeException.class);
        assertUnchanged();
    }

    @Test
    void manualRecheckRejectsAnOuterTransactionBeforeAnyRpc() {
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            assertThatThrownBy(this::recheck).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(remote, permissions);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "assetDeleted", "applyDeleted", "binding", "revision", "operation"})
    void changedSnapshotImmediatelyBeforeLockCannotWrite(String change) throws Exception {
        doAnswer(invocation -> {
            // 使用独立 JDBC 连接，在最后一次权限复验后、数据库取锁前提交变化。
            changeLocal(change);
            return invocation.callRealMethod();
        }).when(evidenceMapper).boundLockWait();
        assertThatThrownBy(this::recheck).isInstanceOf(RuntimeException.class);
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dh_asset_data_asset", "dh_market_resource_apply", "dh_market_delivery_operation"})
    void allAuthorizationRowsRemainLockedUntilLedgerAndAuditCommit(String table) throws Exception {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            long id = switch (table) {
                case "dh_asset_data_asset" -> 8;
                case "dh_market_resource_apply" -> 7;
                default -> 11;
            };
            try (var other = open(); var statement = other.createStatement()) {
                statement.execute("SET lock_timeout='100ms'");
                assertThatThrownBy(() -> statement.executeUpdate("UPDATE " + table + " SET deleted=1 WHERE id=" + id))
                        .isInstanceOf(SQLException.class)
                        .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("55P03"));
            }
            return invocation.callRealMethod();
        }).when(operations).reconcileManualUnknown( any(), anyString(), anyString(), any(), any());
        assertThat(recheck()).isTrue();
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_RECHECK'")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void bindingChangedDuringEitherPermissionQueryIsRejected(int queryNumber) throws Exception {
        var queries = new AtomicInteger();
        when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH)).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (queries.incrementAndGet() == queryNumber) changeLocal("binding");
            return CommonResult.success(true);
        });
        assertThatThrownBy(this::recheck).isInstanceOf(RuntimeException.class);
        assertUnchanged();
        verify(remote, times(queryNumber - 1)).querySourceOperation("asset", OPERATION);
    }

    @Test
    void auditInsertFailureRollsBackTheConfirmedLedger() throws Exception {
        sql("ALTER TABLE dh_market_audit_event ADD CONSTRAINT test_recheck_audit_failure CHECK (event_type<>'DELIVERY_RECHECK')");
        assertThatThrownBy(this::recheck).isInstanceOf(RuntimeException.class);
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_FOUND", "UNKNOWN", "STALE", "REJECTED"})
    void receiptSemanticsDoNotChangeOrResendTheOriginal(String result) throws Exception {
        when(remote.querySourceOperation("asset", OPERATION)).thenReturn(
                CommonResult.success(receipt(SupplyGrantSourceResultDTO.Outcome.valueOf(result))));
        assertThat(recheck()).isTrue();
        String expected = switch (result) {
            case "STALE" -> "SUPERSEDED";
            case "REJECTED" -> "CONFIRMED";
            default -> "UNKNOWN";
        };
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE id=11 AND row_version=6"
                + " AND resolution_status='" + expected + "' AND status='"
                + (result.equals("STALE") ? "SUCCESS" : "MANUAL") + "'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_RECHECK'")).isEqualTo(1);
    }

    @Test
    void unusableFrozenPayloadIsQuarantinedOnlyAfterAuthorizationWithoutRpc() throws Exception {
        sql("UPDATE dh_market_delivery_operation SET payload_json=NULL WHERE id=11");
        assertThat(recheck()).isTrue();
        verifyNoInteractions(remote);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE id=11"
                + " AND status='MANUAL' AND resolution_status='REVIEW_REQUIRED' AND row_version=6")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_RECHECK'")).isEqualTo(1);
    }

    @Test
    void simultaneousAuthorizedRechecksCommitOnlyOneLedgerAndAudit() throws Exception {
        var readyToLock = new CountDownLatch(2);
        doAnswer(invocation -> {
            readyToLock.countDown();
            assertThat(readyToLock.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(evidenceMapper).boundLockWait();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(this::recheckInIndependentThread);
            var second = workers.submit(this::recheckInIndependentThread);
            assertThat((first.get(15, TimeUnit.SECONDS) ? 1 : 0) + (second.get(15, TimeUnit.SECONDS) ? 1 : 0))
                    .isEqualTo(1);
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE status='SUCCESS' AND row_version=6"))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_RECHECK'")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "department"})
    void identityChangedDuringLedgerWriteRollsBackLedgerAndAudit(String change) throws Exception {
        doAnswer(invocation -> {
            var changed = invocation.callRealMethod();
            switch (change) {
                case "user" -> security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(12L);
                case "department" -> security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(101L);
                default -> throw new AssertionError(change);
            }
            return changed;
        }).when(operations).reconcileManualUnknown( any(), anyString(), anyString(), any(), any());
        assertThatThrownBy(this::recheck).isInstanceOf(RuntimeException.class);
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void writeSnapshotGuardRequiresAnActualWritableTransaction(boolean readOnlyTransaction) {
        var snapshot = evidence.authorizeCurrent( OPERATION);
        Runnable assertion = () -> assertThatThrownBy(() -> evidence.assertSnapshotForWrite( snapshot, 11, 100L))
                .isInstanceOf(IllegalStateException.class);
        if (readOnlyTransaction) {
            var readOnly = new TransactionTemplate(transactions);
            readOnly.setReadOnly(true);
            readOnly.executeWithoutResult(ignored -> assertion.run());
        } else {
            assertion.run();
        }
        verify(evidenceMapper, never()).boundLockWait();
        verify(evidenceMapper, never()).lockContext( anyLong());
        verify(evidenceMapper, never()).lockOperation( anyString());
        verifyNoInteractions(remote);
    }

    private boolean recheckInIndependentThread() {
        try (var workerIdentity = mockStatic(SecurityFrameworkUtils.class)) {
            workerIdentity.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(11L);
            workerIdentity.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(100L);
            TenantContextHolder.clear();
            try {
                return recheck();
            } catch (ServiceException conflict) {
                assertThat(conflict.getCode()).isEqualTo(
                        cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_STATUS_DENY.getCode());
                return false;
            }
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void changeLocal(String change) throws Exception {
        switch (change) {
            case "owner" -> sql("UPDATE dh_asset_data_asset SET tech_leader_user_id=12,owner_dept_id=101 WHERE id=8");
            case "assetDeleted" -> sql("UPDATE dh_asset_data_asset SET deleted=1 WHERE id=8");
            case "applyDeleted" -> sql("UPDATE dh_market_resource_apply SET deleted=1 WHERE id=7");
            case "binding" -> sql("UPDATE dh_market_resource_apply SET api_id=99 WHERE id=7");
            case "revision" -> sql("UPDATE dh_market_resource_apply SET source_revision=3 WHERE id=7");
            case "operation" -> sql("UPDATE dh_market_delivery_operation SET app_id=99 WHERE id=11");
            case "attempt" -> sql("UPDATE dh_market_delivery_operation SET attempt_count=attempt_count+1 WHERE id=11");
            case "result" -> sql("UPDATE dh_market_delivery_operation SET result_ref='new-receipt' WHERE id=11");
            case "error" -> sql("UPDATE dh_market_delivery_operation SET last_error='另一次核查' WHERE id=11");
            case "approval" -> approvals.insertApproval( 7, 8, "apply:7:r1", "a".repeat(64), 11, 100L, "批准");
            default -> throw new AssertionError(change);
        }
    }

    private void assertUnchanged() throws Exception {
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE id=11 AND status='MANUAL'"
                + " AND resolution_status='UNKNOWN' AND row_version=5")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='DELIVERY_RECHECK'")).isZero();
    }
}
