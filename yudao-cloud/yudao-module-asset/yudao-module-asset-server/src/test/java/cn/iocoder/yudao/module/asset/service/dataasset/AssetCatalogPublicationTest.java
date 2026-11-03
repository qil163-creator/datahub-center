package cn.iocoder.yudao.module.asset.service.dataasset;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.*;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetCatalogVersionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AssetCatalogPublicationTest extends BaseMockitoUnitTest {
    @Mock AssetCatalogVersionMapper versionMapper;
    @Mock cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper assetMapper;
    @InjectMocks AssetCatalogServiceImpl catalog;
    @Test void marketReadsApprovedNameAndBindingDespiteMutableDraftEdits() {
        var frozen=DataAssetDO.builder().id(7L).assetName("批准名称").datasourceId(3L).tableName("approved_table").publishedSnapshotId(9L).build();
        var version=AssetCatalogVersionDO.builder().id(5L).assetId(7L).status(AssetCatalogVersionDO.STATUS_PUBLISHED)
                .techSnapshotId(9L).contentJson(JsonUtils.toJsonString(Map.of("format",1,"asset",frozen))).build();
        when(versionMapper.selectById(5L)).thenReturn(version);
        var mutable=DataAssetDO.builder().id(7L).publishedCatalogVersionId(5L).assetName("未审核草稿")
                .datasourceId(999L).tableName("draft_table").status(1).build();
        var result=catalog.publishedAsset(mutable);
        assertEquals("批准名称",result.getAssetName());
        assertEquals(3L,result.getDatasourceId());
        assertEquals("approved_table",result.getTableName());
        assertEquals(5L,result.getPublishedCatalogVersionId());
    }
    @Test void legacyPublishedEvidenceRemainsVisibleButCannotAcceptNewApplication() {
        when(versionMapper.selectById(5L)).thenReturn(AssetCatalogVersionDO.builder().id(5L).assetId(7L)
                .status(AssetCatalogVersionDO.STATUS_PUBLISHED).contentJson("{\"assetName\":\"历史名称\"}").build());
        var asset=DataAssetDO.builder().id(7L).publishedCatalogVersionId(5L).status(1).build();
        assertEquals("历史名称",catalog.publishedAsset(asset).getAssetName());
        assertFalse(catalog.hasCompletePublishedSnapshot(asset));
        assertThrows(ServiceException.class,()->catalog.requireCompletePublishedAsset(asset));
        verify(versionMapper,never()).updateById(org.mockito.ArgumentMatchers.any(AssetCatalogVersionDO.class));
    }
    @Test void oldPublishEntryDelegatesToSameApprovalService() {
        var approval=mock(AssetCatalogService.class);
        var old=new DataAssetServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(old,"catalogService",approval);
        old.publishAsset(7L);
        verify(approval).publishLatestApproved(7L);
        verifyNoMoreInteractions(approval);
    }
    @Test void retiredDirectoryCannotResumeEvenAfterSuccessfulUnlink() {
        var service=spy(catalog);
        var asset=DataAssetDO.builder().id(7L).status(0).retirementRequested(true).publishedCatalogVersionId(5L).build();
        when(versionMapper.selectById(5L)).thenReturn(AssetCatalogVersionDO.builder().id(5L).assetId(7L)
                .status(AssetCatalogVersionDO.STATUS_PUBLISHED).build());
        when(assetMapper.selectById(7L)).thenReturn(asset);
        when(assetMapper.selectForCatalogUpdate(7L)).thenReturn(asset);
        doNothing().when(service).assertCanMaintain(asset);
        assertThrows(ServiceException.class,()->service.publishCatalogVersion(5L));
        verify(versionMapper,never()).updateById(org.mockito.ArgumentMatchers.any(AssetCatalogVersionDO.class));
    }
}
