package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetColumnUpdateReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnUpdateReqDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.META_BIZ_CONCURRENT_CHANGED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 资产维护只通过元数据 API，并严格限制在授权资产的冻结字段身份内。 */
class DataAssetColumnMaintenanceTest extends BaseMockitoUnitTest {
    @InjectMocks private DataAssetServiceImpl service;
    @Mock private DataAssetMapper assetMapper;
    @Mock private AssetCatalogService catalogService;
    @Mock private MetadataApi metadataApi;

    @Test
    void readsCurrentRevisionAndRemarkOnlyAfterAssetAndFrozenIdentityValidation() {
        var asset = scope();
        var current = column(100L, 10L, "customer_name");
        current.setRevision(9);
        current.setRemark("原备注");
        current.setStandardElementId(7);
        current.setStandardElementName("固定发布名称");
        when(metadataApi.getColumn(100L)).thenReturn(CommonResult.success(current));

        var result = service.getAssetColumn(1L, 100L);

        assertEquals(9, result.getRevision());
        assertEquals("原备注", result.getRemark());
        assertEquals("固定发布名称", result.getStandardElementName());
        var order = inOrder(catalogService, metadataApi);
        order.verify(catalogService).assertCanMaintain(asset);
        order.verify(catalogService).assertSingleTableCompatible(asset);
        order.verify(catalogService).listColumns(1L, null);
        order.verify(metadataApi).getColumn(100L);
    }

    @Test
    void guardRejectsBothReadAndWriteBeforeAnyMetadataRpc() {
        var asset = asset();
        when(assetMapper.selectById(1L)).thenReturn(asset);
        doThrow(new ServiceException(DATA_ASSET_FORBIDDEN)).when(catalogService).assertCanMaintain(asset);
        assertEquals(DATA_ASSET_FORBIDDEN.getCode(), assertThrows(ServiceException.class,
                () -> service.getAssetColumn(1L, 100L)).getCode());
        assertEquals(DATA_ASSET_FORBIDDEN.getCode(), assertThrows(ServiceException.class,
                () -> service.updateAssetColumn(request(3))).getCode());
        verifyNoInteractions(metadataApi);
    }

    @Test
    void fieldOfAnotherAssetAndLegacySnapshotWithoutIdentityAreReadOnly() {
        when(assetMapper.selectById(1L)).thenReturn(asset());
        when(catalogService.listColumns(1L, null)).thenReturn(List.of(column(200L, 10L, "customer_name")));
        assertThrows(ServiceException.class, () -> service.getAssetColumn(1L, 100L));
        when(catalogService.listColumns(1L, null)).thenReturn(List.of(column(null, 10L, "customer_name")));
        assertThrows(ServiceException.class, () -> service.updateAssetColumn(request(3)));
        verifyNoInteractions(metadataApi);
    }

    @Test
    void currentFieldCannotMoveToAnotherTableOrReplaceFrozenName() {
        scope();
        when(metadataApi.getColumn(100L)).thenReturn(CommonResult.success(column(100L, 20L, "customer_name")));
        assertEquals(ASSET_QUALITY_COLUMN_INVALID.getCode(), assertThrows(ServiceException.class,
                () -> service.getAssetColumn(1L, 100L)).getCode());
        when(metadataApi.getColumn(100L)).thenReturn(CommonResult.success(column(100L, 10L, "replacement")));
        assertThrows(ServiceException.class, () -> service.getAssetColumn(1L, 100L));
        when(metadataApi.getColumn(100L)).thenReturn(CommonResult.success(column(200L, 10L, "customer_name")));
        assertThrows(ServiceException.class, () -> service.getAssetColumn(1L, 100L));
        verify(metadataApi, never()).updateColumnBiz(any());
    }

    @Test
    void deletedCurrentFieldCannotBeMaintainedFromHistoricalSnapshot() {
        scope();
        var deleted = column(100L, 10L, "customer_name");
        deleted.setObjectStatus(3);
        when(metadataApi.getColumn(100L)).thenReturn(CommonResult.success(deleted));
        assertThrows(ServiceException.class, () -> service.updateAssetColumn(request(3)));
        verify(metadataApi, never()).updateColumnBiz(any());
    }

    @Test
    void sameNameRecreatedColumnIsNeverResolvedThroughOldFrozenIdentity() {
        scope();
        var old = column(100L, 10L, "customer_name");
        old.setObjectStatus(3);
        when(metadataApi.getColumn(100L)).thenReturn(CommonResult.success(old));
        assertThrows(ServiceException.class, () -> service.getAssetColumn(1L, 100L));
        assertThrows(ServiceException.class, () -> service.updateAssetColumn(request(3)));
        assertThrows(ServiceException.class, () -> service.getAssetColumn(1L, 200L));
        var recreated = request(1);
        recreated.setId(200L);
        assertThrows(ServiceException.class, () -> service.updateAssetColumn(recreated));
        verify(metadataApi, never()).getColumn(200L);
        verify(metadataApi, never()).updateColumnBiz(any());
    }

    @Test
    void forwardsOriginalRevisionAndPropagatesProviderCasFailureWithoutRetryOrRefresh() {
        scope();
        var current = column(100L, 10L, "customer_name");
        current.setRevision(8);
        when(metadataApi.getColumn(100L)).thenReturn(CommonResult.success(current));
        when(metadataApi.updateColumnBiz(any())).thenReturn(CommonResult.error(META_BIZ_CONCURRENT_CHANGED));
        var request = request(7);
        request.setStandardElementId(6);
        request.setRemark("提交原备注");

        assertEquals(META_BIZ_CONCURRENT_CHANGED.getCode(), assertThrows(ServiceException.class,
                () -> service.updateAssetColumn(request)).getCode());

        var submitted = ArgumentCaptor.forClass(MetadataColumnUpdateReqDTO.class);
        verify(metadataApi, times(1)).updateColumnBiz(submitted.capture());
        assertEquals(7, submitted.getValue().getRevision());
        assertEquals(6, submitted.getValue().getStandardElementId());
        assertEquals("提交原备注", submitted.getValue().getRemark());
    }

    private DataAssetDO scope() {
        var asset = asset();
        when(assetMapper.selectById(1L)).thenReturn(asset);
        when(catalogService.listColumns(1L, null)).thenReturn(List.of(column(100L, 10L, "customer_name")));
        return asset;
    }

    private DataAssetDO asset() {
        return DataAssetDO.builder().id(1L).metaTableId(10L).publishedSnapshotId(55L).build();
    }

    private MetadataColumnRespDTO column(Long id, Long tableId, String name) {
        var column = new MetadataColumnRespDTO();
        column.setId(id); column.setTableId(tableId); column.setColumnName(name); column.setObjectStatus(1);
        return column;
    }

    private DataAssetColumnUpdateReqVO request(int revision) {
        var request = new DataAssetColumnUpdateReqVO();
        request.setAssetId(1L); request.setId(100L); request.setRevision(revision);
        return request;
    }
}
