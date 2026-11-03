package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketFavoriteReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketResourceRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketStatisticsRespVO;

public interface MarketMartService {

    /**
     * 检索已发布资产，收藏与授权均绑定当前登录用户。
     */
    PageResult<MarketResourceRespVO> getPage(MarketPageReqVO reqVO);

    /**
     * 已发布总数及当前用户授权、收藏、在途申请资产数。
     */
    MarketStatisticsRespVO getStatistics();

    /**
     * 设置当前用户收藏状态，重复设置保持幂等。
     */
    void setFavorite(MarketFavoriteReqVO reqVO);

    /**
     * 保存市场展示配置并沿资产目录原状态机发布。
     */
    void publish(cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketPublishReqVO reqVO);

    /**
     * 读取市场展示信息供发布表单回显，不修改发布状态。
     */
    cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketListingRespVO getListing(Long assetId);

    /**
     * 启用的顶级市场专题及其已发布资产数。
     */
    java.util.List<cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketTopicRespVO> getTopics();
}
