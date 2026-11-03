package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDeliveryActionTest {
    private final MarketDeliveryService service = new MarketDeliveryService();

    @Test
    void confirmedSuspendIsNotATerminalRevocation() {
        var row = operation(Action.SUSPEND);
        assertThat(row.getOpType()).isEqualTo("REVOKE"); // 不能重写历史存储协议。
        assertThat(MarketDeliveryService.deliveryResolution(row)).isEqualTo("SUSPENDED");
    }

    @Test
    void confirmedRevokeRetainsItsTerminalPresentation() {
        assertThat(MarketDeliveryService.deliveryResolution(operation(Action.REVOKE))).isEqualTo("REVOKED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingPayload", "invalidPayload", "missingHash", "wrongHash", "legacy",
            "missingProtocol", "app", "api", "apply", "revision", "operationId"})
    void successFlagCannotProveAnUnverifiableWithdrawalAction(String mutation) {
        var row = operation(Action.SUSPEND);
        switch (mutation) {
            case "missingPayload" -> row.setPayloadJson(null);
            case "invalidPayload" -> row.setPayloadJson("{invalid");
            case "missingHash" -> row.setPayloadHash(null);
            case "wrongHash" -> row.setPayloadHash("0".repeat(64));
            case "legacy" -> row.setProtocolVersion(1);
            case "missingProtocol" -> row.setProtocolVersion(null);
            case "app" -> row.setAppId(99L);
            case "api" -> row.setApiId(99L);
            case "apply" -> row.setApplyId(99L);
            case "revision" -> row.setSourceRevision(99L);
            case "operationId" -> row.setOperationId("other-operation");
            default -> throw new AssertionError(mutation);
        }
        assertThat(MarketDeliveryService.deliveryResolution(row)).isEqualTo("UNKNOWN");
        assertThat(MarketDeliveryPayload.operationAction(row)).isEqualTo("UNKNOWN");
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void readableActionIsTheFrozenRequestNotTheTechnicalType(Action action) {
        var row = operation(action);
        row.setStatus("PENDING");
        row.setResolutionStatus("UNCONFIRMED");
        assertThat(MarketDeliveryPayload.operationAction(row)).isEqualTo(action.name());
        assertThat(MarketDeliveryService.deliveryResolution(row)).isEqualTo("PENDING");
        assertThat(row.getStatus()).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @CsvSource({"PENDING,UNCONFIRMED,PENDING", "RUNNING,UNCONFIRMED,PENDING", "RETRY,UNCONFIRMED,PENDING",
            "SUCCESS,UNCONFIRMED,UNKNOWN", "MANUAL,UNKNOWN,UNKNOWN", "MANUAL,CONFIRMED,REJECTED",
            "CANCELLED,CONFIRMED,UNKNOWN", "SUCCESS,SUPERSEDED,SUPERSEDED"})
    void knownSuspendIntentMustNotPromoteAnUnconfirmedOrSupersededResult(String status, String resolution,
                                                                       String expected) {
        var row = operation(Action.SUSPEND);
        row.setStatus(status);
        row.setResolutionStatus(resolution);
        assertThat(MarketDeliveryPayload.operationAction(row)).isEqualTo("SUSPEND");
        assertThat(MarketDeliveryService.deliveryResolution(row)).isEqualTo(expected);
    }

    @Test
    void mismatchedGrantPayloadCannotMasqueradeAsAWithdrawal() {
        var row = operation(Action.GRANT);
        row.setOpType("REVOKE");
        assertThat(MarketDeliveryPayload.operationAction(row)).isEqualTo("UNKNOWN");
        assertThat(MarketDeliveryService.deliveryResolution(row)).isEqualTo("UNKNOWN");
        assertThat(MarketDeliveryPayload.operationAction(null)).isEqualTo("UNKNOWN");
    }

    private MarketDeliveryOperationDO operation(Action action) {
        return MarketDeliveryConfirmationFixture.operation(service, MarketDeliveryConfirmationFixture.apply(), action);
    }
}
