package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetTechLinkSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableRespDTO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetTechLinkDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetTechLinkMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_MULTI_TABLE_AMBIGUOUS;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_TECH_UNAVAILABLE;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_META_NOT_PUBLISHED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AssetCatalogServiceImplTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AssetCatalogServiceImpl service;
    @Mock
    private AssetTechLinkMapper techLinkMapper;
    @Mock private DataAssetMapper assetMapper;
    @Mock private MetadataApi metadataApi;

    @Test
    void assertSingleTableCompatible_unlinkedThrowsUnavailable() {
        DataAssetDO asset = DataAssetDO.builder().id(8L).build();
        when(techLinkMapper.selectListByAssetId(8L)).thenReturn(List.of());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.assertSingleTableCompatible(asset));
        assertEquals(DATA_ASSET_TECH_UNAVAILABLE.getCode(), ex.getCode());
    }

    @Test
    void assertSingleTableCompatible_multiLinkThrowsAmbiguous() {
        DataAssetDO asset = DataAssetDO.builder().id(8L).metaTableId(1L).build();
        when(techLinkMapper.selectListByAssetId(8L)).thenReturn(List.of(
                AssetTechLinkDO.builder().id(1L).metaTableId(1L).build(),
                AssetTechLinkDO.builder().id(2L).metaTableId(2L).build()));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.assertSingleTableCompatible(asset));
        assertEquals(DATA_ASSET_MULTI_TABLE_AMBIGUOUS.getCode(), ex.getCode());
    }

    @Test
    void addTechLinkRejectsUnpublishedTableEvenWhenFinalizedSnapshotRemains() {
        var metadata=metadata(0,21L,"v1");
        var maintained=linkService(metadata);
        var error=assertThrows(ServiceException.class,()->maintained.addTechLink(linkRequest()));
        assertEquals(DATA_ASSET_META_NOT_PUBLISHED.getCode(),error.getCode());
        verify(assetMapper,never()).selectForCatalogUpdate(anyLong());
        verify(techLinkMapper,never()).insert(any(AssetTechLinkDO.class));
    }

    @Test
    void addTechLinkRejectsIncompletePublishedEvidence() {
        var metadata=metadata(1,21L,null);
        var maintained=linkService(metadata);
        assertEquals(DATA_ASSET_META_NOT_PUBLISHED.getCode(),
                assertThrows(ServiceException.class,()->maintained.addTechLink(linkRequest())).getCode());
        metadata.setPublishedVersion("v1"); metadata.setPublishedSnapshotId(null);
        assertEquals(DATA_ASSET_META_NOT_PUBLISHED.getCode(),
                assertThrows(ServiceException.class,()->maintained.addTechLink(linkRequest())).getCode());
        verify(assetMapper,never()).selectForCatalogUpdate(anyLong());
        verify(techLinkMapper,never()).insert(any(AssetTechLinkDO.class));
    }

    @Test
    void addTechLinkAcceptsCompletePublishedEvidenceBeforeEnteringMutation() {
        var metadata=metadata(1,21L,"v1");
        var maintained=linkService(metadata);
        var mutationReached=new IllegalStateException("测试在写入边界停止");
        when(assetMapper.selectForCatalogUpdate(8L)).thenThrow(mutationReached);
        org.junit.jupiter.api.Assertions.assertSame(mutationReached,
                assertThrows(IllegalStateException.class,()->maintained.addTechLink(linkRequest())));
        verify(assetMapper).selectForCatalogUpdate(8L);
    }

    private AssetCatalogServiceImpl linkService(MetadataTableRespDTO metadata) {
        var asset=DataAssetDO.builder().id(8L).build();
        when(assetMapper.selectById(8L)).thenReturn(asset);
        when(metadataApi.getTable(20L)).thenReturn(CommonResult.success(metadata));
        var maintained=spy(service); doNothing().when(maintained).assertCanMaintain(asset); return maintained;
    }
    private AssetTechLinkSaveReqVO linkRequest() {
        var req=new AssetTechLinkSaveReqVO(); req.setAssetId(8L); req.setMetaTableId(20L); return req;
    }
    private MetadataTableRespDTO metadata(int status, Long snapshot, String version) {
        var metadata=new MetadataTableRespDTO(); metadata.setId(20L); metadata.setPublishStatus(status);
        metadata.setPublishedSnapshotId(snapshot); metadata.setPublishedVersion(version); return metadata;
    }

}
