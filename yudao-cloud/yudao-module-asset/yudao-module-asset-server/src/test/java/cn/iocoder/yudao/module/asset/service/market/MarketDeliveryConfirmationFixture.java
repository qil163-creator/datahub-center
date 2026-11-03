package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;

/** 只用于测试：通过真实命令构建器冻结一致的申请与账本，不用不完整 SUCCESS 行冒充 v2 证据。 */
final class MarketDeliveryConfirmationFixture {
    private MarketDeliveryConfirmationFixture() { }

    static MarketResourceApplyDO apply() {
        return MarketResourceApplyDO.builder().id(7L).assetId(8L).apiId(2L).appId(3L).applyUserId(11L)
                .revision(1).sourceRevision(2L).sourceGrantId("apply:7:r1")
                .status(2).ifAuth(1).paused(0).deliveryStatus(MarketDeliveryService.DELIVERY_SUCCESS)
                .snapshot("""
                    {"assetId":"8","databaseId":"20","schemaName":"public","tableName":"t_person",
                     "frozenColumns":["id"],"approvedContractHash":"test-contract","apiVersion":"v1"}
                    """).build();
    }

    static MarketDeliveryOperationDO operation(MarketDeliveryService service, MarketResourceApplyDO apply) {
        return operation(service, apply, SupplyGrantSourceCommandDTO.Action.GRANT);
    }

    static MarketDeliveryOperationDO operation(MarketDeliveryService service, MarketResourceApplyDO apply,
                                              SupplyGrantSourceCommandDTO.Action action) {
        String id = "asset:" + service.sourceKey(apply) + ":r" + apply.getSourceRevision() + ":" + action;
        var command = service.buildCommand(apply, action, id, apply.getSourceRevision());
        return MarketDeliveryOperationDO.builder().id(101L).applyId(apply.getId())
                .operationId(id).protocolVersion(2).sourceGrantId(service.sourceGrantId(apply))
                .expectedRevision(apply.getRevision() == null ? 1 : apply.getRevision())
                .sourceRevision(apply.getSourceRevision()).appId(apply.getAppId()).apiId(apply.getApiId())
                .opType(action == SupplyGrantSourceCommandDTO.Action.GRANT
                        ? MarketDeliveryOperationDO.TYPE_GRANT : MarketDeliveryOperationDO.TYPE_REVOKE)
                .status(MarketDeliveryOperationDO.STATUS_SUCCESS)
                .resolutionStatus(MarketDeliveryCoordinator.RESOLUTION_CONFIRMED)
                .payloadJson(MarketDeliveryPayload.toJson(command)).payloadHash(MarketDeliveryPayload.hash( command)).build();
    }
}
