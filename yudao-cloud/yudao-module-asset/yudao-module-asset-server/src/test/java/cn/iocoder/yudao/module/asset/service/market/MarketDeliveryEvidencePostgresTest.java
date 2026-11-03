package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.controller.admin.market.MarketApplyController;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryEvidenceMapper;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryEvidenceRespVO.Classification.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 PostgreSQL + 生产租户拦截器/Guard；远端仅替身，不冒充双服务部署验收。 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketDeliveryEvidencePostgresTest extends SharingPostgresSupport {
    private static final String OPERATION = "asset:asset:apply:7:r2:GRANT";
    private final SupplyGrantSourceApi remote = mock(SupplyGrantSourceApi.class);
    private final PermissionApi permissions = mock(PermissionApi.class);
    private MarketDeliveryEvidenceMapper mapper;
    private MarketApprovalEvidenceMapper approvalMapper;
    private MarketDeliveryEvidenceService service;
    private DataSourceTransactionManager transactions;
    private MockedStatic<SecurityFrameworkUtils> security;
    private SupplyGrantSourceCommandDTO command;

    @BeforeEach
    void prepare() throws Exception {
        migrate("0042_asset_grant-source-delivery.sql");
        try (var input = getClass().getResourceAsStream("/sql/market-review-fixture.sql")) {
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        migrate("0066_asset_delivery-review-evidence.sql");
        migrate("0067_asset_grant-approval-evidence.sql");
        command = grant();
        insert(command);
        var source = new UnpooledDataSource("org.postgresql.Driver", jdbcUrl + "?currentSchema=datahub-center",
                "sharing_test", "");
        transactions = new DataSourceTransactionManager(source);
        transactions.setEnforceReadOnly(true);
        var configuration = singleTenantConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("evidence-test", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        configuration.addInterceptor(interceptor);
        configuration.addMapper(MarketDeliveryEvidenceMapper.class);
        configuration.addMapper(MarketApprovalEvidenceMapper.class);
        var template = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        mapper = spy(template.getMapper(MarketDeliveryEvidenceMapper.class));
        approvalMapper = spy(template.getMapper(MarketApprovalEvidenceMapper.class));
        var guard = new MarketObjectGuard();
        ReflectionTestUtils.setField(guard, "permissionApi", permissions);
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(11L);
        security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(100L);
        TenantContextHolder.clear();
        when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return CommonResult.success(true);
        });
        when(remote.querySourceOperation("asset", OPERATION)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return CommonResult.success(receipt(SupplyGrantSourceResultDTO.Outcome.APPLIED));
        });
        service = new MarketDeliveryEvidenceService(mapper, guard, remote, transactions, approvalMapper);
    }

    @AfterEach
    void cleanContext() {
        if (security != null) security.close();
        TenantContextHolder.clear();
        verify(remote, never()).applySource(any());
    }

    private SupplyGrantSourceCommandDTO grant() {
        var scope = new SupplyGrantSourceCommandDTO.Scope("apply:7:r1", "PERSONAL", 9L,
                new SupplyGrantSourceCommandDTO.Binding(3L, "private_schema", "sensitive_table"), "secret-contract", "v1",
                "purpose", "duty", "CURRENT_USER_GOVERNANCE", List.of("secret_id", "secret_name"), false, 1000L, 2000L);
        return new SupplyGrantSourceCommandDTO(2, OPERATION, "asset", "asset:apply:7", 2, 1L, 2L,
                SupplyGrantSourceCommandDTO.Action.GRANT, scope, "original");
    }

    private void insert(SupplyGrantSourceCommandDTO value) throws Exception {
        try (var statement = connection.prepareStatement("""
                INSERT INTO dh_market_delivery_operation(id,operation_id,apply_id,source_grant_id,op_type,app_id,api_id,
                  status,protocol_version,source_revision,payload_json,payload_hash,row_version,resolution_status)
                VALUES(11,?,7,'apply:7:r1',?,1,2,'MANUAL',2,2,CAST(? AS jsonb),?,5,'REVIEW_REQUIRED')
                """)) {
            statement.setString(1, value.operationId());
            statement.setString(2, value.action() == SupplyGrantSourceCommandDTO.Action.GRANT ? "GRANT" : "REVOKE");
            statement.setString(3, MarketDeliveryPayload.toJson(value));
            statement.setString(4, MarketDeliveryPayload.hash( value));
            statement.executeUpdate();
        }
    }

    private SupplyGrantSourceResultDTO receipt(SupplyGrantSourceResultDTO.Outcome outcome) {
        return new SupplyGrantSourceResultDTO(command.operationId(), outcome, "provider-private-error",
                2, 8, outcome == SupplyGrantSourceResultDTO.Outcome.NOT_FOUND ? null : MarketDeliveryPayload.hash( command));
    }

    @Test
    void appliedReceiptWithoutFormalApprovalIsStillMissingApproval() {
        var result = service.inspect(OPERATION);
        assertThat(result.classification()).isEqualTo(ORIGINAL_APPLIED);
        assertThat(JsonUtils.toJsonString(result)).contains("\"approvalStatus\":\"MISSING\"");
    }

    @Test
    void formalApprovalMatchesIndependentlyOfReceiptAvailability() throws Exception {
        approvalMapper.insertApproval( 7, 8, "apply:7:r1", MarketDeliveryPayload.approvalHash( 8L, command), 11, 100L, "原批准");
        when(remote.querySourceOperation("asset", OPERATION)).thenThrow(new IllegalStateException("offline"));
        var result = service.inspect(OPERATION);
        assertThat(result.classification()).isEqualTo(RECEIPT_UNAVAILABLE);
        assertThat(JsonUtils.toJsonString(result)).contains("\"approvalStatus\":\"MATCHED\"")
                .doesNotContain("approvalScopeHash", "approvalRef", "approvalEvidenceId", "secret");
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"asset", "reference", "scope"})
    void appliedReceiptCannotHideConflictingFormalApproval(String change) {
        approvalMapper.insertApproval( 7, change.equals("asset") ? 9 : 8,
                change.equals("reference") ? "apply:7:r0" : "apply:7:r1",
                change.equals("scope") ? "f".repeat(64) : MarketDeliveryPayload.approvalHash( 8L, command), 11, 100L, "原批准");
        var result = service.inspect(OPERATION);
        assertThat(result.classification()).isEqualTo(ORIGINAL_APPLIED);
        assertThat(JsonUtils.toJsonString(result)).contains("\"approvalStatus\":\"CONFLICT\"");
    }

    @Test
    void rehashedFrozenPayloadDoesNotReplaceOriginalApproval() throws Exception {
        approvalMapper.insertApproval( 7, 8, "apply:7:r1", MarketDeliveryPayload.approvalHash( 8L, command), 11, 100L, "原批准");
        command = MarketDeliveryPayload.fromJson(MarketDeliveryPayload.toJson(command).replace("secret_name", "extra_secret"));
        sql("DELETE FROM dh_market_delivery_operation WHERE id=11");
        insert(command);
        var result = service.inspect(OPERATION);
        assertThat(result.classification()).isEqualTo(ORIGINAL_APPLIED);
        assertThat(JsonUtils.toJsonString(result)).contains("\"approvalStatus\":\"CONFLICT\"");
    }

    @Test
    void approvalAppearingDuringRemoteReadDiscardsOldObservation() {
        when(remote.querySourceOperation("asset", OPERATION)).thenAnswer(call -> {
            approvalMapper.insertApproval( 7, 8, "apply:7:r1", MarketDeliveryPayload.approvalHash( 8L, command), 11, 100L, "原批准");
            return CommonResult.success(receipt(SupplyGrantSourceResultDTO.Outcome.APPLIED));
        });
        var result = service.inspect(OPERATION);
        assertThat(result.classification()).isEqualTo(LOCAL_STATE_CHANGED);
        assertThat(JsonUtils.toJsonString(result)).contains("\"approvalStatus\":\"UNVERIFIABLE\"");
        assertThat(result.frozenFieldCount()).isNull();
    }



    @Test
    void failedApprovalQueryMustNotBeReportedAsMissing() {
        doThrow(new IllegalStateException("approval database unavailable")).when(approvalMapper).selectByApply( 7);
        assertThatThrownBy(() -> service.inspect(OPERATION)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(remote);
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "payload", "binding"})
    void unavailableFrozenIdentityCannotClaimAnApprovalMatch(String change) throws Exception {
        approvalMapper.insertApproval( 7, 8, "apply:7:r1", MarketDeliveryPayload.approvalHash( 8L, command), 11, 100L, "原批准");
        switch (change) {
            case "legacy" -> sql("UPDATE dh_market_delivery_operation SET protocol_version=1 WHERE id=11");
            case "payload" -> sql("UPDATE dh_market_delivery_operation SET payload_json=NULL WHERE id=11");
            case "binding" -> sql("UPDATE dh_market_resource_apply SET app_id=99 WHERE id=7");
            default -> throw new AssertionError(change);
        }
        assertThat(JsonUtils.toJsonString(service.inspect(OPERATION))).contains("\"approvalStatus\":\"UNVERIFIABLE\"");
        verifyNoInteractions(remote);
    }

    @Test
    void matchesOriginalEvidenceButDoesNotApproveOrChangeAnything() throws Exception {
        String before;
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(
                "SELECT row_to_json(o)::text FROM dh_market_delivery_operation o WHERE id=11")) {
            rs.next(); before = rs.getString(1);
        }
        var evidence = service.inspect(OPERATION);
        assertThat(evidence.classification()).isEqualTo(ORIGINAL_APPLIED);
        assertThat(evidence.frozenFieldCount()).isEqualTo(2);
        assertThat(evidence.originalAction()).isEqualTo("GRANT");
        assertThat(evidence.providerOutcome()).isEqualTo("APPLIED");
        String json = JsonUtils.toJsonString(evidence);
        assertThat(json).doesNotContain("secret", "private_schema", "sensitive_table", "provider-private-error",
                "payloadHash", "payloadJson", "usable", "approvedColumns", "permissionUserId");
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(
                "SELECT row_to_json(o)::text FROM dh_market_delivery_operation o WHERE id=11")) {
            rs.next(); assertThat(rs.getString(1)).isEqualTo(before);
        }
        verify(permissions, times(2)).hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH);
    }

    @Test
    void allSnapshotReadsAreReadOnlyAndRepeatableAndRpcHasNoTransaction() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            return call.callRealMethod();
        }).when(mapper).selectOperation( OPERATION);
        assertThat(service.inspect(OPERATION).classification()).isEqualTo(ORIGINAL_APPLIED);
        // Supply 前后各执行一次 Guard；每次 Guard 由前后两个独立的短快照包围。
        verify(mapper, times(4)).selectOperation( OPERATION);
    }

    @Test
    void refusesAmbientTransactionRatherThanHoldingLocksAcrossRpc() {
        new TransactionTemplate(transactions).executeWithoutResult(ignored ->
                assertThatThrownBy(() -> service.inspect(OPERATION)).isInstanceOf(IllegalStateException.class));
        verifyNoInteractions(mapper, remote, permissions);
    }

    @ParameterizedTest
    @CsvSource({"APPLIED, ORIGINAL_APPLIED", "STALE, ORIGINAL_STALE", "REJECTED, ORIGINAL_REJECTED",
            "NOT_FOUND, RECEIPT_NOT_FOUND", "UNKNOWN, RECEIPT_UNKNOWN"})
    void classifiesReceiptsWithoutResending(String outcome, String expected) {
        when(remote.querySourceOperation("asset", OPERATION))
                .thenReturn(CommonResult.success(receipt(SupplyGrantSourceResultDTO.Outcome.valueOf(outcome))));
        assertThat(service.inspect(OPERATION).classification().name()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPEND", "REVOKE"})
    void reportsFrozenActionRatherThanLegacyOperationType(String action) throws Exception {
        sql("DELETE FROM dh_market_delivery_operation WHERE id=11");
        command = new SupplyGrantSourceCommandDTO(2, OPERATION, "asset", "asset:apply:7", 2, 1L, 2L,
                SupplyGrantSourceCommandDTO.Action.valueOf(action), null, "original");
        insert(command);
        var result = service.inspect(OPERATION);
        assertThat(result.classification()).isEqualTo(ORIGINAL_APPLIED);
        assertThat(result.originalAction()).isEqualTo(action);
        assertThat(JsonUtils.toJsonString(result)).contains("\"approvalStatus\":\"NOT_APPLICABLE\"");
        assertThat(result.frozenFieldCount()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"operation", "hash", "revision", "not-found-operation"})
    void mismatchedReceiptCannotBeEvidence(String mismatch) {
        when(remote.querySourceOperation("asset", OPERATION)).thenReturn(CommonResult.success(
                new SupplyGrantSourceResultDTO(mismatch.contains("operation") ? "another-operation" : OPERATION,
                        mismatch.startsWith("not-found") ? SupplyGrantSourceResultDTO.Outcome.NOT_FOUND
                                : SupplyGrantSourceResultDTO.Outcome.APPLIED, null,
                        mismatch.equals("revision") ? 3 : 2, 8,
                        mismatch.equals("hash") ? "wrong" : MarketDeliveryPayload.hash( command))));
        var evidence = service.inspect(OPERATION);
        assertThat(evidence.classification()).isEqualTo(RECEIPT_CONFLICT);
        assertThat(evidence.providerOutcome()).isNull();
    }

    @Test
    void unknownWithoutHashRemainsUnknown() {
        when(remote.querySourceOperation("asset", OPERATION)).thenReturn(CommonResult.success(
                new SupplyGrantSourceResultDTO(OPERATION, SupplyGrantSourceResultDTO.Outcome.UNKNOWN, null, 0, 0, null)));
        assertThat(service.inspect(OPERATION).classification()).isEqualTo(RECEIPT_UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"throw", "null-envelope", "null-data", "failed-envelope", "null-outcome"})
    void failedQueriesAreNotAnAbsenceProof(String fault) {
        when(remote.querySourceOperation("asset", OPERATION)).thenAnswer(call -> switch (fault) {
            case "throw" -> throw new IllegalStateException("sensitive-remote-error");
            case "null-envelope" -> null;
            case "failed-envelope" -> CommonResult.error(500, "sensitive-remote-error");
            case "null-outcome" -> CommonResult.success(new SupplyGrantSourceResultDTO(OPERATION, null, null, 0, 0, null));
            default -> CommonResult.success(null);
        });
        assertThat(service.inspect(OPERATION).classification()).isEqualTo(RECEIPT_UNAVAILABLE);
    }

    @ParameterizedTest
    @CsvSource({"protocol_version=0, LEGACY_REVIEW_REQUIRED", "payload_json=NULL, FROZEN_COMMAND_UNAVAILABLE",
            "payload_json='{}'::jsonb, FROZEN_COMMAND_UNAVAILABLE", "payload_hash='bad', FROZEN_COMMAND_CONFLICT",
            "source_revision=3, FROZEN_COMMAND_CONFLICT", "source_grant_id='other-approval', FROZEN_COMMAND_CONFLICT"})
    void invalidLocalEvidenceNeverQueriesProvider(String change, String expected) throws Exception {
        sql("UPDATE dh_market_delivery_operation SET " + change + " WHERE id=11");
        assertThat(service.inspect(OPERATION).classification().name()).isEqualTo(expected);
        verifyNoInteractions(remote);
    }

    @Test
    void oversizedJsonIsNotLoadedOrSentToProvider() throws Exception {
        sql("UPDATE dh_market_delivery_operation SET payload_json=jsonb_build_object('oversized',repeat('x',4194305)) WHERE id=11");
        assertThat(mapper.selectOperation( OPERATION).getPayloadJson()).isNull();
        assertThat(service.inspect(OPERATION).classification()).isEqualTo(FROZEN_COMMAND_UNAVAILABLE);
        verifyNoInteractions(remote);
    }

    @Test
    void currentBindingConflictCannotBeUsedToInspectAnotherApi() throws Exception {
        sql("UPDATE dh_market_resource_apply SET api_id=99 WHERE id=7");
        assertThat(service.inspect(OPERATION).classification()).isEqualTo(LOCAL_BINDING_CONFLICT);
        verifyNoInteractions(remote);
    }

    @Test
    void laterApprovalAndRevocationDoNotRebuildOriginalCommandOrProveCurrentAccess() throws Exception {
        sql("UPDATE dh_market_resource_apply SET revision=99,source_revision=99,status=6,paused=1,if_auth=0 WHERE id=7");
        var result = service.inspect(OPERATION);
        assertThat(result.classification()).isEqualTo(ORIGINAL_APPLIED);
        assertThat(result.sourceRevision()).isEqualTo(2);
        assertThat(result.frozenFieldCount()).isEqualTo(2);
        verify(remote).querySourceOperation("asset", OPERATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE dh_market_delivery_operation SET row_version=6 WHERE id=11",
            "UPDATE dh_market_delivery_operation SET payload_hash='changed' WHERE id=11",
            "UPDATE dh_market_resource_apply SET status=6 WHERE id=7",
            "UPDATE dh_market_resource_apply SET source_revision=3 WHERE id=7"})
    void discardsReceiptWhenLocalStateChangesDuringRpc(String mutation) {
        when(remote.querySourceOperation("asset", OPERATION)).thenAnswer(call -> {
            sql(mutation);
            return CommonResult.success(receipt(SupplyGrantSourceResultDTO.Outcome.APPLIED));
        });
        var result = service.inspect(OPERATION);
        assertThat(result.classification()).isEqualTo(LOCAL_STATE_CHANGED);
        assertThat(result.frozenFieldCount()).isNull();
        assertThat(result.originalAction()).isNull();
        assertThat(result.providerOutcome()).isNull();
    }

    @Test
    void lostOwnershipDuringRpcIsForbiddenInsteadOfReturningOldEvidence() {
        when(remote.querySourceOperation("asset", OPERATION)).thenAnswer(call -> {
            sql("UPDATE dh_asset_data_asset SET tech_leader_user_id=99,owner_dept_id=999 WHERE id=8");
            return CommonResult.success(receipt(SupplyGrantSourceResultDTO.Outcome.APPLIED));
        });
        assertThatThrownBy(() -> service.inspect(OPERATION)).isInstanceOf(ServiceException.class);
    }

    @Test
    void revokedPermissionDuringRpcIsRechecked() {
        when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH))
                .thenReturn(CommonResult.success(true), CommonResult.success(false));
        assertThatThrownBy(() -> service.inspect(OPERATION)).isInstanceOf(ServiceException.class);
        verify(remote).querySourceOperation("asset", OPERATION);
    }

    @ParameterizedTest
    @CsvSource({"1, ownership", "2, ownership", "1, asset-deleted", "2, asset-deleted",
            "1, apply-deleted", "2, apply-deleted", "1, operation-deleted", "2, operation-deleted",
            "1, binding", "2, binding", "1, operation-version", "2, operation-version",
            "1, approval", "2, approval"})
    void stateChangedDuringPermissionRpcCannotReturnOrQueryWithOldSnapshot(int changedCall, String change) {
        var calls = new AtomicInteger();
        when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (calls.incrementAndGet() == changedCall) {
                if (change.equals("approval")) {
                    approvalMapper.insertApproval( 7, 8, "apply:7:r1",
                            MarketDeliveryPayload.approvalHash( 8L, command), 11, 100L, "原批准");
                } else {
                    sql(switch (change) {
                        case "ownership" -> "UPDATE dh_asset_data_asset SET tech_leader_user_id=99,owner_dept_id=999 WHERE id=8";
                        case "asset-deleted" -> "UPDATE dh_asset_data_asset SET deleted=1 WHERE id=8";
                        case "apply-deleted" -> "UPDATE dh_market_resource_apply SET deleted=1 WHERE id=7";
                        case "operation-deleted" -> "UPDATE dh_market_delivery_operation SET deleted=1 WHERE id=11";
                        case "binding" -> "UPDATE dh_market_resource_apply SET api_id=99 WHERE id=7";
                        case "operation-version" -> "UPDATE dh_market_delivery_operation SET row_version=6 WHERE id=11";
                        default -> throw new IllegalArgumentException(change);
                    });
                }
            }
            return CommonResult.success(true);
        });
        assertThatThrownBy(() -> service.inspect(OPERATION)).isInstanceOf(ServiceException.class);
        verify(remote, times(changedCall - 1)).querySourceOperation("asset", OPERATION);
    }

    @ParameterizedTest
    @CsvSource({"1, actor", "2, actor", "1, department", "2, department"})
    void callerChangedDuringPermissionRpcCannotReuseEarlierAuthorization(int changedCall, String change) {
        var calls = new AtomicInteger();
        when(permissions.hasAnyPermissions(12L, MarketObjectGuard.PERM_APPLY_AUTH)).thenReturn(CommonResult.success(true));
        when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH)).thenAnswer(call -> {
            if (calls.incrementAndGet() == changedCall) {
                switch (change) {
                    case "actor" -> security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(12L);
                    case "department" -> security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(999L);
                    default -> throw new IllegalArgumentException(change);
                }
            }
            return CommonResult.success(true);
        });
        assertThatThrownBy(() -> service.inspect(OPERATION)).isInstanceOfAny(ServiceException.class, IllegalStateException.class);
        verify(remote, times(changedCall - 1)).querySourceOperation("asset", OPERATION);
    }

    @Test
    void permissionWithoutObjectOwnershipCannotQuery() throws Exception {
        sql("UPDATE dh_asset_data_asset SET tech_leader_user_id=99,owner_dept_id=999 WHERE id=8");
        assertThatThrownBy(() -> service.inspect(OPERATION)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(remote);
    }

    @ParameterizedTest
    @ValueSource(strings = {"actor", "department"})
    void receiptQueryCannotSwitchToAnotherOtherwiseAuthorizedCaller(String change) {
        when(permissions.hasAnyPermissions(12L, MarketObjectGuard.PERM_APPLY_AUTH)).thenReturn(CommonResult.success(true));
        when(remote.querySourceOperation("asset", OPERATION)).thenAnswer(call -> {
            if (change.equals("actor")) {
                security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(12L);
            } else {
                security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(999L);
            }
            return CommonResult.success(receipt(SupplyGrantSourceResultDTO.Outcome.APPLIED));
        });
        assertThatThrownBy(() -> service.inspect(OPERATION)).isInstanceOf(ServiceException.class);
        verify(remote).querySourceOperation("asset", OPERATION);
        verify(permissions, times(1)).hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH);
        verify(permissions, never()).hasAnyPermissions(12L, MarketObjectGuard.PERM_APPLY_AUTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dh_market_delivery_operation", "dh_market_resource_apply", "dh_asset_data_asset"})
    void everyJoinedObjectMustRemainNotDeleted(String table) throws Exception {
        sql("UPDATE " + table + " SET deleted=1");
        assertThatThrownBy(() -> service.inspect(OPERATION)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(remote);
    }

    @Test
    void deletedLocalEvidenceDuringRpcIsNotReturned() {
        when(remote.querySourceOperation("asset", OPERATION)).thenAnswer(call -> {
            sql("UPDATE dh_market_delivery_operation SET deleted=1 WHERE id=11");
            return CommonResult.success(receipt(SupplyGrantSourceResultDTO.Outcome.APPLIED));
        });
        assertThatThrownBy(() -> service.inspect(OPERATION)).isInstanceOf(ServiceException.class);
    }



    @Test
    void controllerIsReadOnlyAndUsesExistingAuthPermission() throws Exception {
        var method = MarketApplyController.class.getMethod("getDeliveryEvidence", String.class);
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly("/delivery-evidence");
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermission('asset:market:auth')");
        var controller = new MarketApplyController();
        ReflectionTestUtils.setField(controller, "deliveryEvidenceService", service);
        assertThat(controller.getDeliveryEvidence(OPERATION).getCheckedData().classification()).isEqualTo(ORIGINAL_APPLIED);
    }
}
