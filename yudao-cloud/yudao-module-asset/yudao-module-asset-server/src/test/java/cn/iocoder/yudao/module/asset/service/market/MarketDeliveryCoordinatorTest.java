package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketAuditEventDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketAuditEventMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryReviewMapper;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantSourceApi;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceResultDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 交付协调：重试必须先查原回执，未知结果保持 UNKNOWN，过期令牌不能回写，确定拒绝不无限重试。
 */
class MarketDeliveryCoordinatorTest {

    private static final long TENANT = 1L;

    private MarketDeliveryOperationMapper operationMapper;
    private MarketAuditEventMapper auditEventMapper;
    private SupplyGrantSourceApi grantSourceApi;
    private PlatformTransactionManager transactionManager;
    private MarketDeliveryEvidenceService evidenceService;
    private MarketDeliveryCoordinator coordinator;

    private static SupplyGrantSourceCommandDTO revokeCommand() {
        return new SupplyGrantSourceCommandDTO(2, "asset:asset:apply:7:r3:REVOKE", "asset",
                "asset:apply:7", 3, 1L, 2L, SupplyGrantSourceCommandDTO.Action.REVOKE, null, "撤销");
    }

    private static SupplyGrantSourceCommandDTO grantCommand() {
        var scope = new SupplyGrantSourceCommandDTO.Scope("apply:7:r1", "PERSONAL", 9L,
                new SupplyGrantSourceCommandDTO.Binding(3L, "public", "citizen"), "contract-1", "v1",
                "核验", "事项1", "CURRENT_USER_GOVERNANCE", List.of("id", "name"), false, 1000L, 2000L);
        return new SupplyGrantSourceCommandDTO(2, "asset:asset:apply:7:r2:GRANT", "asset",
                "asset:apply:7", 2, 1L, 2L, SupplyGrantSourceCommandDTO.Action.GRANT, scope, "开通");
    }

    private static MarketDeliveryOperationDO claimed(SupplyGrantSourceCommandDTO command, int attempt) {
        return MarketDeliveryOperationDO.builder()
                .id(11L).operationId(command.operationId()).applyId(7L).appId(1L).apiId(2L)
                .sourceGrantId("apply:7:r1")
                .opType(command.action() == SupplyGrantSourceCommandDTO.Action.GRANT ? "GRANT" : "REVOKE")
                .sourceRevision(command.sourceRevision())
                .claimToken("node-a").rowVersion(1L).attemptCount(attempt)
                .payloadJson(MarketDeliveryPayload.toJson(command))
                .payloadHash(MarketDeliveryPayload.hash( command)).build();
    }

    private static MarketDeliveryOperationDO manualUnknown(SupplyGrantSourceCommandDTO command) {
        MarketDeliveryOperationDO row = claimed(command, 5);
        row.setStatus(MarketDeliveryOperationDO.STATUS_MANUAL);
        row.setResolutionStatus(MarketDeliveryCoordinator.RESOLUTION_UNKNOWN);
        row.setProtocolVersion(2);
        return row;
    }

    private static SupplyGrantSourceResultDTO receipt(SupplyGrantSourceCommandDTO command,
                                                       SupplyGrantSourceResultDTO.Outcome outcome) {
        return new SupplyGrantSourceResultDTO(command.operationId(), outcome, null, command.sourceRevision(), 8,
                outcome == SupplyGrantSourceResultDTO.Outcome.NOT_FOUND ? null : MarketDeliveryPayload.hash( command));
    }

    @Test
    void legacyLimitedPolicyIsQuarantinedBeforeGrantRpc() {
        when(operationMapper.unlimitedPolicy(7L)).thenReturn(0);
        coordinator.execute( claimed(grantCommand(), 1));
        verify(grantSourceApi, never()).applySource(any());
        verify(operationMapper).completeClaim( eq(11L), eq("node-a"), eq(1L),
                eq(MarketDeliveryOperationDO.STATUS_MANUAL), eq(MarketDeliveryCoordinator.RESOLUTION_REVIEW_REQUIRED),
                org.mockito.ArgumentMatchers.isNull(), anyString());
    }

    @BeforeEach
    void setUp() {
        operationMapper = mock(MarketDeliveryOperationMapper.class);
        when(operationMapper.unlimitedPolicy(anyLong())).thenReturn(1);
        auditEventMapper = mock(MarketAuditEventMapper.class);
        grantSourceApi = mock(SupplyGrantSourceApi.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        evidenceService = mock(MarketDeliveryEvidenceService.class);
        when(evidenceService.authorizeCurrent( anyString())).thenAnswer(invocation ->
                new MarketDeliveryEvidenceService.Snapshot(
                        operationMapper.selectManualUnknown( 11L), null, null));
        coordinator = new MarketDeliveryCoordinator(operationMapper, auditEventMapper, grantSourceApi, transactionManager,
                evidenceService, mock(MarketDeliveryReviewMapper.class), mock(MarketDeliveryEvidenceMapper.class));
        when(operationMapper.completeClaim( anyLong(), anyString(), anyLong(), anyString(),
                anyString(), any(), any())).thenReturn(1);
        when(operationMapper.reconcileManualUnknown( any(), anyString(), anyString(), any(), any()))
                .thenReturn(1);
        when(auditEventMapper.insertDeliveryReview( any(MarketAuditEventDO.class))).thenReturn(1);
    }

    @Test
    void firstAttemptSendsTheFrozenCommandWithoutQuerying() {
        when(grantSourceApi.applySource(any())).thenReturn(
                CommonResult.success(receipt(revokeCommand(), SupplyGrantSourceResultDTO.Outcome.APPLIED)));
        assertThat(coordinator.execute( claimed(revokeCommand(), 1))).isTrue();
        verify(grantSourceApi, never()).querySourceOperation(anyString(), anyString());
        verify(grantSourceApi).applySource(revokeCommand());
        verify(operationMapper).completeClaim( 11L, "node-a", 1L, "SUCCESS", "CONFIRMED", "8", null);
    }

    @Test
    void retryQueriesTheOriginalReceiptBeforeResending() {
        when(grantSourceApi.querySourceOperation(anyString(), anyString())).thenReturn(
                CommonResult.success(receipt(revokeCommand(), SupplyGrantSourceResultDTO.Outcome.NOT_FOUND)));
        when(grantSourceApi.applySource(any())).thenReturn(
                CommonResult.success(receipt(revokeCommand(), SupplyGrantSourceResultDTO.Outcome.APPLIED)));
        SupplyGrantSourceCommandDTO command = revokeCommand();
        assertThat(coordinator.execute( claimed(command, 2))).isTrue();
        InOrder order = inOrder(grantSourceApi);
        order.verify(grantSourceApi).querySourceOperation(command.sourceSystem(), command.operationId());
        order.verify(grantSourceApi).applySource(command);
    }

    @Test
    void alreadyAppliedOperationIsNotSentAgain() {
        when(grantSourceApi.querySourceOperation(anyString(), anyString())).thenReturn(
                CommonResult.success(receipt(revokeCommand(), SupplyGrantSourceResultDTO.Outcome.APPLIED)));
        assertThat(coordinator.execute( claimed(revokeCommand(), 3))).isTrue();
        verify(grantSourceApi, never()).applySource(any());
        verify(operationMapper).completeClaim( 11L, "node-a", 1L, "SUCCESS", "CONFIRMED", "8", null);
    }

    @Test
    void failedQueryNeverResendsAndKeepsUnknown() {
        when(grantSourceApi.querySourceOperation(anyString(), anyString()))
                .thenThrow(new IllegalStateException("connection reset"));
        assertThat(coordinator.execute( claimed(revokeCommand(), 2))).isTrue();
        verify(grantSourceApi, never()).applySource(any());
        verify(operationMapper).completeClaim( 11L, "node-a", 1L, "RETRY", "UNKNOWN", null,
                "远端结果未确认");
    }

    @Test
    void unknownReceiptStaysUnknownAndBecomesManualAfterTheAttemptBudget() {
        when(grantSourceApi.applySource(any())).thenReturn(
                CommonResult.success(receipt(revokeCommand(), SupplyGrantSourceResultDTO.Outcome.UNKNOWN)));
        assertThat(coordinator.execute( claimed(revokeCommand(), 1))).isTrue();
        verify(operationMapper).completeClaim( 11L, "node-a", 1L, "RETRY", "UNKNOWN", null,
                "提供方结果未确认");
        when(grantSourceApi.querySourceOperation(anyString(), anyString())).thenReturn(
                CommonResult.success(receipt(revokeCommand(), SupplyGrantSourceResultDTO.Outcome.UNKNOWN)));
        assertThat(coordinator.execute( claimed(revokeCommand(), 5))).isTrue();
        verify(operationMapper).completeClaim( 11L, "node-a", 1L, "MANUAL", "UNKNOWN", null,
                "提供方结果未确认");
    }

    @Test
    void transportFailureBecomesManualOnlyAfterTheAttemptBudget() {
        when(grantSourceApi.applySource(any())).thenThrow(new IllegalStateException("timeout"));
        assertThat(coordinator.execute( claimed(grantCommand(), 1))).isTrue();
        verify(operationMapper).completeClaim( 11L, "node-a", 1L, "RETRY", "UNKNOWN", null,
                "远端结果未确认");
    }

    @Test
    void definiteRejectionIsNotRetriedForever() {
        SupplyGrantSourceCommandDTO command = grantCommand();
        when(grantSourceApi.applySource(any())).thenReturn(CommonResult.success(
                new SupplyGrantSourceResultDTO(command.operationId(), SupplyGrantSourceResultDTO.Outcome.REJECTED,
                        "CONTRACT_MISMATCH", command.sourceRevision(), 5, MarketDeliveryPayload.hash( command))));
        assertThat(coordinator.execute( claimed(command, 1))).isTrue();
        verify(operationMapper).completeClaim( 11L, "node-a", 1L, "MANUAL", "CONFIRMED", null,
                "提供方拒绝：CONTRACT_MISMATCH");
    }

    @Test
    void staleReceiptIsAcceptedAsADefiniteOutcome() {
        when(grantSourceApi.applySource(any())).thenReturn(
                CommonResult.success(receipt(revokeCommand(), SupplyGrantSourceResultDTO.Outcome.STALE)));
        assertThat(coordinator.execute( claimed(revokeCommand(), 1))).isTrue();
        verify(operationMapper).completeClaim( 11L, "node-a", 1L, "SUCCESS", "SUPERSEDED", "8",
                "原操作已被后续来源版本覆盖");
    }

    @Test
    void unusablePayloadIsNeverRebuiltFromCurrentState() {
        MarketDeliveryOperationDO broken = claimed(revokeCommand(), 1);
        broken.setPayloadJson("not-json");
        assertThat(coordinator.execute( broken)).isTrue();
        verify(grantSourceApi, never()).applySource(any());
        verify(grantSourceApi, never()).querySourceOperation(anyString(), anyString());
        verify(operationMapper).completeClaim( 11L, "node-a", 1L, "MANUAL", "REVIEW_REQUIRED", null,
                "冻结载荷不可用，需人工复核");
    }

    @Test
    void takenOverOperationCannotBeCompletedByTheOldOwner() {
        when(operationMapper.completeClaim( anyLong(), anyString(), anyLong(), anyString(),
                anyString(), any(), any())).thenReturn(0);
        when(grantSourceApi.applySource(any())).thenReturn(
                CommonResult.success(receipt(revokeCommand(), SupplyGrantSourceResultDTO.Outcome.APPLIED)));
        assertThat(coordinator.execute( claimed(revokeCommand(), 1))).isFalse();
    }

    @Test
    void drainDueSkipsOperationsClaimedByAnotherNode() {
        MarketDeliveryOperationDO first = claimed(revokeCommand(), 1);
        MarketDeliveryOperationDO second = MarketDeliveryOperationDO.builder().id(12L).build();
        when(operationMapper.selectDueV2()).thenReturn(List.of(first, second));
        when(operationMapper.claim( 11L, "node-a")).thenReturn(first);
        when(operationMapper.claim( 12L, "node-a")).thenReturn(null);
        when(grantSourceApi.applySource(any())).thenReturn(
                CommonResult.success(receipt(revokeCommand(), SupplyGrantSourceResultDTO.Outcome.APPLIED)));
        assertThat(coordinator.drainDue( "node-a")).isEqualTo(1);
        verify(grantSourceApi).applySource(any());
        verify(operationMapper, never()).completeClaim( eq(12L), anyString(), anyLong(),
                anyString(), anyString(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"claim", "complete"})
    void drainDueProcessesLaterRowsBeforeReportingAnItemFailure(String stage) {
        var first = claimed(revokeCommand(), 1);
        var second = claimed(grantCommand(), 1);
        second.setId(12L);
        when(operationMapper.selectDueV2()).thenReturn(List.of(first, second));
        when(operationMapper.claim( 11L, "node-a")).thenReturn(first);
        when(operationMapper.claim( 12L, "node-a")).thenReturn(second);
        when(grantSourceApi.applySource(any())).thenAnswer(invocation -> CommonResult.success(
                receipt(invocation.getArgument(0), SupplyGrantSourceResultDTO.Outcome.APPLIED)));
        if ("claim".equals(stage)) {
            when(operationMapper.claim( 11L, "node-a"))
                    .thenThrow(new IllegalStateException("sensitive-db-payload"));
        } else {
            when(operationMapper.completeClaim( eq(11L), anyString(), anyLong(), anyString(),
                    anyString(), any(), any())).thenThrow(new IllegalStateException("sensitive-db-payload"));
        }
        assertThatThrownBy(() -> coordinator.drainDue( "node-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("交付重试部分失败：已回写 1 条，失败 1 条").hasNoCause();
        verify(operationMapper).completeClaim( 12L, "node-a", 1L, "SUCCESS", "CONFIRMED", "8", null);
        verify(grantSourceApi).applySource(grantCommand());
    }

    @Test
    void drainDueDoesNotCountAResultDiscardedAfterLeaseTakeover() {
        var row = claimed(revokeCommand(), 1);
        when(operationMapper.selectDueV2()).thenReturn(List.of(row));
        when(operationMapper.claim( 11L, "node-a")).thenReturn(row);
        when(grantSourceApi.applySource(any())).thenReturn(
                CommonResult.success(receipt(revokeCommand(), SupplyGrantSourceResultDTO.Outcome.APPLIED)));
        when(operationMapper.completeClaim( anyLong(), anyString(), anyLong(), anyString(),
                anyString(), any(), any())).thenReturn(0);
        assertThat(coordinator.drainDue( "node-a")).isZero();
    }

    @Test
    void drainDueDoesNotSwallowFatalErrors() {
        var row = claimed(revokeCommand(), 1);
        when(operationMapper.selectDueV2()).thenReturn(List.of(row));
        var fatal = new AssertionError("fatal-test");
        when(operationMapper.claim( 11L, "node-a")).thenThrow(fatal);
        assertThatThrownBy(() -> coordinator.drainDue( "node-a")).isSameAs(fatal);
    }

    @Test
    void claimAndCompletionEachUseAnIndependentShortTransaction() {
        MarketDeliveryOperationDO row = claimed(revokeCommand(), 1);
        when(operationMapper.claim( 11L, "node-a")).thenReturn(row);
        assertThat(coordinator.claim( 11L, "node-a")).contains(row);
        assertThat(coordinator.complete( row, "SUCCESS", "CONFIRMED", "8", null)).isTrue();
        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).allSatisfy(definition -> {
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(definition.getTimeout()).isEqualTo(10);
        });
        verify(transactionManager, times(2)).commit(any());
    }

    @Test
    void mismatchedFrozenCommandIsNeverSent() {
        MarketDeliveryOperationDO corrupted = claimed(revokeCommand(), 1);
        corrupted.setPayloadJson(MarketDeliveryPayload.toJson(grantCommand()));
        assertThat(coordinator.execute( corrupted)).isTrue();
        verify(grantSourceApi, never()).applySource(any());
        verify(operationMapper).completeClaim( 11L, "node-a", 1L, "MANUAL", "REVIEW_REQUIRED", null,
                "冻结载荷与交付记录不一致，需人工复核");
    }

    @Test
    void alteredFrozenHashIsNeverSent() {
        MarketDeliveryOperationDO corrupted = claimed(revokeCommand(), 1);
        corrupted.setPayloadHash("wrong-hash");
        assertThat(coordinator.execute( corrupted)).isTrue();
        verify(grantSourceApi, never()).applySource(any());
        verify(operationMapper).completeClaim( 11L, "node-a", 1L, "MANUAL", "REVIEW_REQUIRED", null,
                "冻结载荷与交付记录不一致，需人工复核");
    }

    @Test
    void mismatchedReceiptCannotConfirmDelivery() {
        SupplyGrantSourceCommandDTO command = revokeCommand();
        when(grantSourceApi.applySource(any())).thenReturn(CommonResult.success(
                new SupplyGrantSourceResultDTO("another-operation", SupplyGrantSourceResultDTO.Outcome.APPLIED,
                        null, command.sourceRevision(), 8, MarketDeliveryPayload.hash( command))));
        assertThat(coordinator.execute( claimed(command, 1))).isTrue();
        verify(operationMapper).completeClaim( 11L, "node-a", 1L, "MANUAL", "REVIEW_REQUIRED", null,
                "提供方回执与交付操作不一致，需人工复核");
    }

    @Test
    void mismatchedNotFoundReceiptCannotTriggerResend() {
        SupplyGrantSourceCommandDTO command = revokeCommand();
        when(grantSourceApi.querySourceOperation(anyString(), anyString())).thenReturn(CommonResult.success(
                new SupplyGrantSourceResultDTO("another-operation", SupplyGrantSourceResultDTO.Outcome.NOT_FOUND,
                        null, 0, 0, null)));
        assertThat(coordinator.execute( claimed(command, 2))).isTrue();
        verify(grantSourceApi, never()).applySource(any());
        verify(operationMapper).completeClaim( 11L, "node-a", 1L, "MANUAL", "REVIEW_REQUIRED", null,
                "提供方回执与交付操作不一致，需人工复核");
    }

    @Test
    void mismatchedReceiptHashCannotConfirmDelivery() {
        SupplyGrantSourceCommandDTO command = revokeCommand();
        when(grantSourceApi.querySourceOperation(anyString(), anyString())).thenReturn(CommonResult.success(
                new SupplyGrantSourceResultDTO(command.operationId(), SupplyGrantSourceResultDTO.Outcome.APPLIED,
                        null, command.sourceRevision(), 8, "wrong-hash")));
        assertThat(coordinator.execute( claimed(command, 2))).isTrue();
        verify(grantSourceApi, never()).applySource(any());
        verify(operationMapper).completeClaim( 11L, "node-a", 1L, "MANUAL", "REVIEW_REQUIRED", null,
                "提供方回执与交付操作不一致，需人工复核");
    }

    @Test
    void mismatchedReceiptSourceRevisionCannotConfirmDelivery() {
        SupplyGrantSourceCommandDTO command = revokeCommand();
        when(grantSourceApi.applySource(any())).thenReturn(CommonResult.success(
                new SupplyGrantSourceResultDTO(command.operationId(), SupplyGrantSourceResultDTO.Outcome.APPLIED,
                        null, command.sourceRevision() + 1, 8, MarketDeliveryPayload.hash( command))));
        assertThat(coordinator.execute( claimed(command, 1))).isTrue();
        verify(operationMapper).completeClaim( 11L, "node-a", 1L, "MANUAL", "REVIEW_REQUIRED", null,
                "提供方回执与交付操作不一致，需人工复核");
    }

    @Test
    void manualRecheckConfirmsOnlyTheOriginalAppliedReceiptAndPersistsAudit() {
        SupplyGrantSourceCommandDTO command = revokeCommand();
        MarketDeliveryOperationDO row = manualUnknown(command);
        when(operationMapper.selectManualUnknown( 11L)).thenReturn(row);
        when(grantSourceApi.querySourceOperation(command.sourceSystem(), command.operationId())).thenReturn(
                CommonResult.success(receipt(command, SupplyGrantSourceResultDTO.Outcome.APPLIED)));

        assertThat(coordinator.reconcileManualUnknown( row, 91L, 7L, "核对原回执")).isTrue();
        verify(grantSourceApi, never()).applySource(any());
        verify(operationMapper).reconcileManualUnknown( row, "SUCCESS", "CONFIRMED", "8", null);
        ArgumentCaptor<MarketAuditEventDO> audit = ArgumentCaptor.forClass(MarketAuditEventDO.class);
        verify(auditEventMapper).insertDeliveryReview( audit.capture());
        assertThat(audit.getValue().getActorUserId()).isEqualTo(91L);
        assertThat(audit.getValue().getObjectId()).isEqualTo(11L);
        assertThat(audit.getValue().getReason()).isEqualTo("核对原回执");
    }

    @Test
    void manualRecheckNotFoundKeepsUnknownAndNeverResends() {
        SupplyGrantSourceCommandDTO command = revokeCommand();
        MarketDeliveryOperationDO row = manualUnknown(command);
        when(operationMapper.selectManualUnknown( 11L)).thenReturn(row);
        when(grantSourceApi.querySourceOperation(command.sourceSystem(), command.operationId())).thenReturn(
                CommonResult.success(receipt(command, SupplyGrantSourceResultDTO.Outcome.NOT_FOUND)));

        assertThat(coordinator.reconcileManualUnknown( row, 91L, null, "仍需核查")).isTrue();
        verify(grantSourceApi, never()).applySource(any());
        verify(operationMapper).reconcileManualUnknown( row, "MANUAL", "UNKNOWN", null,
                "提供方未记录原操作；未自动重发");
    }

    @Test
    void manualRecheckTransportFailureKeepsUnknownAndNeverResends() {
        SupplyGrantSourceCommandDTO command = revokeCommand();
        MarketDeliveryOperationDO row = manualUnknown(command);
        when(operationMapper.selectManualUnknown( 11L)).thenReturn(row);
        when(grantSourceApi.querySourceOperation(command.sourceSystem(), command.operationId()))
                .thenThrow(new IllegalStateException("timeout"));

        assertThat(coordinator.reconcileManualUnknown( row, 91L, null, "重查超时")).isTrue();
        verify(grantSourceApi, never()).applySource(any());
        verify(operationMapper).reconcileManualUnknown( row, "MANUAL", "UNKNOWN", null,
                "提供方结果仍未确认；未重发原操作");
    }

    @Test
    void manualRecheckProviderRejectionIsConfirmedButNotSuccessful() {
        SupplyGrantSourceCommandDTO command = grantCommand();
        MarketDeliveryOperationDO row = manualUnknown(command);
        when(operationMapper.selectManualUnknown( 11L)).thenReturn(row);
        when(grantSourceApi.querySourceOperation(command.sourceSystem(), command.operationId())).thenReturn(
                CommonResult.success(new SupplyGrantSourceResultDTO(command.operationId(),
                        SupplyGrantSourceResultDTO.Outcome.REJECTED, "CONTRACT_MISMATCH",
                        command.sourceRevision(), 8, row.getPayloadHash())));

        assertThat(coordinator.reconcileManualUnknown( row, 91L, null, "核查拒绝")).isTrue();
        verify(grantSourceApi, never()).applySource(any());
        verify(operationMapper).reconcileManualUnknown( row, "MANUAL", "CONFIRMED", null,
                "提供方拒绝：CONTRACT_MISMATCH");
    }

    @Test
    void manualRecheckStaleDoesNotClaimCurrentAuthorization() {
        SupplyGrantSourceCommandDTO command = grantCommand();
        MarketDeliveryOperationDO row = manualUnknown(command);
        when(operationMapper.selectManualUnknown( 11L)).thenReturn(row);
        when(grantSourceApi.querySourceOperation(command.sourceSystem(), command.operationId())).thenReturn(
                CommonResult.success(receipt(command, SupplyGrantSourceResultDTO.Outcome.STALE)));

        assertThat(coordinator.reconcileManualUnknown( row, 91L, null, "核对覆盖版本")).isTrue();
        verify(operationMapper).reconcileManualUnknown( row, "SUCCESS", "SUPERSEDED", "8",
                "原操作已被后续来源版本覆盖");
    }

    @Test
    void manualRecheckRejectsMismatchedReceiptAndDoesNotResend() {
        SupplyGrantSourceCommandDTO command = revokeCommand();
        MarketDeliveryOperationDO row = manualUnknown(command);
        when(operationMapper.selectManualUnknown( 11L)).thenReturn(row);
        when(grantSourceApi.querySourceOperation(command.sourceSystem(), command.operationId())).thenReturn(
                CommonResult.success(new SupplyGrantSourceResultDTO("wrong-id",
                        SupplyGrantSourceResultDTO.Outcome.NOT_FOUND, null, 0, 0, null)));

        assertThat(coordinator.reconcileManualUnknown( row, 91L, null, "核对错配回执")).isTrue();
        verify(grantSourceApi, never()).applySource(any());
        verify(operationMapper).reconcileManualUnknown( row, "MANUAL", "REVIEW_REQUIRED", null,
                "提供方回执与交付操作不一致，需人工复核");
    }

    @Test
    void manualRecheckLostCasCannotPersistAnAuditOrAdvertiseSuccess() {
        SupplyGrantSourceCommandDTO command = revokeCommand();
        MarketDeliveryOperationDO row = manualUnknown(command);
        when(operationMapper.selectManualUnknown( 11L)).thenReturn(row);
        when(grantSourceApi.querySourceOperation(command.sourceSystem(), command.operationId())).thenReturn(
                CommonResult.success(receipt(command, SupplyGrantSourceResultDTO.Outcome.APPLIED)));
        when(operationMapper.reconcileManualUnknown( any(), anyString(), anyString(), any(), any()))
                .thenReturn(0);

        assertThat(coordinator.reconcileManualUnknown( row, 91L, null, "竞态核对")).isFalse();
        verify(auditEventMapper, never()).insertDeliveryReview( any(MarketAuditEventDO.class));
        verify(grantSourceApi, never()).applySource(any());
    }

    @Test
    void manualRecheckMissingFrozenCommandStaysForReviewWithoutRemoteCall() {
        MarketDeliveryOperationDO row = manualUnknown(revokeCommand());
        row.setPayloadJson("{}");
        when(operationMapper.selectManualUnknown( 11L)).thenReturn(row);

        assertThat(coordinator.reconcileManualUnknown( row, 91L, null, "冻结命令缺失")).isTrue();
        verify(grantSourceApi, never()).querySourceOperation(anyString(), anyString());
        verify(operationMapper).reconcileManualUnknown( row, "MANUAL", "REVIEW_REQUIRED", null,
                "冻结载荷与交付记录不一致，需人工复核");
    }

    @Test
    void manualRecheckAuditFailureRollsBackTheResult() {
        SupplyGrantSourceCommandDTO command = revokeCommand();
        MarketDeliveryOperationDO row = manualUnknown(command);
        when(operationMapper.selectManualUnknown( 11L)).thenReturn(row);
        when(grantSourceApi.querySourceOperation(command.sourceSystem(), command.operationId())).thenReturn(
                CommonResult.success(receipt(command, SupplyGrantSourceResultDTO.Outcome.APPLIED)));
        when(auditEventMapper.insertDeliveryReview( any(MarketAuditEventDO.class))).thenReturn(0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> coordinator.reconcileManualUnknown( row, 91L, null, "强制审计"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("审计未持久化");
        verify(transactionManager).rollback(any());
    }
}
