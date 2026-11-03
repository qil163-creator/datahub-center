package cn.iocoder.yudao.module.asset.service.market;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MarketQuotaPolicyTest {
    @Test void finiteQuotaRequiresMatchingFrozenEvidenceAndPreservesMetric() {
        var apply=MarketResourceApplyDO.builder().id(7L).applyUseType(2).applyUseTimes(10)
                .snapshot("{\"applyUseType\":\"2\",\"applyUseTimes\":\"10\"}").build();
        assertDoesNotThrow(() -> MarketQuotaPolicy.requireFrozen(apply));
        assertEquals(new SupplyGrantSourceCommandDTO.Quota("ROWS",10), MarketQuotaPolicy.quota(2,10));
        assertEquals(new SupplyGrantSourceCommandDTO.Quota("CALLS",3), MarketQuotaPolicy.quota(1,3));
        apply.setApplyUseTimes(11);
        assertThrows(ServiceException.class, () -> MarketQuotaPolicy.requireFrozen(apply));
        apply.setSnapshot("{}");
        assertThrows(ServiceException.class, () -> MarketQuotaPolicy.requireFrozen(apply));
    }
    @Test void rejectsUnknownTypesNegativeCountsAndLegacyLimits() {
        for (int type : new int[]{-1,1,2}) assertThrows(ServiceException.class, () -> MarketQuotaPolicy.validate(type,0));
        for (int times : new int[]{-1,1,100}) assertThrows(ServiceException.class, () -> MarketQuotaPolicy.validate(0,times));
        assertDoesNotThrow(() -> MarketQuotaPolicy.validate(null,null));
        assertDoesNotThrow(() -> MarketQuotaPolicy.validate(0,0));
    }
    @Test void frozenGrantCannotEraseLegacyQuotaButRevokeRemainsAvailable() {
        var apply=MarketResourceApplyDO.builder().id(7L).appId(8L).apiId(9L).revision(1).applyUseType(1).applyUseTimes(10).build();
        var service=new MarketDeliveryService();
        assertThrows(ServiceException.class, () -> service.buildCommand(apply, SupplyGrantSourceCommandDTO.Action.GRANT,"op",1));
        assertEquals(SupplyGrantSourceCommandDTO.Action.REVOKE, service.buildCommand(apply, SupplyGrantSourceCommandDTO.Action.REVOKE,"revoke",2).action());
    }
}
