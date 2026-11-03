package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetCatalogVersionDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetCatalogVersionMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssetMaintenanceAuthorizationTest {
    private final AssetCatalogServiceImpl service = new AssetCatalogServiceImpl();
    private final PermissionApi permissions = mock(PermissionApi.class);

    @BeforeEach
    void injectDependencies() { ReflectionTestUtils.setField(service, "permissionApi", permissions); }

    @Test
    void prefetchedEvidenceIsActorBoundAndLockedAssetOwnershipIsRecheckedWithoutRpc() {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(4L);
            when(permissions.hasAnyRoles(9L, "super_admin")).thenReturn(CommonResult.success(false));
            when(permissions.hasAnyPermissions(eq(9L), any(String[].class))).thenReturn(CommonResult.success(false));
            var proof = service.prepareMaintenanceAuthorization();
            clearInvocations(permissions);
            var asset = DataAssetDO.builder().id(5L).techLeaderUserId(9L).build();
            service.assertCanMaintain(asset, proof);
            asset.setTechLeaderUserId(10L);
            assertThatThrownBy(() -> service.assertCanMaintain(asset, proof)).isInstanceOf(ServiceException.class);
            verifyNoInteractions(permissions);
        }
    }

    @Test
    void proofCannotBeReusedAfterIdentityOrDepartmentChanges() {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            var proof = new AssetMaintenanceAuthorization(9L, 4L, true, true);
            var asset = DataAssetDO.builder().id(5L).build();
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(10L);
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(4L);
            assertThatThrownBy(() -> service.assertCanMaintain(asset, proof)).isInstanceOf(ServiceException.class);
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(8L);
            assertThatThrownBy(() -> service.assertCanMaintain(asset, proof)).isInstanceOf(ServiceException.class);
            verifyNoInteractions(permissions);
        }
    }

    @Test
    void missingDepartmentsCannotGrantOwnershipThroughTwoNulls() {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            var proof = new AssetMaintenanceAuthorization(9L, null, false, false);
            assertThatThrownBy(() -> service.assertCanMaintain(DataAssetDO.builder().id(5L).build(), proof))
                    .isInstanceOf(ServiceException.class);
            verifyNoInteractions(permissions);
        }
    }

    @Test
    void queryOnlyUserCannotReadSomeoneElsesTechnicalLinksOrDraftVersions() {
        var assets = mock(DataAssetMapper.class);
        var links = mock(cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetTechLinkMapper.class);
        var versions = mock(AssetCatalogVersionMapper.class);
        ReflectionTestUtils.setField(service, "assetMapper", assets);
        ReflectionTestUtils.setField(service, "techLinkMapper", links);
        ReflectionTestUtils.setField(service, "versionMapper", versions);
        when(assets.selectById(5L)).thenReturn(DataAssetDO.builder().id(5L).techLeaderUserId(88L).build());
        when(permissions.hasAnyRoles(9L, "super_admin")).thenReturn(CommonResult.success(false));
        when(permissions.hasAnyPermissions(eq(9L), any(String[].class))).thenReturn(CommonResult.success(false));
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            assertThatThrownBy(() -> service.listTechLinks(5L)).isInstanceOf(ServiceException.class);
            assertThatThrownBy(() -> service.listCatalogVersions(5L)).isInstanceOf(ServiceException.class);
        }
        verifyNoInteractions(links, versions);
    }

    @Test
    void approvedPublisherReusesPreloadedEvidenceWithoutRemotePermissionLookupUnderLock() {
        var versions = mock(AssetCatalogVersionMapper.class); var assets = mock(DataAssetMapper.class);
        ReflectionTestUtils.setField(service, "versionMapper", versions); ReflectionTestUtils.setField(service, "assetMapper", assets);
        var asset = DataAssetDO.builder().id(5L).publishedCatalogVersionId(7L).retirementRequested(true).techLeaderUserId(9L).build();
        when(assets.selectById(5L)).thenReturn(asset); when(assets.selectForCatalogUpdate(5L)).thenReturn(asset);
        var version = AssetCatalogVersionDO.builder().id(7L).assetId(5L).status(AssetCatalogVersionDO.STATUS_PUBLISHED).build();
        when(versions.selectListByAssetId(5L)).thenReturn(List.of(version)); when(versions.selectById(7L)).thenReturn(version);
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            var proof = new AssetMaintenanceAuthorization(9L, null, false, false);
            assertThatThrownBy(() -> service.publishLatestApproved(5L, proof)).isInstanceOf(ServiceException.class);
            verify(assets).selectForCatalogUpdate(5L); verifyNoInteractions(permissions);
        }
    }
}
