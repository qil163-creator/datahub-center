package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetGrantSourceDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketDeliveryServiceTest {

    private final MarketDeliveryService service = new MarketDeliveryService();

    @BeforeEach void prepare() {
        TenantContextHolder.clear();
        ReflectionTestUtils.setField(service, "operationMapper", mock(MarketDeliveryOperationMapper.class));
        ReflectionTestUtils.setField(service, "approvalEvidenceMapper", mock(MarketApprovalEvidenceMapper.class));
    }

    @AfterEach void cleanup() { TenantContextHolder.clear(); }

    @ParameterizedTest
    @CsvSource(value = {
            "GRANT,SUCCESS,CONFIRMED,APPLIED,true",
            "REVOKE,SUCCESS,CONFIRMED,REVOKED,false",
            "GRANT,SUCCESS,UNCONFIRMED,UNKNOWN,false",
            "REVOKE,SUCCESS,UNCONFIRMED,UNKNOWN,false",
            "GRANT,SUCCESS,UNKNOWN,UNKNOWN,false",
            "GRANT,SUCCESS,REVIEW_REQUIRED,UNKNOWN,false",
            "GRANT,SUCCESS,SUPERSEDED,SUPERSEDED,false",
            "GRANT,SUCCESS,NULL,UNKNOWN,false",
            "GRANT,SUCCESS,NEW_RESOLUTION,UNKNOWN,false",
            "NULL,SUCCESS,CONFIRMED,UNKNOWN,false",
            "NEW_ACTION,SUCCESS,CONFIRMED,UNKNOWN,false",
            "GRANT,MANUAL,CONFIRMED,REJECTED,false",
            "REVOKE,MANUAL,CONFIRMED,REJECTED,false",
            "GRANT,MANUAL,UNCONFIRMED,UNKNOWN,false",
            "GRANT,CANCELLED,UNCONFIRMED,UNKNOWN,false",
            "GRANT,CANCELLED,CONFIRMED,UNKNOWN,false",
            "REVOKE,CANCELLED,UNCONFIRMED,UNKNOWN,false",
            "GRANT,CANCELLED,SUPERSEDED,SUPERSEDED,false",
            "GRANT,PENDING,UNCONFIRMED,PENDING,false",
            "GRANT,RUNNING,UNCONFIRMED,PENDING,false",
            "GRANT,RETRY,UNCONFIRMED,PENDING,false",
            "REVOKE,PENDING,UNCONFIRMED,PENDING,false",
            "GRANT,PENDING,CONFIRMED,UNKNOWN,false",
            "GRANT,RETRY,UNKNOWN,UNKNOWN,false",
            "GRANT,NEW_STATUS,UNCONFIRMED,UNKNOWN,false",
            "GRANT,NULL,UNCONFIRMED,UNKNOWN,false"
    }, nullValues = "NULL")
    void deliveryPresentationAndConfirmationShareOneConservativeRule(String type, String status,
            String resolution, String expected, boolean confirmed) {
        var mapper = mock(MarketDeliveryOperationMapper.class);
        ReflectionTestUtils.setField(service, "operationMapper", mapper);
        var apply = MarketDeliveryConfirmationFixture.apply();
        var row = MarketDeliveryConfirmationFixture.operation(service, apply,
                MarketDeliveryOperationDO.TYPE_REVOKE.equals(type)
                        ? cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Action.REVOKE
                        : cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Action.GRANT);
        row.setOpType(type);
        row.setStatus(status);
        row.setResolutionStatus(resolution);
        when(mapper.selectLatestByApply( 7L)).thenReturn(row);
        TenantContextHolder.clear();
        try {
            assertEquals(expected, MarketDeliveryService.deliveryResolution(row));
            assertEquals(confirmed, service.isConfirmedApply(apply));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @ParameterizedTest
    @CsvSource(value = {"0,NULL,true", "1,PENDING,false", "2,PENDING,false", "3,APPLIED,true",
            "4,PENDING,false", "5,UNKNOWN,false", "6,UNKNOWN,false", "99,UNKNOWN,false", "NULL,UNKNOWN,false"},
            nullValues = "NULL")
    void explicitLegacyCompatibilityDoesNotPromoteCancelledOrUnknown(Integer status, String expected,
            boolean confirmed) {
        ReflectionTestUtils.setField(service, "operationMapper", mock(MarketDeliveryOperationMapper.class));
        var apply = MarketResourceApplyDO.builder().id(7L).apiId(2L).appId(3L).deliveryStatus(status).build();
        TenantContextHolder.clear();
        try {
            assertEquals(expected, MarketDeliveryService.legacyDeliveryResolution(status));
            assertEquals(confirmed, service.isConfirmedApply(apply));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @ParameterizedTest
    @CsvSource(value = {"PENDING,1", "RUNNING,2", "SUCCESS,3", "RETRY,4", "MANUAL,5", "CANCELLED,6",
            "NEW_STATUS,5", "NULL,5"}, nullValues = "NULL")
    void technicalStatusComesFromLedgerState(String status, int expected) {
        assertEquals(expected, MarketDeliveryService.technicalDeliveryStatus(
                MarketDeliveryOperationDO.builder().status(status).build()));
    }

    @Test
    void noApiPreviewCompatibilityAndMissingApplicationRemainExplicit() {
        assertFalse(service.isConfirmedApply(null));
        assertTrue(service.isConfirmedApply(MarketResourceApplyDO.builder().id(7L).build()));
        assertFalse(service.isConfirmedApply(MarketResourceApplyDO.builder().apiId(2L).build()));
        assertEquals("UNKNOWN", MarketDeliveryService.deliveryResolution(null));
    }

    @Test
    void sourceGrantIdIncludesApplyAndRevision() {
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setId(88L);
        apply.setRevision(3);
        assertEquals("apply:88:r3", service.sourceGrantId(apply));
    }

    @Test
    void staleGrantDoesNotResurrectAfterRevoke() {
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setStatus(MarketResourceApplyDO.STATUS_REVOKED);
        apply.setIfAuth(MarketResourceApplyDO.AUTH_NO);
        AssetGrantSourceDO source = AssetGrantSourceDO.builder()
                .revoked(1)
                .revision(2L)
                .build();
        MarketDeliveryOperationDO grant = new MarketDeliveryOperationDO();
        grant.setExpectedRevision(1);
        assertTrue(service.shouldSkipStaleGrant(apply, source, grant));
    }

    @Test
    void approvedGrantStillRuns() {
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        apply.setIfAuth(MarketResourceApplyDO.AUTH_YES);
        apply.setPaused(0);
        AssetGrantSourceDO source = AssetGrantSourceDO.builder()
                .revoked(0)
                .revision(1L)
                .build();
        MarketDeliveryOperationDO grant = new MarketDeliveryOperationDO();
        grant.setExpectedRevision(1);
        assertFalse(service.shouldSkipStaleGrant(apply, source, grant));
    }

    @Test
    void enqueueRequiresAnActiveTransactionBeforeWritingAnySourceOrOperation() {
        TenantContextHolder.clear();
        try {
            var failure = assertThrows(IllegalStateException.class,
                    () -> service.enqueueRevoke(new MarketResourceApplyDO()));
            assertEquals("Market delivery enqueue requires an active transaction", failure.getMessage());
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void immediateDeliveryFailureAfterCommitDoesNotTurnCommittedApprovalIntoAnError() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            ReflectionTestUtils.invokeMethod(service, "runAfterCommit", (Runnable) () -> {
                throw new IllegalStateException("transient database outage");
            });
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations()
                    .getFirst().afterCommit());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void confirmedV2ReceiptMakesPendingIntentUsable() {
        var mapper = mock(MarketDeliveryOperationMapper.class);
        ReflectionTestUtils.setField(service, "operationMapper", mapper);
        var apply = MarketDeliveryConfirmationFixture.apply();
        apply.setDeliveryStatus(MarketDeliveryService.DELIVERY_PENDING);
        when(mapper.selectLatestByApply( 7L)).thenReturn(MarketDeliveryConfirmationFixture.operation(service, apply));
        TenantContextHolder.clear();
        try {
            assertTrue(service.isConfirmedApply(apply));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void unconfirmedV2ReceiptCannotBeOverriddenByStaleApplicationStatus() {
        var mapper = mock(MarketDeliveryOperationMapper.class);
        ReflectionTestUtils.setField(service, "operationMapper", mapper);
        var apply = MarketDeliveryConfirmationFixture.apply();
        apply.setDeliveryStatus(MarketDeliveryService.DELIVERY_SUCCESS);
        when(mapper.selectLatestByApply( 7L)).thenReturn(MarketDeliveryOperationDO.builder()
                .status(MarketDeliveryOperationDO.STATUS_MANUAL)
                .resolutionStatus(MarketDeliveryCoordinator.RESOLUTION_UNKNOWN).build());
        TenantContextHolder.clear();
        try {
            assertFalse(service.isConfirmedApply(apply));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void confirmedRevokeIsNotAConfirmedGrant() {
        var mapper = mock(MarketDeliveryOperationMapper.class);
        ReflectionTestUtils.setField(service, "operationMapper", mapper);
        var apply = MarketDeliveryConfirmationFixture.apply();
        apply.setDeliveryStatus(MarketDeliveryService.DELIVERY_SUCCESS);
        when(mapper.selectLatestByApply( 7L)).thenReturn(MarketDeliveryOperationDO.builder()
                .opType(MarketDeliveryOperationDO.TYPE_REVOKE)
                .status(MarketDeliveryOperationDO.STATUS_SUCCESS)
                .resolutionStatus(MarketDeliveryCoordinator.RESOLUTION_CONFIRMED).build());
        TenantContextHolder.clear();
        try {
            assertFalse(service.isConfirmedApply(apply));
        } finally {
            TenantContextHolder.clear();
        }
    }

}
