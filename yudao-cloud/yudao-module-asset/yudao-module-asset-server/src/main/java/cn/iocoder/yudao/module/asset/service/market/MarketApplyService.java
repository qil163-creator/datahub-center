package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyApplicationRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplySaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketEligibleApiRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketEndpointRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryOperationRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryRecheckReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryRedeliverReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyAppRespDTO;

import java.util.List;

public interface MarketApplyService {

    /** 仅由已持久化停供意图驱动的内部逐申请撤权。 */
    void revokeForAssetStop(Long applyId);

    /**
     * 提交资源申请。
     */
    Long createApply(MarketApplySaveReqVO reqVO);

    /**
     * 修改草稿申请。
     */
    void updateApply(MarketApplySaveReqVO reqVO);

    /**
     * 提交申请。
     */
    void submitApply(Long id);

    /** expectedRevision 为客户端所见内容版本；为空时与 {@link #submitApply(Long)} 相同。 */
    void submitApply(Long id, Integer expectedRevision);

    /**
     * 删除草稿/驳回/已撤销申请。
     */
    void deleteApply(Long id);

    /**
     * 取消申请。
     */
    void cancelApply(Long id);

    /**
     * 撤销自己的申请。
     */
    void cancelApply(Long id, Integer expectedRevision);

    /**
     * 审批申请。
     */
    void checkApply(MarketCheckReqVO reqVO);

    /**
     * 切换已通过市场申请的开通或取消授权状态。
     */
    void authApply(Long id, Integer auth);

    /** expectedSourceRevision 为客户端所见交付来源版本；为空时与 {@link #authApply(Long, Integer)} 相同。 */
    void authApply(Long id, Integer auth, Long expectedSourceRevision);

    /**
     * 撤回申请。
     */
    void revokeApply(Long id, String note);

    /**
     * 收回已通过的授权。
     */
    void revokeApply(Long id, String note, Long expectedSourceRevision);

    /**
     * 历史交付处置的收回：只能加入调用方已锁定并复验证据的事务，不再做权限 RPC；
     * 复用与普通收回相同的状态 CAS、资产复验、过程、冻结收回命令及提交后通知。
     */
    void revokeForDisposal(Long id, String note);

    /**
     * 扫描已到期的市场申请并撤销相应的有效授权。
     */
    int expireDueApplies();

    /**
     * 申请详情（含审批轨迹）。
     */
    MarketApplyRespVO getApply(Long id);

    /**
     * 申请分页。
     */
    PageResult<MarketApplyRespVO> getApplyPage(MarketApplyPageReqVO reqVO);

    /** 仅查询当前登录人的本地申请。 */
    PageResult<MarketMyApplicationRespVO> getMyApplications(MarketApplyPageReqVO reqVO);

    /** 仅查看当前登录人的本地申请详情。 */
    MarketApplyRespVO getMyApplication(Long id);

    /** 内部需求确认编排：复用时返回所属申请锁内的当前行，锁随调用方事务提交/回滚释放。 */
    MarketResourceApplyDO createApplyFromDemand(Long demandId, Long assetId, Long applyUserId,
            Long applyDeptId, String purpose);

    /**
     * 查询可用应用。
     */
    List<SupplyAppRespDTO> listAvailableApps();

    /**
     * 查询可用API。
     */
    List<MarketEndpointRespVO> listAvailableApis(Long assetId);

    /**
     * 当前资产可证明关联的已绑定 API。
     */
    List<MarketEligibleApiRespVO> listEligibleApis(Long assetId);

    /**
     * 请求补正。
     */
    void requestSupplement(Long id, String note);

    /**
     * 请求补正。
     */
    void requestSupplement(Long id, String note, Integer expectedRevision);

    /**
     * 重新提交申请。
     */
    void resubmitApply(Long id);

    /**
     * 补正后重新提交，生成新修订快照。
     */
    void resubmitApply(Long id, Integer expectedRevision);

    /**
     * 旧的原地续期入口：新协议下不再延长原批准，调用会返回明确错误，请改用 {@link #createRenewal}。
     */
    void renewApply(Long id, java.time.LocalDateTime applyEndTime);

    /**
     * 创建续期申请：关联原申请的新申请，复制原批准证据并重新走审批。
     * 同一 operationId 的重复提交返回同一条续期申请；原申请的期限与来源保持不变。
     * 独立 READ_COMMITTED 事务提交，不参与调用方外层事务；必须通过 Spring 代理调用。
     * 幂等比较首次请求截止时间（微秒精度），缺少历史证据或复用身份时明确拒绝。
     *
     * @return 新申请编号
     */
    Long createRenewal(cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketRenewalReqVO reqVO);

    /**
     * 暂停申请。
     */
    void pauseApply(Long id, String note);

    /**
     * 暂停申请。
     */
    void pauseApply(Long id, String note, Long expectedSourceRevision);

    /**
     * 恢复申请。
     */
    void resumeApply(Long id);

    /**
     * 恢复已暂停授权。
     */
    void resumeApply(Long id, Long expectedSourceRevision);

    /**
     * 重试待执行或失败的交付操作。
     */
    int retryDeliveries();

    /**
     * 查看原交付操作状态（仅授权提供方）。
     */
    MarketDeliveryOperationRespVO getDeliveryOperation(String operationId);

    /**
     * 人工按原操作身份核查未知结果；不重发交付命令。
     */
    MarketDeliveryOperationRespVO recheckDelivery(MarketDeliveryRecheckReqVO reqVO);

    /**
     * 受控重投：仅限冻结命令完整匹配且动作是撤销/暂停的 MANUAL/UNKNOWN 原操作，按原 operationId 重发一次；
     * 必须绑定核查决定，GRANT 一律拒绝。
     */
    MarketDeliveryOperationRespVO redeliverDelivery(MarketDeliveryRedeliverReqVO reqVO);

}
