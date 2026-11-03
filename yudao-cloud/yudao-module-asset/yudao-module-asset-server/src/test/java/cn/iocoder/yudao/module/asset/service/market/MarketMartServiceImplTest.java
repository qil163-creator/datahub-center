package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.*;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.*;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetMaintenanceAuthorization;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketMartServiceImplTest {
    private final MarketMartServiceImpl service = new MarketMartServiceImpl();
    private final MarketObjectGuard guard = mock(MarketObjectGuard.class);
    private final MarketResourceService resources = mock(MarketResourceService.class);
    private final MarketAccessService access = mock(MarketAccessService.class);
    private final DataAssetMapper assets = mock(DataAssetMapper.class);
    private final MarketFavoriteMapper favorites = mock(MarketFavoriteMapper.class);
    private final MarketResourceApplyMapper applies = mock(MarketResourceApplyMapper.class);
    private final MarketListingMapper listings = mock(MarketListingMapper.class);
    private final MarketCategoryMapper categories = mock(MarketCategoryMapper.class);
    private final MarketCategoryService categoryService = mock(MarketCategoryService.class);
    private final AssetCatalogService catalog = mock(AssetCatalogService.class);
    private final AssetMaintenanceAuthorization authorization = mock(AssetMaintenanceAuthorization.class);
    private final MarketAuditRecorder audit = mock(MarketAuditRecorder.class);

    @BeforeAll
    static void initializeMetadata() {
        for (var type : List.of(DataAssetDO.class, MarketFavoriteDO.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }

    @BeforeEach
    void injectDependencies() {
        Map.ofEntries(Map.entry("objectGuard", guard), Map.entry("resourceService", resources),
                Map.entry("accessService", access), Map.entry("assetMapper", assets), Map.entry("favoriteMapper", favorites),
                Map.entry("applyMapper", applies), Map.entry("listingMapper", listings), Map.entry("categoryMapper", categories),
                Map.entry("categoryService", categoryService), Map.entry("catalogService", catalog),
                Map.entry("audit", audit)).forEach((name, value) -> ReflectionTestUtils.setField(service, name, value));
        when(guard.requireUserId()).thenReturn(9L);
        when(catalog.prepareMaintenanceAuthorization()).thenReturn(authorization);
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "transactionManager", manager);
        var display = new MarketDisplayHelper();
        ReflectionTestUtils.setField(display, "listingMapper", listings);
        ReflectionTestUtils.setField(display, "categoryMapper", categories);
        ReflectionTestUtils.setField(service, "displayHelper", display);
    }

    @Test
    void favoriteUsesCurrentActorAndRequiresPublishedAsset() {
        var req = favorite(true);
        when(assets.selectForMatching(5L)).thenReturn(DataAssetDO.builder().id(5L).status(1).build());
        service.setFavorite(req);
        service.setFavorite(req);
        verify(favorites, times(2)).insertIfAbsent(9L, 5L);
        when(assets.selectForMatching(5L)).thenReturn(DataAssetDO.builder().id(5L).status(0).build());
        assertError(() -> service.setFavorite(req), DATA_ASSET_NOT_EXISTS.getCode());
        verify(favorites, times(2)).insertIfAbsent(anyLong(), anyLong());
    }

    @Test
    void removalAllowsOfflineAssetsButIsAlwaysActorScoped() {
        service.setFavorite(favorite(false));
        verifyNoInteractions(assets);
        verify(favorites).delete(org.mockito.ArgumentMatchers.<Wrapper<MarketFavoriteDO>>argThat(wrapper -> {
            String sql = wrapper.getSqlSegment();
            var parameters = ((cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<?>) wrapper).getParamNameValuePairs();
            return sql.contains("user_id") && sql.contains("asset_id") && parameters.containsValue(9L) && parameters.containsValue(5L);
        }));
    }

    @Test
    void publishedPrefillReadsFrozenNameRatherThanMutableDraft() {
        var current = DataAssetDO.builder().id(5L).assetName("尚未审核的名称").description("草稿说明")
                .status(1).catalogRevision(4L).build();
        var frozen = DataAssetDO.builder().id(5L).assetName("已审核名称").description("已审核说明").build();
        when(assets.selectById(5L)).thenReturn(current);
        when(catalog.publishedAsset(current)).thenReturn(frozen);
        var result = service.getListing(5L);
        assertThat(result.getName()).isEqualTo("已审核名称");
        assertThat(result.getDescription()).isEqualTo("已审核说明");
        assertThat(result.getExpectedRevision()).isEqualTo(4L);
        assertThat(result.getMarketCategoryId()).isNull();
        assertThat(result.getFrequency()).isNull();
        assertThat(result.getDeliveryTypes()).isEmpty();
        verify(catalog, never()).assertCanMaintain(any());
    }

    @Test
    void draftPrefillRequiresMaintenanceGuardBeforeReadingListing() {
        var draft = DataAssetDO.builder().id(5L).status(0).build();
        when(assets.selectById(5L)).thenReturn(draft);
        doThrow(new ServiceException(DATA_ASSET_FORBIDDEN)).when(catalog).assertCanMaintain(draft);
        assertError(() -> service.getListing(5L), DATA_ASSET_FORBIDDEN.getCode());
        verifyNoInteractions(listings);
    }

    @Test
    void publishingCanonicalizesDeclarationsAndDelegatesToOriginalStateMachine() {
        var asset = DataAssetDO.builder().id(5L).catalogRevision(4L).build();
        when(assets.selectForCatalogUpdate(5L)).thenReturn(asset);
        var req = publish();
        service.publish(req);
        var captured = ArgumentCaptor.forClass(MarketListingDO.class);
        var order = inOrder(catalog, categoryService, listings);
        order.verify(catalog).prepareMaintenanceAuthorization();
        order.verify(catalog).assertCanMaintain(asset, authorization);
        order.verify(categoryService).requireEnabledForPublish(8L);
        order.verify(listings).upsert(captured.capture(), eq(9L));
        order.verify(catalog).publishLatestApproved(5L, authorization);
        assertThat(captured.getValue().getDisplayName()).isEqualTo("市场名称");
        assertThat(captured.getValue().getFrequency()).isEqualTo("每日");
        assertThat(captured.getValue().getDeliveryTypes()).isEqualTo("1,3");
        assertThat(captured.getValue().getRevision()).isEqualTo(1L);
    }

    @Test
    void stalePublicationCannotOverwriteListingOrInvokePublisher() {
        when(assets.selectForCatalogUpdate(5L)).thenReturn(DataAssetDO.builder().id(5L).catalogRevision(5L).build());
        assertError(() -> service.publish(publish()), DATA_ASSET_CATALOG_REVISION_CONFLICT.getCode());
        verifyNoInteractions(listings, categoryService, audit);
    }

    @Test
    void listingFallbackAndFavoritesKeepAssetIdentityAndFrozenFields() {
        var req = new MarketPageReqVO();
        var row = new MarketResourceRespVO();
        row.setId(5L); row.setAssetName("冻结名称"); row.setDescription("冻结描述");
        when(resources.getMarketPage(req, 9L, null, null)).thenReturn(new PageResult<>(List.of(row), 1L));
        var favorite = new MarketFavoriteDO(); favorite.setAssetId(5L);
        when(favorites.selectByUserAndAssets(9L, List.of(5L))).thenReturn(List.of(favorite));
        var result = service.getPage(req).getList().getFirst();
        assertThat(result.getId()).isEqualTo(5L);
        assertThat(result.getMarketName()).isEqualTo("冻结名称");
        assertThat(result.getMarketDescription()).isEqualTo("冻结描述");
        assertThat(result.getFavorite()).isTrue();
        assertThat(result.getDeliveryTypes()).isEmpty();
        verifyNoInteractions(access);
    }

    @Test
    void authorizedFilterAndCategorySubtreeAreAppliedBeforePagination() {
        var req = new MarketPageReqVO(); req.setAuthorizedOnly(true); req.setMarketCategoryId(8L);
        when(access.findEffectiveApplies(9L, null)).thenReturn(Map.of(5L, new MarketResourceApplyDO()));
        when(categoryService.descendants(8L)).thenReturn(Set.of(8L, 10L));
        when(resources.getMarketPage(req, 9L, Set.of(5L), Set.of(8L, 10L))).thenReturn(PageResult.empty());
        assertThat(service.getPage(req).getList()).isEmpty();
        verify(resources).getMarketPage(req, 9L, Set.of(5L), Set.of(8L, 10L));
    }

    @Test
    void emptyAuthorizationDoesNotTurnIntoUnfilteredStatisticCount() {
        when(access.findEffectiveApplies(9L, null)).thenReturn(Map.of());
        when(assets.selectCount(any(Wrapper.class))).thenReturn(12L);
        when(favorites.selectPublishedCount(9L)).thenReturn(2L);
        when(applies.selectApplyingPublishedAssetCount(9L)).thenReturn(3L);
        var result = service.getStatistics();
        assertThat(result.getTotalAssetCount()).isEqualTo(12L);
        assertThat(result.getAuthorizedAssetCount()).isZero();
        assertThat(result.getFavoriteAssetCount()).isEqualTo(2L);
        assertThat(result.getApplyingAssetCount()).isEqualTo(3L);
        verify(assets).selectCount(any(Wrapper.class));
    }

    private static MarketFavoriteReqVO favorite(boolean value) {
        var req = new MarketFavoriteReqVO(); req.setAssetId(5L); req.setFavorite(value); return req;
    }

    private static MarketPublishReqVO publish() {
        var req = new MarketPublishReqVO(); req.setAssetId(5L); req.setMarketCategoryId(8L);
        req.setName(" 市场名称 "); req.setDescription("集市描述"); req.setFrequency(" 每日 ");
        req.setDeliveryTypes(List.of(3, 1, 3)); req.setExpectedRevision(4L); req.setExpectedListingRevision(0L); return req;
    }

    private static void assertError(Runnable operation, int code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
