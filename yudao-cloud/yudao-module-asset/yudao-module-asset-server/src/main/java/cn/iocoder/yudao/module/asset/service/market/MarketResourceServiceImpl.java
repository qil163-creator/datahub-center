package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.core.convert.Convert;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetDetailRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetColumnRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetFacetRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.MarketConsumerDetailRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketEndpointBindReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketEndpointRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketResourceRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceEndpointDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceCommentMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceEndpointMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketFavoriteMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.DataAssetService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_NOT_EXISTS;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_ASSET_NOT_PUBLISHED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_NOT_AUTHORIZED;

@Service
@Validated
public class MarketResourceServiceImpl implements MarketResourceService {

    @Resource
    private DataAssetService dataAssetService;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private MarketResourceApplyMapper applyMapper;

    @Resource
    private MarketResourceCommentMapper commentMapper;

    @Resource
    private MarketResourceEndpointMapper endpointMapper;

    @Resource
    private MarketSupplyApiGuard supplyApiGuard;

    @Resource
    private MarketAccessService marketAccessService;

    @Resource
    private MarketDeliveryService marketDeliveryService;

    @Resource
    private MarketDisplayHelper displayHelper;

    @Resource
    private MarketObjectGuard objectGuard;

    @Resource
    private MarketFavoriteMapper favoriteMapper;

    /**
     * 已发布资产分页（集市）。
     */
    @Override
    public PageResult<MarketResourceRespVO> getPublishedPage(DataAssetPageReqVO reqVO) {
        reqVO.setStatus(DataAssetDO.STATUS_PUBLISHED);
        PageResult<DataAssetRespVO> page = dataAssetService.getPublishedAssetPage(reqVO);
        return enrichPublishedPage(page);
    }

    /**
     * 读取市场分页。
     */
    @Override
    public PageResult<MarketResourceRespVO> getMarketPage(
            cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketPageReqVO reqVO,
            Long userId, java.util.Collection<Long> authorizedIds, java.util.Collection<Long> marketCategoryIds) {
        return enrichPublishedPage(
                dataAssetService.getPublishedMarketPage(reqVO, userId, authorizedIds, marketCategoryIds));
    }

    /**
     * 补充市场资源的已发布分页。
     */
    private PageResult<MarketResourceRespVO> enrichPublishedPage(PageResult<DataAssetRespVO> page) {
        return enrichPublishedPage(page, SecurityFrameworkUtils.getLoginUserId());
    }

    /**
     * 补充市场资源的已发布分页。
     */
    private PageResult<MarketResourceRespVO> enrichPublishedPage(PageResult<DataAssetRespVO> page, Long userId) {
        List<Long> assetIds = page.getList().stream().map(DataAssetRespVO::getId).toList();
        Map<Long, CommentStat> stats = loadCommentStats(assetIds);
        Map<Long, MarketResourceApplyDO> myApplies = loadMyApplies(assetIds, userId);
        Map<Long, Long> applyCounts = loadApplyCounts(assetIds);
        Map<Long, ResourceAccess> access = loadAccess(assetIds, userId);
        List<MarketResourceRespVO> list = page.getList().stream().map(asset -> {
            MarketResourceRespVO vo = BeanUtils.toBean(asset, MarketResourceRespVO.class);
            CommentStat stat = stats.getOrDefault(asset.getId(), CommentStat.EMPTY);
            vo.setCommentAvg(stat.avg);
            vo.setCommentCount(stat.count);
            vo.setApplyCount(applyCounts.getOrDefault(asset.getId(), 0L).intValue());
            MarketResourceApplyDO mine = myApplies.get(asset.getId());
            if (mine != null) {
                vo.setMyApplyId(mine.getId());
                vo.setMyApplyStatus(mine.getStatus());
            }
            ResourceAccess currentAccess = access.getOrDefault(asset.getId(), ResourceAccess.NONE);
            vo.setAuthorized(currentAccess.authorized());
            vo.setUsable(currentAccess.usable());
            if (vo.getShareAttribute() == null) {
                vo.setShareAttribute(vo.getShareAttr() == null ? DataAssetDO.SHARE_UNKNOWN : vo.getShareAttr());
            }
            return vo;
        }).toList();
        return new PageResult<>(list, page.getTotal());
    }

    /**
     * 已发布资产详情（集市消费视图，不重复计浏览）。
     */
    @Override
    public DataAssetDetailRespVO getPublishedDetail(Long id) {
        DataAssetDO asset = assetMapper.selectById(id);
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        if (!Objects.equals(asset.getStatus(), DataAssetDO.STATUS_PUBLISHED)) {
            throw exception(MARKET_APPLY_ASSET_NOT_PUBLISHED);
        }
        return dataAssetService.getPublishedAssetDetail(id);
    }

    /**
     * 读取消费者详情。
     */
    @Override
    public MarketConsumerDetailRespVO getConsumerDetail(Long id) {
        Long userId = objectGuard.requireUserId();
        DataAssetDetailRespVO detail = getPublishedDetail(id);
        detail.getAsset().setDatasourceHost(null);
        MarketConsumerDetailRespVO vo = new MarketConsumerDetailRespVO();
        vo.setAsset(enrichPublishedPage(new PageResult<>(List.of(detail.getAsset()), 1L), userId)
                .getList().getFirst());
        vo.getAsset().setFavorite(favoriteMapper.selectByUserAndAssets(userId, List.of(id)).stream()
                .anyMatch(item -> Objects.equals(item.getAssetId(), id)));
        displayHelper.fillListings(List.of(vo.getAsset()));
        vo.setColumns(List.of());
        vo.setQualityTasks(List.of());
        if (Boolean.TRUE.equals(vo.getAsset().getUsable())
                && Objects.equals(vo.getAsset().getAssetType(), DataAssetDO.TYPE_DATABASE_TABLE)) {
            // 卡片展示状态不能作为字段授权；沿消费入口重新核验同一用户的冻结批准范围。
            MarketResourceApplyDO apply = marketAccessService.findEffectiveApply(userId, id);
            if (apply == null) {
                throw exception(MARKET_APPLY_NOT_AUTHORIZED);
            }
            MarketQuerySnapshot authorization = marketAccessService.requireQuerySnapshot(userId, apply.getId());
            var columns = detail.getColumns() == null ? List.<DataAssetColumnRespVO>of()
                    : detail.getColumns();
            vo.setColumns(columns.stream().filter(column -> authorization.fieldScope().allows(column.getColumnName()))
                    .toList());
            // 整表质量任务可能包含其他字段的规则与结果，部分字段授权不返回该管理视图。
            if (!columns.isEmpty() && vo.getColumns().size() == columns.size()) {
                vo.setQualityTasks(detail.getQualityTasks() == null ? List.of() : detail.getQualityTasks());
            }
            marketAccessService.assertStillCurrent(authorization);
        }
        return vo;
    }

    /**
     * 集市资产分组统计。
     */
    @Override
    public DataAssetFacetRespVO getPublishedFacets() {
        return dataAssetService.getPublishedAssetFacets();
    }

    /**
     * 绑定资源可交付 API。
     */
    @Override
    public void bindApi(MarketEndpointBindReqVO reqVO) {
        DataAssetDO asset = assetMapper.selectById(reqVO.getAssetId());
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        if (endpointMapper.selectByAssetAndApi(reqVO.getAssetId(), reqVO.getApiId()) != null) {
            return;
        }
        supplyApiGuard.requireMatchesAsset(asset,
                supplyApiGuard.requireQueryableTableApi(reqVO.getApiId()));
        MarketResourceEndpointDO endpoint = new MarketResourceEndpointDO();
        endpoint.setAssetId(reqVO.getAssetId());
        endpoint.setEndpointType(MarketResourceEndpointDO.TYPE_API);
        endpoint.setApiId(reqVO.getApiId());
        endpointMapper.insert(endpoint);
    }

    /**
     * 查询资源已绑定交付端点。
     */
    @Override
    public List<MarketEndpointRespVO> listEndpoints(Long assetId) {
        DataAssetDO asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        return endpointMapper.selectListByAssetId(assetId).stream().map(item -> {
            MarketEndpointRespVO vo = new MarketEndpointRespVO();
            vo.setAssetId(item.getAssetId());
            vo.setApiId(item.getApiId());
            vo.setName("API #" + item.getApiId());
            return vo;
        }).toList();
    }

    /**
     * 加载注释统计。
     */
    private Map<Long, CommentStat> loadCommentStats(List<Long> assetIds) {
        Map<Long, CommentStat> result = new HashMap<>();
        for (Map<String, Object> row : commentMapper.selectStatsByAssetIds(assetIds)) {
            Long assetId = Convert.toLong(row.get("asset_id"));
            Integer count = Convert.toInt(row.get("cnt"), 0);
            BigDecimal avg = Convert.toBigDecimal(row.get("avg_level"), BigDecimal.ZERO)
                    .setScale(1, RoundingMode.HALF_UP);
            result.put(assetId, new CommentStat(avg, count));
        }
        return result;
    }

    /**
     * 加载本人申请。
     */
    private Map<Long, MarketResourceApplyDO> loadMyApplies(List<Long> assetIds, Long userId) {
        if (userId == null || assetIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, MarketResourceApplyDO> result = new HashMap<>();
        for (MarketResourceApplyDO apply : applyMapper.selectLatestByUserAndAssetIds(userId, assetIds)) {
            result.putIfAbsent(apply.getAssetId(), apply);
        }
        return result;
    }

    /** 两个展示标志绑定同一份申请，不能通过两次独立读取拼接不同时点的授权事实。 */
    private Map<Long, ResourceAccess> loadAccess(List<Long> assetIds, Long userId) {
        Map<Long, ResourceAccess> result = new HashMap<>();
        if (userId == null || assetIds.isEmpty()) {
            return result;
        }
        var effective = marketAccessService.findEffectiveApplies(userId, assetIds);
        for (Long assetId : assetIds) {
            MarketResourceApplyDO apply = effective.get(assetId);
            result.put(assetId, apply == null ? ResourceAccess.NONE
                    : new ResourceAccess(true, marketDeliveryService.isConfirmedApply(apply)));
        }
        return result;
    }

    /**
     * 加载申请数量。
     */
    private Map<Long, Long> loadApplyCounts(List<Long> assetIds) {
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> result = new HashMap<>();
        applyMapper.selectList(Wrappers.<MarketResourceApplyDO>lambdaQuery()
                .in(MarketResourceApplyDO::getAssetId, assetIds)
                .ne(MarketResourceApplyDO::getStatus, MarketResourceApplyDO.STATUS_CANCELLED)
        ).forEach(item -> result.merge(item.getAssetId(), 1L, Long::sum));
        return result;
    }

    private record CommentStat(BigDecimal avg, Integer count) {

        private static final CommentStat EMPTY = new CommentStat(BigDecimal.ZERO, 0);
    }

    private record ResourceAccess(boolean authorized, boolean usable) {

        private static final ResourceAccess NONE = new ResourceAccess(false, false);
    }

}
