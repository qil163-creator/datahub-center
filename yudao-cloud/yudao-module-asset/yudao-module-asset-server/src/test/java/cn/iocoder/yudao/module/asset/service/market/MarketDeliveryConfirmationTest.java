package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceRow;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketDeliveryConfirmationTest {
    private final MarketDeliveryService service = new MarketDeliveryService();
    private final MarketDeliveryOperationMapper operations = mock(MarketDeliveryOperationMapper.class);
    private final MarketApprovalEvidenceMapper approvals = mock(MarketApprovalEvidenceMapper.class);
    private MarketResourceApplyDO apply;
    private MarketDeliveryOperationDO row;

    @BeforeEach void setup() {
        TenantContextHolder.clear();
        ReflectionTestUtils.setField(service, "operationMapper", operations);
        ReflectionTestUtils.setField(service, "approvalEvidenceMapper", approvals);
        apply = MarketDeliveryConfirmationFixture.apply();
        row = MarketDeliveryConfirmationFixture.operation(service, apply);
        when(operations.selectLatestByApply( 7L)).thenReturn(row);
    }

    @AfterEach void cleanup() { TenantContextHolder.clear(); }

    @Test void intactCurrentGrantIsConfirmed() {
        assertTrue(service.isConfirmedApply(apply));
        verify(operations).selectLatestByApply( 7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"revision", "sourceRevision", "app", "api", "clearApi", "clearBinding", "sourceRef",
            "owner", "columns", "database", "schema", "table", "contract", "apiVersion", "purpose", "duty",
            "start", "end", "asset", "missingScope", "columnCase", "missingSourceRef"})
    void changedApplicationCannotBorrowOldConfirmation(String field) {
        switch (field) {
            case "revision" -> apply.setRevision(2);
            case "sourceRevision" -> apply.setSourceRevision(3L);
            case "app" -> apply.setAppId(4L);
            case "api" -> apply.setApiId(4L);
            case "clearApi" -> apply.setApiId(null);
            case "clearBinding" -> { apply.setApiId(null); apply.setAppId(null); }
            case "sourceRef" -> apply.setSourceGrantId("different");
            case "owner" -> apply.setApplyUserId(12L);
            case "columns" -> apply.setSnapshot(apply.getSnapshot().replace("[\"id\"]", "[\"secret\"]"));
            case "database" -> apply.setSnapshot(apply.getSnapshot().replace("\"20\"", "\"21\""));
            case "schema" -> apply.setSnapshot(apply.getSnapshot().replace("public", "other"));
            case "table" -> apply.setSnapshot(apply.getSnapshot().replace("t_person", "other_table"));
            case "contract" -> apply.setSnapshot(apply.getSnapshot().replace("test-contract", "other-contract"));
            case "apiVersion" -> apply.setSnapshot(apply.getSnapshot().replace("v1", "v2"));
            case "purpose" -> apply.setPurpose("different purpose");
            case "duty" -> apply.setDutyMatter("different duty");
            case "end" -> apply.setApplyEndTime(LocalDateTime.of(2040, 1, 1, 0, 0));
            case "start" -> apply.setApplyStartTime(LocalDateTime.of(2020, 1, 1, 0, 0));
            case "asset" -> apply.setAssetId(99L);
            case "missingScope" -> apply.setSnapshot("{}");
            case "columnCase" -> apply.setSnapshot(apply.getSnapshot().replace("[\"id\"]", "[\"ID\"]"));
            case "missingSourceRef" -> apply.setSourceGrantId(null);
            default -> throw new AssertionError(field);
        }
        assertFalse(service.isConfirmedApply(apply), field);
    }

    @ParameterizedTest
    @ValueSource(strings = {"apply", "expectedRevision", "sourceRevision", "app", "api", "sourceRef",
            "protocol", "missingPayload", "badPayload", "hash", "operation"})
    void mismatchedOrIncompleteLedgerCannotBeConfirmed(String field) {
        switch (field) {
            case "apply" -> row.setApplyId(8L);
            case "expectedRevision" -> row.setExpectedRevision(2);
            case "sourceRevision" -> row.setSourceRevision(3L);
            case "app" -> row.setAppId(4L);
            case "api" -> row.setApiId(4L);
            case "sourceRef" -> row.setSourceGrantId("different");
            case "protocol" -> row.setProtocolVersion(1);
            case "missingPayload" -> row.setPayloadJson(null);
            case "badPayload" -> row.setPayloadJson("{}");
            case "hash" -> row.setPayloadHash("corrupt");
            case "operation" -> row.setOperationId("different");
            default -> throw new AssertionError(field);
        }
        assertFalse(service.isConfirmedApply(apply), field);
    }

    @Test void missingV2LedgerCannotFallBackToOldSuccessFlag() {
        when(operations.selectLatestByApply( 7L)).thenReturn(null);
        assertFalse(service.isConfirmedApply(apply));
    }

    @Test void clearedVersionCannotHideFormalApproval() {
        when(operations.selectLatestByApply( 7L)).thenReturn(null);
        apply.setSourceRevision(null);
        when(approvals.selectByApply( 7L)).thenReturn(new MarketApprovalEvidenceRow());
        assertFalse(service.isConfirmedApply(apply));
    }

    @Test void clearedApiCannotHideFormalApproval() {
        when(operations.selectLatestByApply( 7L)).thenReturn(null);
        apply.setApiId(null);
        apply.setAppId(null);
        when(approvals.selectByApply( 7L)).thenReturn(new MarketApprovalEvidenceRow());
        assertFalse(service.isConfirmedApply(apply));
    }

    @Test void currentGrantContradictingFormalApprovalIsNotConfirmed() {
        when(approvals.selectByApply( 7L)).thenReturn(new MarketApprovalEvidenceRow());
        assertFalse(service.isConfirmedApply(apply));
    }

    @Test void approvalLookupFailureIsNotConvertedToLegacySuccess() {
        when(operations.selectLatestByApply( 7L)).thenReturn(null);
        apply.setSourceRevision(null);
        when(approvals.selectByApply( 7L)).thenThrow(new IllegalStateException("test lookup unavailable"));
        assertThrows(IllegalStateException.class, () -> service.isConfirmedApply(apply));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 2})
    void onlyZeroSourceVersionCanUseLegacyCompatibility(long version) {
        when(operations.selectLatestByApply( 7L)).thenReturn(null);
        apply.setSourceRevision(version);
        assertEquals(version == 0, service.isConfirmedApply(apply));
    }

    @Test void formalApprovalMatchesAfterSourceVersionAdvances() {
        var proof = new MarketApprovalEvidenceRow();
        proof.setApprovalRef("apply:7:r1");
        proof.setApprovalAssetId(8L);
        proof.setApprovalScopeHash(MarketDeliveryPayload.approvalHash( 8L,
                MarketDeliveryPayload.fromJson(row.getPayloadJson())));
        when(approvals.selectByApply( 7L)).thenReturn(proof);
        apply.setSourceRevision(4L);
        when(operations.selectLatestByApply( 7L)).thenReturn(MarketDeliveryConfirmationFixture.operation(service, apply));
        assertTrue(service.isConfirmedApply(apply));
    }

    @Test void localOnlyPreviewWithNoApiHistoryStillNeedsNoRemoteDelivery() {
        apply.setAppId(null);
        apply.setApiId(null);
        when(operations.selectLatestByApply( 7L)).thenReturn(null);
        assertTrue(service.isConfirmedApply(apply));
    }
}
