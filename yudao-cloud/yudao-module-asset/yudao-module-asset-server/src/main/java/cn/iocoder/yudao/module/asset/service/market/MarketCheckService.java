package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;

import java.util.List;

public interface MarketCheckService {

    /**
     * 按筛选条件分页查询市场审批工单。
     */
    PageResult<MarketCheckTicketRespVO> getPage(MarketCheckPageReqVO req);

    /**
     * 服务审批只读详情；来源快照不代表当前访问授权。
     */
    MarketCheckDetailRespVO get(String id, String origin);

    /**
     * 可见工单申请人筛选，最多返回五百项；来源人员不作为本地身份。
     */
    List<MarketCheckApplicantRespVO> applicants();

    /**
     * 保存审批意见；来源工单不授予真实数据权限，本地申请复用原审批链。
     */
    void check(MarketCheckDecisionReqVO req);
}
