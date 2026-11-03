package cn.iocoder.yudao.module.asset.service.sensitive;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.sensitive.vo.AssetSensitivePageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.sensitive.vo.AssetSensitiveSaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.sensitive.vo.AssetSensitiveStatusReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetImportDatasetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.sensitive.AssetSensitiveListDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetImportDatasetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.sensitive.AssetSensitiveListMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetMaintenanceAuthorization;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.security.SensitiveClassificationReferenceApi;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyClassificationRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** 当前用户令牌贯穿分类选项、目录筛选和写前核验，资产范围及字段约束保持生效。 */
class AssetSensitiveClassificationReferenceTest {

    private static final String AUTHORIZATION = "Bearer sensitive-session";
    private final AssetSensitiveListService service = new AssetSensitiveListService();
    private final SensitiveClassificationReferenceApi safety = mock(SensitiveClassificationReferenceApi.class);
    private final AssetCatalogService catalog = mock(AssetCatalogService.class);
    private final AssetSensitiveListMapper mapper = mock(AssetSensitiveListMapper.class);
    private final DataAssetMapper assetMapper = mock(DataAssetMapper.class);
    private final AssetImportDatasetMapper datasetMapper = mock(AssetImportDatasetMapper.class);
    private final AssetSensitiveListWriter writer = mock(AssetSensitiveListWriter.class);
    private final AssetMaintenanceAuthorization auth = mock(AssetMaintenanceAuthorization.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final SecurityProperties securityProperties = new SecurityProperties();

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(service, "safety", safety);
        ReflectionTestUtils.setField(service, "catalog", catalog);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "assetMapper", assetMapper);
        ReflectionTestUtils.setField(service, "datasetMapper", datasetMapper);
        ReflectionTestUtils.setField(service, "writer", writer);
        ReflectionTestUtils.setField(service, "servletRequest", request);
        ReflectionTestUtils.setField(service, "securityProperties", securityProperties);
        request.addHeader("Authorization", AUTHORIZATION);
        when(catalog.prepareMaintenanceAuthorization()).thenReturn(auth);
        when(auth.getUserId()).thenReturn(9L);
        when(auth.getDeptId()).thenReturn(4L);
    }

    @Test
    void categoriesForwardCurrentTokenAndPreserveClassificationProjection() {
        when(safety.getClassifications(AUTHORIZATION)).thenReturn(CommonResult.success(List.of(category(24L, "dir:1"))));
        var categories = service.categories();
        assertThat(categories).singleElement().satisfies(node -> {
            assertThat(node.key()).isEqualTo("category:24");
            assertThat(node.categoryId()).isEqualTo(24L);
            assertThat(node.type()).isEqualTo("CATEGORY");
            assertThat(node.status()).isZero();
            assertThat(node.selectable()).isTrue();
        });
        verify(catalog).prepareMaintenanceAuthorization();
        verify(safety).getClassifications(AUTHORIZATION);
    }

    @Test
    void configuredTokenHeaderAndParameterFollowExistingHeaderPrecedence() {
        securityProperties.setTokenHeader("X-Access-Token");
        securityProperties.setTokenParameter("access_token");
        request.addHeader("X-Access-Token", "Bearer header-session");
        request.addParameter("access_token", "parameter-session");
        when(safety.getClassifications(anyString())).thenReturn(CommonResult.success(List.of()));
        service.categories();
        request.removeHeader("X-Access-Token");
        service.categories();
        verify(safety).getClassifications("Bearer header-session");
        verify(safety).getClassifications("Bearer parameter-session");
    }

    @Test
    void missingTokenCannotBeSynthesizedFromInternalIdentityHeader() {
        request.removeHeader("Authorization");
        request.addHeader(SecurityFrameworkUtils.LOGIN_USER_HEADER, "{\"id\":9,\"userType\":2}");
        assertThat(assertThrows(ServiceException.class, service::categories).getCode()).isEqualTo(UNAUTHORIZED.getCode());
        verifyNoInteractions(safety);
    }

    @Test
    void directoryFilterUsesDescendantsAndKeepsAssetMaintenanceScope() {
        var root = new SafetyClassificationRespDTO(); root.setId("dir:1");
        var child = new SafetyClassificationRespDTO(); child.setId("dir:2"); child.setParentId("dir:1");
        when(safety.getClassifications(AUTHORIZATION)).thenReturn(CommonResult.success(List.of(
                root, child, category(24L, "dir:2"), category(25L, "dir:1"), category(26L, "dir:99"))));
        var query = new AssetSensitivePageReqVO(); query.setCategoryKey("dir:1");
        when(mapper.page(query, List.of(24L, 25L), 9L, 4L, false)).thenReturn(PageResult.empty());
        assertThat(service.page(query).getTotal()).isZero();
        verify(mapper).page(query, List.of(24L, 25L), 9L, 4L, false);
        verify(safety).getClassifications(AUTHORIZATION);
    }

    @Test
    void createUpdateAndEnableUseCurrentAuthorizedClassificationBeforeWriting() {
        var asset = prepareAsset();
        var dataset = new AssetImportDatasetDO(); dataset.setRevision(7);
        when(datasetMapper.selectById(1L)).thenReturn(dataset);
        when(safety.getClassification(24L, AUTHORIZATION)).thenReturn(CommonResult.success(category(24L, "dir:1")));
        when(writer.create(any(), any(), same(auth))).thenReturn(8L);
        var create = saveRequest();
        assertThat(service.create(create)).isEqualTo(8L);
        var previous = disabledRow();
        when(mapper.selectById(8L)).thenReturn(previous);
        var update = saveRequest(); update.setId(8L); update.setRevision(0);
        service.update(update);
        var status = statusRequest(0);
        service.changeStatus(status);

        var prepared = ArgumentCaptor.forClass(AssetSensitiveListService.Prepared.class);
        verify(writer).create(same(create), prepared.capture(), same(auth));
        verify(writer).update(same(update), eq(1L), prepared.capture(), same(auth));
        verify(writer).changeStatus(same(status), eq(1L), prepared.capture(), same(auth));
        assertThat(prepared.getAllValues()).allSatisfy(value -> {
            assertThat(value.asset()).isSameAs(asset);
            assertThat(value.columnId()).isEqualTo(101L);
            assertThat(value.columnName()).isEqualTo("STCD");
            assertThat(value.datasetRevision()).isEqualTo(7);
            assertThat(value.category().getCategoryId()).isEqualTo(24L);
        });
        verify(safety, times(3)).getClassification(24L, AUTHORIZATION);
        verify(catalog, atLeast(3)).assertCanMaintain(asset, auth);
    }

    @Test
    void unauthorizedAssetIsRejectedBeforeClassificationLookupAndWrite() {
        var asset = prepareAsset();
        doThrow(exception(DATA_ASSET_FORBIDDEN)).when(catalog).assertCanMaintain(asset, auth);
        assertThat(assertThrows(ServiceException.class, () -> service.create(saveRequest())).getCode())
                .isEqualTo(DATA_ASSET_FORBIDDEN.getCode());
        verifyNoInteractions(safety, writer);
    }

    @Test
    void invalidColumnIsRejectedBeforeClassificationLookupAndWrite() {
        prepareAsset();
        var save = saveRequest(); save.setColumnId(999L);
        assertThat(assertThrows(ServiceException.class, () -> service.create(save)).getCode())
                .isEqualTo(ASSET_SENSITIVE_COLUMN_INVALID.getCode());
        verifyNoInteractions(safety, writer);
    }

    @Test
    void disabledClassificationAndExpiredTokenNeverReachWriter() {
        prepareAsset();
        var disabled = category(24L, "dir:1"); disabled.setStatus((short) 1);
        when(safety.getClassification(24L, AUTHORIZATION)).thenReturn(CommonResult.success(disabled));
        assertThat(assertThrows(ServiceException.class, () -> service.create(saveRequest())).getCode())
                .isEqualTo(ASSET_SENSITIVE_CATEGORY_INVALID.getCode());
        when(safety.getClassification(24L, AUTHORIZATION)).thenReturn(CommonResult.error(UNAUTHORIZED));
        assertThat(assertThrows(ServiceException.class, () -> service.create(saveRequest())).getCode())
                .isEqualTo(UNAUTHORIZED.getCode());
        verifyNoInteractions(writer);
    }

    @Test
    void disablingExistingEntryDoesNotDependOnClassificationService() {
        prepareAsset();
        when(mapper.selectById(8L)).thenReturn(disabledRow());
        var status = statusRequest(1);
        service.changeStatus(status);
        verify(writer).changeStatus(status, 1L, null, auth);
        verifyNoInteractions(safety);
    }

    private DataAssetDO prepareAsset() {
        var asset = new DataAssetDO(); asset.setId(1L);
        when(assetMapper.selectById(1L)).thenReturn(asset);
        var column = new MetadataColumnRespDTO(); column.setId(101L); column.setColumnName("STCD");
        when(catalog.listColumns(1L, null)).thenReturn(List.of(column));
        return asset;
    }

    private static AssetSensitiveSaveReqVO saveRequest() {
        var request = new AssetSensitiveSaveReqVO();
        request.setAssetId(1L); request.setColumnId(101L); request.setCategoryId(24L);
        return request;
    }

    private static AssetSensitiveStatusReqVO statusRequest(int status) {
        var request = new AssetSensitiveStatusReqVO();
        request.setId(8L); request.setRevision(0); request.setStatus(status);
        return request;
    }

    private static AssetSensitiveListDO disabledRow() {
        var row = new AssetSensitiveListDO();
        row.setId(8L); row.setAssetId(1L); row.setColumnId(101L); row.setCategoryId(24L);
        row.setStatus(1); row.setRevision(0);
        return row;
    }

    private static SafetyClassificationRespDTO category(Long id, String parent) {
        var category = new SafetyClassificationRespDTO();
        category.setId("category:" + id); category.setParentId(parent); category.setCategoryId(id);
        category.setName("测站编码"); category.setSelectable(true); category.setStatus((short) 0);
        return category;
    }
}
