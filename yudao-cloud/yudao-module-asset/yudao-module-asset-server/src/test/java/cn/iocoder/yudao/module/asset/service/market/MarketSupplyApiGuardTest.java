package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApi;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiBindingRespDTO;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_API_ASSET_MISMATCH;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_API_TYPE_UNSUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

class MarketSupplyApiGuardTest extends BaseMockitoUnitTest {

    @InjectMocks
    private MarketSupplyApiGuard guard;
    @Mock
    private SupplyDataApiApi supplyDataApiApi;

    @Test
    void rejectsSqlScriptApi() {
        SupplyApiBindingRespDTO binding = binding();
        binding.setConfigType("SQL");
        when(supplyDataApiApi.getApiBinding(9L)).thenReturn(CommonResult.success(binding));
        ServiceException ex = assertThrows(ServiceException.class, () -> guard.requireQueryableTableApi(9L));
        assertEquals(MARKET_APPLY_API_TYPE_UNSUPPORTED.getCode(), ex.getCode());
    }

    @Test
    void rejectsTableMismatch() {
        DataAssetDO asset = DataAssetDO.builder()
                .assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .datasourceId(20L)
                .tableName("t_person")
                .build();
        SupplyApiBindingRespDTO binding = binding();
        binding.setTableName("other");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.requireMatchesAsset(asset, binding));
        assertEquals(MARKET_APPLY_API_ASSET_MISMATCH.getCode(), ex.getCode());
    }

    private static SupplyApiBindingRespDTO binding() {
        SupplyApiBindingRespDTO dto = new SupplyApiBindingRespDTO();
        dto.setId(9L);
        dto.setStatus(1);
        dto.setApiType("QUERY");
        dto.setConfigType("TABLE");
        dto.setDatabaseId(20L);
        dto.setTableName("t_person");
        return dto;
    }

}
