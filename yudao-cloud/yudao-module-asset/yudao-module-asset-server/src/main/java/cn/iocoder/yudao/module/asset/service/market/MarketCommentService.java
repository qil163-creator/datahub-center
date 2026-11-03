package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentReplyReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentSaveReqVO;

public interface MarketCommentService {

    /**
     * 发表评价。
     */
    Long createComment(MarketCommentSaveReqVO reqVO);

    /**
     * 修改自己的评价。
     */
    void updateComment(MarketCommentSaveReqVO reqVO);

    /**
     * 删除自己的评价。
     */
    void deleteComment(Long id);

    /**
     * 资产方回复评价。
     */
    void replyComment(MarketCommentReplyReqVO reqVO);

    /**
     * 评价分页。
     */
    PageResult<MarketCommentRespVO> getCommentPage(MarketCommentPageReqVO reqVO);

}
