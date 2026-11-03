package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.module.asset.controller.admin.dataasset.DataAssetController;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetDetailRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.categorydataasset.CategoryDataAssetController;
import cn.iocoder.yudao.module.asset.controller.admin.categorydataasset.vo.CategoryDataAssetListReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCategoryDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketListingDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCategoryMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketListingMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketFavoriteMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceCommentMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.DataAssetService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketListingDetailTest {
    @Test
    void consumerDetailUsesSameListingAsCardAndRetainsFrozenAssetAndHostRedaction() {
        var assets = mock(DataAssetMapper.class); var catalog = mock(DataAssetService.class);
        var listings = mock(MarketListingMapper.class); var categories = mock(MarketCategoryMapper.class);
        var frozen = new DataAssetRespVO(); frozen.setId(5L); frozen.setAssetName("冻结资产名");
        frozen.setDescription("冻结说明"); frozen.setDatasourceHost("fixture.example.invalid");
        var detail = new DataAssetDetailRespVO(); detail.setAsset(frozen);
        when(catalog.getPublishedAssetDetail(5L)).thenReturn(detail);
        when(assets.selectById(5L)).thenReturn(DataAssetDO.builder().id(5L).status(1).assetName("未审草稿").build());
        var listing = new MarketListingDO(); listing.setAssetId(5L); listing.setMarketCategoryId(8L);
        listing.setDisplayName("集市展示名"); listing.setDescription("集市展示描述"); listing.setFrequency("每日"); listing.setDeliveryTypes("1,3");
        listing.setSourceRecordId("9007199254740993"); listing.setFileName("水情.csv");
        listing.setFileType("CSV文件(.csv)"); listing.setServiceIdentifier("49");
        listing.setPrimaryDeliveryType(3); listing.setDisplayScore("98.5");
        when(listings.selectByAssetIds(List.of(5L))).thenReturn(List.of(listing));
        var category = new MarketCategoryDO(); category.setId(8L); category.setName("真实集市类目");
        when(categories.selectByIds(java.util.Set.of(8L))).thenReturn(List.of(category));
        var display = new MarketDisplayHelper(); ReflectionTestUtils.setField(display, "listingMapper", listings);
        ReflectionTestUtils.setField(display, "categoryMapper", categories);
        var service = new MarketResourceServiceImpl(); ReflectionTestUtils.setField(service, "assetMapper", assets);
        ReflectionTestUtils.setField(service, "dataAssetService", catalog); ReflectionTestUtils.setField(service, "displayHelper", display);
        var guard = mock(MarketObjectGuard.class); when(guard.requireUserId()).thenReturn(9L);
        ReflectionTestUtils.setField(service, "objectGuard", guard);
        ReflectionTestUtils.setField(service, "applyMapper", mock(MarketResourceApplyMapper.class));
        ReflectionTestUtils.setField(service, "commentMapper", mock(MarketResourceCommentMapper.class));
        ReflectionTestUtils.setField(service, "favoriteMapper", mock(MarketFavoriteMapper.class));
        ReflectionTestUtils.setField(service, "marketAccessService", mock(MarketAccessService.class));
        var result = service.getConsumerDetail(5L).getAsset();
        assertThat(result.getAssetName()).isEqualTo("冻结资产名");
        assertThat(result.getDatasourceHost()).isNull();
        assertThat(result.getMarketName()).isEqualTo("集市展示名");
        assertThat(result.getMarketDescription()).isEqualTo("集市展示描述");
        assertThat(result.getMarketCategoryId()).isEqualTo(8L);
        assertThat(result.getMarketCategoryName()).isEqualTo("真实集市类目");
        assertThat(result.getFrequency()).isEqualTo("每日");
        assertThat(result.getDeliveryTypes()).containsExactly(1, 3);
        assertThat(result.getSourceRecordId()).isEqualTo("9007199254740993");
        assertThat(result.getFileName()).isEqualTo("水情.csv");
        assertThat(result.getFileType()).isEqualTo("CSV文件(.csv)");
        assertThat(result.getServiceIdentifier()).isEqualTo("49");
        assertThat(result.getPrimaryDeliveryType()).isEqualTo(3);
        assertThat(result.getDisplayScore()).isEqualTo("98.5");
        assertThat(result.getUsable()).isFalse();
    }

    @Test
    void mapReadPermissionDoesNotBroadenCategoryMaintenanceEndpoint() throws Exception {
        assertThat(DataAssetController.class.getMethod("navigationCategories").getAnnotation(PreAuthorize.class).value())
                .isEqualTo("isAuthenticated()");
        assertThat(CategoryDataAssetController.class.getMethod("getCategoryDataAssetList", CategoryDataAssetListReqVO.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermission('asset:category-data-asset:query')");
    }
}
