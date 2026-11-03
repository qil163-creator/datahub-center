package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.categorydataasset.CategoryDataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.service.assettag.AssetTagService;
import cn.iocoder.yudao.module.asset.service.common.AssetUserNameResolver;
import cn.iocoder.yudao.module.asset.service.market.MarketAccessService;
import cn.iocoder.yudao.module.asset.service.quality.AssetQualityTaskService;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetMapReadScopeTest {
    private final DataAssetServiceImpl service = new DataAssetServiceImpl();
    private final DataAssetMapper assets = mock(DataAssetMapper.class);
    private final AssetCatalogService catalog = mock(AssetCatalogService.class);
    private final MarketAccessService access = mock(MarketAccessService.class);
    private final MetadataApi metadata = mock(MetadataApi.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "assetMapper", assets);
        ReflectionTestUtils.setField(service, "catalogService", catalog);
        ReflectionTestUtils.setField(service, "marketAccessService", access);
        ReflectionTestUtils.setField(service, "metadataApi", metadata);
        ReflectionTestUtils.setField(service, "categoryMapper", mock(CategoryDataAssetMapper.class));
        ReflectionTestUtils.setField(service, "tagService", mock(AssetTagService.class));
        ReflectionTestUtils.setField(service, "qualityTaskService", mock(AssetQualityTaskService.class));
        ReflectionTestUtils.setField(service, "userNameResolver", mock(AssetUserNameResolver.class));
        ReflectionTestUtils.setField(service, "supplyStopService", mock(AssetSupplyStopService.class));
        ReflectionTestUtils.setField(service, "etlBatchBindings", mock(AssetEtlBatchBindingService.class));
    }

    @Test
    void queryOnlyRoleStillUsesRestrictedScopeAndCannotReadAnotherPersonsDraft() {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(null);
            when(catalog.prepareMaintenanceAuthorization()).thenReturn(new AssetMaintenanceAuthorization(9L, null, false, false));
            var request = new DataAssetPageReqVO();
            when(assets.selectMapPage(request, null, null, 9L, null)).thenReturn(PageResult.empty());
            assertThat(service.getAssetPage(request).getTotal()).isZero();
            verify(assets).selectMapPage(request, null, null, 9L, null);
            verify(assets, never()).selectPage(any(DataAssetPageReqVO.class), any(), org.mockito.ArgumentMatchers.<java.util.Collection<Long>>any());
            when(assets.selectById(7L)).thenReturn(DataAssetDO.builder().id(7L).status(0).build());
            assertThatThrownBy(() -> service.getAssetDetail(7L, false)).isInstanceOf(ServiceException.class);
            verifyNoInteractions(metadata, access);
        }
    }

    @Test
    void independentGlobalMaintenanceAuthorityPreservesManagementReads() {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(null);
            when(catalog.prepareMaintenanceAuthorization()).thenReturn(new AssetMaintenanceAuthorization(9L, null, false, true));
            var request = new DataAssetPageReqVO();
            when(assets.selectPage(request, null, (java.util.Collection<Long>) null)).thenReturn(PageResult.empty());
            assertThat(service.getAssetPage(request).getTotal()).isZero();
            verify(assets).selectPage(request, null, (java.util.Collection<Long>) null);
            verifyNoInteractions(access);
        }
    }

    @Test
    void ownerAndDepartmentDetailsUseTheSameSimpleMaintenanceFields() {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(4L);
            when(catalog.prepareMaintenanceAuthorization()).thenReturn(new AssetMaintenanceAuthorization(9L, 4L, false, false));
            var names = (AssetUserNameResolver) ReflectionTestUtils.getField(service, "userNameResolver");
            when(names.resolve(any())).thenReturn(new java.util.HashMap<>());
            var created = DataAssetDO.builder().build(); created.setCreator("9");
            for (var current : List.of(DataAssetDO.builder().techLeaderUserId(9L).build(),
                    DataAssetDO.builder().businessLeaderUserId(9L).build(),
                    DataAssetDO.builder().ownerDeptId(4L).build(), DataAssetDO.builder().providerDeptId(4L).build(),
                    created)) {
                current.setId(7L); current.setStatus(0); current.setAssetName("当前目录");
                when(assets.selectById(7L)).thenReturn(current);
                assertThat(service.getAssetDetail(7L, false).getAsset().getAssetName()).isEqualTo("当前目录");
            }
            verify(catalog, never()).publishedAsset(any());
            verifyNoInteractions(metadata, access);
        }
    }

    @Test
    void consumptionGrantDoesNotAdmitAnotherPersonsAssetToTheMap() {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(null);
            when(catalog.prepareMaintenanceAuthorization()).thenReturn(new AssetMaintenanceAuthorization(9L, null, false, false));
            when(access.hasEffectiveAccess(9L, 7L)).thenReturn(true);
            when(assets.selectById(7L)).thenReturn(DataAssetDO.builder().id(7L).status(1).build());
            assertThatThrownBy(() -> service.getAssetDetail(7L, false)).isInstanceOf(ServiceException.class);
            verifyNoInteractions(metadata, access);
        }
    }

    @Test
    void changedPrincipalCannotReuseAnotherReadAuthorization() {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(null);
            when(catalog.prepareMaintenanceAuthorization()).thenReturn(new AssetMaintenanceAuthorization(9L, null, false, false));
            when(assets.selectById(7L)).thenReturn(DataAssetDO.builder().id(7L).status(1).build());
            assertThatThrownBy(() -> service.getAssetDetail(7L, false)).isInstanceOf(ServiceException.class);
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(10L);
            assertThatThrownBy(() -> service.getAssetPage(new DataAssetPageReqVO())).isInstanceOf(ServiceException.class);
            verifyNoInteractions(metadata);
        }
    }

    @Test
    void importedRowsRequireMaintenanceScopeRatherThanAConsumerGrant() {
        var snapshots = mock(AssetImportSnapshotService.class);
        ReflectionTestUtils.setField(service, "importSnapshots", snapshots);
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(4L);
            when(catalog.prepareMaintenanceAuthorization()).thenReturn(new AssetMaintenanceAuthorization(9L,4L,false,false));
            when(assets.selectById(7L)).thenReturn(DataAssetDO.builder().id(7L)
                    .externalCode("qdata:asset:49").assetType(1).ownerDeptId(8L).build());
            var request = new cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPreviewReqVO();
            request.setId(7L);
            assertThatThrownBy(() -> service.previewAsset(request)).isInstanceOf(ServiceException.class);
            verifyNoInteractions(snapshots, access, metadata);
        }
    }

    @Test
    void ownershipRevokedDuringImportedPreviewDiscardsTheResponse() {
        var snapshots = mock(AssetImportSnapshotService.class);
        ReflectionTestUtils.setField(service, "importSnapshots", snapshots);
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(4L);
            when(catalog.prepareMaintenanceAuthorization()).thenReturn(new AssetMaintenanceAuthorization(9L,4L,false,false));
            var before = DataAssetDO.builder().id(7L).externalCode("qdata:asset:49").assetType(1).ownerDeptId(4L).build();
            var after = DataAssetDO.builder().id(7L).externalCode("qdata:asset:49").assetType(1).ownerDeptId(8L).build();
            when(assets.selectById(7L)).thenReturn(before, after);
            when(snapshots.preview(any(),any())).thenReturn(new cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetImportPreviewRespVO());
            var request = new cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPreviewReqVO();
            request.setId(7L);
            assertThatThrownBy(() -> service.previewAsset(request)).isInstanceOf(ServiceException.class);
            verify(snapshots).preview(before,request);
            verifyNoInteractions(access,metadata);
        }
    }
}
