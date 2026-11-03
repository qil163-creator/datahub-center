package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketFavoriteReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketResourceRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketStatisticsRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketFavoriteDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketListingDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCategoryMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketListingMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetMaintenanceAuthorization;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketPublishReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketListingRespVO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketFavoriteMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.util.Objects;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_NOT_EXISTS;

@Service
@Validated
public class MarketMartServiceImpl implements MarketMartService {

    @Resource
    private MarketObjectGuard objectGuard;

    @Resource
    private MarketResourceService resourceService;

    @Resource
    private MarketAccessService accessService;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private MarketFavoriteMapper favoriteMapper;

    @Resource
    private MarketResourceApplyMapper applyMapper;

    @Resource
    private MarketCategoryService categoryService;

    @Resource
    private MarketCategoryMapper categoryMapper;

    @Resource
    private MarketListingMapper listingMapper;

    @Resource
    private AssetCatalogService catalogService;

    @Resource
    private MarketDisplayHelper displayHelper;

    @Resource
    private PlatformTransactionManager transactionManager;

    @Resource
    private MarketAuditRecorder audit;

    /**
     * 启用的顶级市场专题及其已发布资产数。
     */
    @Override
    public java.util.List<cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketTopicRespVO> getTopics() {
        objectGuard.requireUserId();
        var categories = categoryService.getEnabledList();
        var byId = categories.stream()
                .collect(Collectors.toMap(item -> item.getId(), java.util.function.Function.identity()));
        var totals = new java.util.HashMap<Long, Long>();
        for (var row : listingMapper.selectPublishedCategoryCounts()) {
            Long id = cn.hutool.core.convert.Convert.toLong(row.get("category_id"));
            Long count = cn.hutool.core.convert.Convert.toLong(row.get("cnt"), 0L);
            var visited = new java.util.HashSet<Long>();
            while (id != null && byId.containsKey(id) && visited.add(id)) {
                totals.merge(id, count, Long::sum);
                id = byId.get(id).getParentId();
            }
        }
        return categories.stream().filter(item -> Objects.equals(item.getParentId(), 0L))
                .map(item -> new cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketTopicRespVO(
                        item.getId(), item.getName(), totals.getOrDefault(item.getId(), 0L))).toList();
    }

    /**
     * 检索已发布资产，收藏与授权均绑定当前登录用户。
     */
    @Override
    public PageResult<MarketResourceRespVO> getPage(MarketPageReqVO reqVO) {
        Long userId = objectGuard.requireUserId();
        var authorizedIds = Boolean.TRUE.equals(reqVO.getAuthorizedOnly())
                ? accessService.findEffectiveApplies(userId, null).keySet() : null;
        var categories = reqVO.getMarketCategoryId() == null ? null : categoryService.descendants(
                reqVO.getMarketCategoryId());
        var page = resourceService.getMarketPage(reqVO, userId, authorizedIds, categories);
        var favorites = favoriteMapper.selectByUserAndAssets(userId,
                        page.getList().stream().map(MarketResourceRespVO::getId).toList()).stream()
                .map(MarketFavoriteDO::getAssetId).collect(Collectors.toSet());
        page.getList().forEach(asset -> asset.setFavorite(favorites.contains(asset.getId())));
        displayHelper.fillListings(page.getList());
        return page;
    }

    /**
     * 已发布总数及当前用户授权、收藏、在途申请资产数。
     */
    @Override
    public MarketStatisticsRespVO getStatistics() {
        Long userId = objectGuard.requireUserId();
        var authorizedIds = accessService.findEffectiveApplies(userId, null).keySet();
        var result = new MarketStatisticsRespVO();
        result.setTotalAssetCount(assetMapper.selectCount(new LambdaQueryWrapperX<DataAssetDO>()
                .eq(DataAssetDO::getStatus, DataAssetDO.STATUS_PUBLISHED)));
        result.setAuthorizedAssetCount(authorizedIds.isEmpty() ? 0L
                : assetMapper.selectCount(new LambdaQueryWrapperX<DataAssetDO>()
                .eq(DataAssetDO::getStatus, DataAssetDO.STATUS_PUBLISHED).in(DataAssetDO::getId, authorizedIds)));
        result.setFavoriteAssetCount(favoriteMapper.selectPublishedCount(userId));
        result.setApplyingAssetCount(applyMapper.selectApplyingPublishedAssetCount(userId));
        return result;
    }

    /**
     * 设置当前用户收藏状态，重复设置保持幂等。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setFavorite(MarketFavoriteReqVO reqVO) {
        Long userId = objectGuard.requireUserId();
        if (Boolean.TRUE.equals(reqVO.getFavorite())) {
            // 与取消发布串行化，收藏落库前始终保持该资产在市场可见。
            var asset = assetMapper.selectForMatching(reqVO.getAssetId());
            if (asset == null || !Objects.equals(asset.getStatus(), DataAssetDO.STATUS_PUBLISHED)) {
                throw exception(DATA_ASSET_NOT_EXISTS);
            }
            favoriteMapper.insertIfAbsent(userId, reqVO.getAssetId());
        } else {
            favoriteMapper.delete(new LambdaQueryWrapperX<MarketFavoriteDO>()
                    .eq(MarketFavoriteDO::getUserId, userId).eq(MarketFavoriteDO::getAssetId, reqVO.getAssetId()));
        }
    }

    /**
     * 读取市场展示信息供发布表单回显，不修改发布状态。
     */
    @Override
    public MarketListingRespVO getListing(Long assetId) {
        objectGuard.requireUserId();
        var asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        var source = asset;
        if (Objects.equals(asset.getStatus(), DataAssetDO.STATUS_PUBLISHED)) {
            source = catalogService.publishedAsset(asset);
        } else {
            catalogService.assertCanMaintain(asset);
        }
        var listing = listingMapper.selectById(assetId);
        var response = new MarketListingRespVO();
        response.setAssetId(assetId);
        response.setRevision(listing == null ? 0L : listing.getRevision());
        response.setMarketCategoryId(listing == null ? null : listing.getMarketCategoryId());
        response.setName(listing == null ? source.getAssetName() : listing.getDisplayName());
        response.setDescription(listing == null ? source.getDescription() : listing.getDescription());
        response.setFrequency(listing == null ? null : listing.getFrequency());
        response.setDeliveryTypes(listing == null ? java.util.List.of() : MarketDisplayHelper.listingDeliveryTypes(
                listing.getDeliveryTypes()));
        response.setExpectedRevision(asset.getCatalogRevision() == null ? 0L : asset.getCatalogRevision());
        return response;
    }

    /**
     * 保存市场展示配置并沿资产目录原状态机发布。
     */
    @Override
    public void publish(MarketPublishReqVO reqVO) {
        Long userId = objectGuard.requireUserId();
        var authorization = catalogService.prepareMaintenanceAuthorization();
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> publishLocked(reqVO, userId, authorization));
    }

    /**
     * 发布已锁定。
     */
    private void publishLocked(MarketPublishReqVO reqVO, Long userId, AssetMaintenanceAuthorization authorization) {
        var asset = assetMapper.selectForCatalogUpdate(reqVO.getAssetId());
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        catalogService.assertCanMaintain(asset, authorization);
        if (!Objects.equals(reqVO.getExpectedRevision(),
                asset.getCatalogRevision() == null ? 0L : asset.getCatalogRevision())) {
            throw exception(
                    cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_CATALOG_REVISION_CONFLICT);
        }
        var current = listingMapper.selectById(reqVO.getAssetId());
        long revision = current == null ? 0L : current.getRevision();
        if (!Objects.equals(reqVO.getExpectedListingRevision(), revision)) {
            throw exception(
                    cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_CATALOG_REVISION_CONFLICT);
        }
        categoryService.requireEnabledForPublish(reqVO.getMarketCategoryId());
        var listing = new MarketListingDO();
        listing.setAssetId(reqVO.getAssetId());
        listing.setRevision(Math.addExact(revision, 1L));
        listing.setMarketCategoryId(reqVO.getMarketCategoryId());
        listing.setDisplayName(reqVO.getName().trim());
        listing.setDescription(reqVO.getDescription());
        listing.setFrequency(reqVO.getFrequency().trim());
        listing.setDeliveryTypes(reqVO.getDeliveryTypes().stream().distinct().sorted().map(String::valueOf)
                .collect(Collectors.joining(",")));
        listingMapper.upsert(listing, userId);
        catalogService.publishLatestApproved(reqVO.getAssetId(), authorization);
        // 只记录配置字段是否变化及枚举/版本摘要，不把名称、描述或频率正文复制到审计日志。
        var changed = new java.util.ArrayList<String>();
        if (current == null || !Objects.equals(current.getDisplayName(), listing.getDisplayName())) {
            changed.add("name");
        }
        if (current == null || !Objects.equals(current.getDescription(), listing.getDescription())) {
            changed.add("description");
        }
        if (current == null || !Objects.equals(current.getFrequency(), listing.getFrequency())) {
            changed.add("frequency");
        }
        if (current == null || !Objects.equals(current.getMarketCategoryId(), listing.getMarketCategoryId())) {
            changed.add("marketCategoryId");
        }
        if (current == null || !Objects.equals(current.getDeliveryTypes(), listing.getDeliveryTypes())) {
            changed.add("deliveryTypes");
        }
        String reason = "集市版本 " + revision + "->" + listing.getRevision() + ";类目 "
                + (current == null ? "无" : current.getMarketCategoryId()) + "->" + listing.getMarketCategoryId()
                + ";交付形态 " + (current == null ? "无" : current.getDeliveryTypes()) + "->" + listing.getDeliveryTypes()
                + ";变更字段 " + String.join(",", changed);
        audit.recordAs("MARKET_LISTING_PUBLISH", "MARKET_LISTING", reqVO.getAssetId(), null, null, reason, "OK",
                userId, authorization.getDeptId());
    }

}
