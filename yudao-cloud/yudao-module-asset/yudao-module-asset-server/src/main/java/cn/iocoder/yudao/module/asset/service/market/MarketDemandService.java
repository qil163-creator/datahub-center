package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;

import java.util.List;

public interface MarketDemandService {

    /**
     * 创建数据需求。
     */
    Long createDemand(MarketDemandSaveReqVO reqVO);

    /**
     * 修改数据需求。
     */
    void updateDemand(MarketDemandSaveReqVO reqVO);

    /**
     * 删除草稿需求。
     */
    void deleteDemand(Long id);

    /**
     * 发布到需求大厅。
     */
    void publishDemand(Long id);

    /**
     * 关闭、撤销或驳回需求。
     */
    void closeDemand(MarketDemandCloseReqVO reqVO);

    /**
     * 需求详情。
     */
    MarketDemandRespVO getDemand(Long id);

    /**
     * 需求分页。
     */
    PageResult<MarketDemandRespVO> getDemandPage(MarketDemandPageReqVO reqVO);

    /**
     * 响应需求。
     */
    Long replyDemand(MarketDemandReplySaveReqVO reqVO);

    /**
     * 把已有资产匹配到需求。
     */
    Long matchDemand(MarketDemandMatchSaveReqVO reqVO);

    /**
     * 需求方确认匹配。
     */
    void confirmMatch(MarketDemandMatchConfirmReqVO reqVO);

    /**
     * 验收已交付需求，已匹配不等于已解决。
     */
    void acceptDemand(Long id);

    /**
     * 按需求推荐已发布资产。
     */
    List<MarketResourceRespVO> suggestAssets(Long demandId);

}
