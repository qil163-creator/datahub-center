package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetPublicationReadinessTest {
    AssetCatalogServiceImpl service; DataAssetMapper assets; AssetSupplyStopMapper stop; AssetCatalogVersionMapper versions;
    AssetCatalogLinkService links; DataAssetDO asset;
    @BeforeEach void setup() {
        TenantContextHolder.clear();
        assets=mock(DataAssetMapper.class); stop=mock(AssetSupplyStopMapper.class); versions=mock(AssetCatalogVersionMapper.class); links=mock(AssetCatalogLinkService.class);
        service=spy(new AssetCatalogServiceImpl());
        ReflectionTestUtils.setField(service,"assetMapper",assets); ReflectionTestUtils.setField(service,"stopMapper",stop);
        ReflectionTestUtils.setField(service,"versionMapper",versions); ReflectionTestUtils.setField(service,"catalogLinkService",links);
        asset=DataAssetDO.builder().id(9L).status(DataAssetDO.STATUS_PUBLISHED).assetType(DataAssetDO.TYPE_DATABASE_TABLE).catalogRevision(4L).build();
        when(assets.selectById(9L)).thenReturn(asset); doNothing().when(service).assertCanMaintain(asset);
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }
    @Test void legacyEvidenceAssessmentExplainsBlockedStepsWithoutChangingHistory() {
        when(stop.openApplications(9)).thenReturn(3L); when(stop.pending(9)).thenReturn(2L); when(stop.unclosedSources(9)).thenReturn(1L);
        var result=service.publicationReadiness(9L);
        assertThat(result.isPublishedEvidenceComplete()).isFalse(); assertThat(result.getCatalogRevision()).isEqualTo(4);
        assertThat(result.getMissingItems()).contains("完整的已审核目录发布快照","资产分类","已发布元数据快照与版本");
        assertThat(result.getOpenApplications()).isEqualTo(3); assertThat(result.getSteps().getFirst()).contains("逐项核对");
        assertThat(asset.getStatus()).isEqualTo(DataAssetDO.STATUS_PUBLISHED); assertThat(asset.getPublishedCatalogVersionId()).isNull();
        verify(assets).selectById(9L); verifyNoMoreInteractions(assets); verifyNoInteractions(versions);
    }
    @Test void forbiddenObjectDoesNotRevealApplicationCounts() {
        doThrow(new IllegalStateException("forbidden")).when(service).assertCanMaintain(asset);
        assertThatThrownBy(() -> service.publicationReadiness(9L)).hasMessage("forbidden");
        verifyNoInteractions(stop,versions,links);
    }
    @Test void completeEvidenceDoesNotSkipPendingRevocationChecks() {
        doReturn(true).when(service).hasCompletePublishedSnapshot(asset);
        asset.setCategoryId(5L); asset.setShareAttr(DataAssetDO.SHARE_UNCONDITIONAL);
        asset.setDatasourceId(6L); asset.setMetaTableId(7L); asset.setSchemaName("public"); asset.setTableName("citizen");
        asset.setPublishedSnapshotId(8L); asset.setPublishedVersion("v1");
        when(stop.pending(9)).thenReturn(1L);
        var result=service.publicationReadiness(9L);
        assertThat(result.isPublishedEvidenceComplete()).isTrue(); assertThat(result.getMissingItems()).isEmpty();
        assertThat(result.getSteps().getFirst()).contains("撤权回执");
    }
}
