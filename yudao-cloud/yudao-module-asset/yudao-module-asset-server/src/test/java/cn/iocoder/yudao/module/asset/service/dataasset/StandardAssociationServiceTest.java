package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.controller.admin.standardassociation.vo.StandardAssociationPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetTechLinkDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetTechLinkMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.service.common.AssetUserNameResolver;
import cn.iocoder.yudao.module.governance.api.standard.StandardAssociationApi;
import cn.iocoder.yudao.module.governance.api.standard.dto.StandardAssociationColumnRespDTO;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StandardAssociationServiceTest extends BaseMockitoUnitTest {
    @InjectMocks private StandardAssociationService service;
    @Mock private StandardAssociationApi standardApi;
    @Mock private DataAssetMapper assets;
    @Mock private AssetTechLinkMapper links;
    @Mock private AssetCatalogService catalog;
    @Mock private AssetUserNameResolver users;
    @Mock private SecurityFrameworkService security;

    @BeforeEach void login() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DataAssetDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AssetTechLinkDO.class);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L).setInfo(Map.of(LoginUser.INFO_KEY_DEPT_ID, "9")), new MockHttpServletRequest());
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void rejectsWrongKindPermissionBeforeRpc() {
        assertThrows(ServiceException.class, () -> service.getPage(request("codeset")));
        verify(security).hasPermission("governance:standard-code-set:query");
        verifyNoInteractions(standardApi, assets, links);
    }

    @Test void permissionAndOwnerRpcFailureDoesNotBecomeEmptySuccess() {
        when(security.hasPermission(anyString())).thenReturn(true);
        when(standardApi.getColumns("element", 10)).thenReturn(CommonResult.error(403, "无权读取"));
        assertThrows(ServiceException.class, () -> service.getPage(request("element")));
        verifyNoInteractions(assets, links);
    }

    @Test void mismatchedAuthorizationIdentityIsRejected() {
        setup(List.of());
        when(catalog.prepareMaintenanceAuthorization()).thenReturn(new AssetMaintenanceAuthorization(8L, 9L, true, true));
        assertThrows(ServiceException.class, () -> service.getPage(request("element")));
        verifyNoInteractions(assets, links);
    }

    @Test void missingDepartmentNeverMatchesUnownedAssetsAndFilteringPrecedesPagination() {
        setup(List.of(field(1L, 20L, "t", "c")));
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
        when(catalog.prepareMaintenanceAuthorization()).thenReturn(new AssetMaintenanceAuthorization(7L, null, false, false));
        var forbidden = asset(30L, 20L); forbidden.setCreator("99");
        var first = asset(29L, 20L); first.setCreator("7");
        var second = asset(28L, 20L); second.setTechLeaderUserId(7L);
        when(assets.selectList(any(Wrapper.class))).thenReturn(List.of(forbidden, first, second));
        when(links.selectList(any(Wrapper.class))).thenReturn(List.of());
        var request = request("element"); request.setPageSize(1); request.setPageNo(2);
        var result = service.getPage(request);
        assertEquals(2L, result.getTotal()); assertEquals(28L, result.getList().getFirst().getId());
        verify(users).resolveByTexts(List.of("99"));
    }

    @Test void authoritativeLinksExcludeOldProjectionAndMergeMultitableFields() {
        setup(List.of(field(1L, 20L, "a", "id"), field(2L, 21L, "b", "id"), field(2L, 21L, "b", "id")));
        var stale = asset(30L, 20L); var multi = asset(29L, 20L);
        stale.setCreator("7"); multi.setCreator("7");
        when(assets.selectList(any(Wrapper.class))).thenReturn(List.of(stale, multi));
        when(links.selectList(any(Wrapper.class))).thenReturn(List.of(link(29L, 20L)),
                List.of(link(30L, 99L), link(29L, 20L), link(29L, 21L)));
        var result = service.getPage(request("element"));
        assertEquals(1L, result.getTotal());
        var row = result.getList().getFirst();
        assertEquals(29L, row.getId()); assertEquals("a、b", row.getTableName());
        assertEquals(List.of("a.id", "b.id"), row.getColumnNames());
    }

    @Test void largePageDoesNotOverflowOrRevealUnpagedResults() {
        setup(List.of(field(1L, 20L, "a", "id")));
        var asset = asset(9007199254740993L, 20L); asset.setCreator("7");
        when(assets.selectList(any(Wrapper.class))).thenReturn(List.of(asset));
        when(links.selectList(any(Wrapper.class))).thenReturn(List.of());
        var request = request("element"); request.setPageNo(Integer.MAX_VALUE); request.setPageSize(200);
        var result = service.getPage(request);
        assertEquals(1L, result.getTotal()); assertTrue(result.getList().isEmpty());
    }

    @Test void anonymousAndInvalidPaginationNeverReachRpc() {
        var invalid = request("element"); invalid.setPageSize(-1);
        assertThrows(ServiceException.class, () -> service.getPage(invalid));
        SecurityContextHolder.clearContext();
        assertThrows(ServiceException.class, () -> service.getPage(request("element")));
        verifyNoInteractions(standardApi, assets, links);
    }

    private void setup(List<StandardAssociationColumnRespDTO> fields) {
        when(security.hasPermission(anyString())).thenReturn(true);
        when(standardApi.getColumns("element", 10)).thenReturn(CommonResult.success(fields));
        when(catalog.prepareMaintenanceAuthorization()).thenReturn(new AssetMaintenanceAuthorization(7L, 9L, false, false));
    }
    private StandardAssociationPageReqVO request(String kind) {
        var request = new StandardAssociationPageReqVO(); request.setKind(kind); request.setId(10); return request;
    }
    private StandardAssociationColumnRespDTO field(long id, long table, String tableName, String column) {
        var field = new StandardAssociationColumnRespDTO(); field.setColumnId(id); field.setTableId(table);
        field.setTableName(tableName); field.setColumnName(column); return field;
    }
    private DataAssetDO asset(long id, long table) {
        var asset = DataAssetDO.builder().id(id).metaTableId(table).assetName("资产" + id).build();
        asset.setCreator("99"); asset.setCreateTime(LocalDateTime.of(2026, 10, 16, 12, 0)); return asset;
    }
    private AssetTechLinkDO link(long assetId, long table) {
        return AssetTechLinkDO.builder().assetId(assetId).metaTableId(table).build();
    }
}
