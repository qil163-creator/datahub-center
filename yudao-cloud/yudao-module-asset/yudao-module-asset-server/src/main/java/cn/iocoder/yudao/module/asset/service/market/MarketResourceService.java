package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetDetailRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.MarketConsumerDetailRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetFacetRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketEndpointBindReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketEndpointRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketResourceRespVO;

import java.util.List;

public interface MarketResourceService {

    /**
     * 已发布资产分页（集市）。
     */
    PageResult<MarketResourceRespVO> getPublishedPage(DataAssetPageReqVO reqVO);

    /**
     * 读取市场分页。
     */
    PageResult<MarketResourceRespVO> getMarketPage(
            cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketPageReqVO reqVO,
            Long userId, java.util.Collection<Long> authorizedIds, java.util.Collection<Long> marketCategoryIds);

    /**
     * 已发布资产详情（集市消费视图，不重复计浏览）。
     */
    DataAssetDetailRespVO getPublishedDetail(Long id);

    /**
     * 读取消费者详情。
     */
    MarketConsumerDetailRespVO getConsumerDetail(Long id);

    /**
     * 集市资产分组统计。
     */
    DataAssetFacetRespVO getPublishedFacets();

    /**
     * 绑定资源可交付 API。
     */
    void bindApi(MarketEndpointBindReqVO reqVO);

    /**
     * 查询资源已绑定交付端点。
     */
    List<MarketEndpointRespVO> listEndpoints(Long assetId);

}
