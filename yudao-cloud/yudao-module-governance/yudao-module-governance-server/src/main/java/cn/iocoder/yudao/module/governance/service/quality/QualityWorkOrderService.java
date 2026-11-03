package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderCommandReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderStatisticsRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyReviewReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyRetryReqVO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderReplayDescribeReqDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderReplayDescribeRespDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderRecheckAttachReqDTO;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

public interface QualityWorkOrderService {

    /**
     * 创建问题工单。
     */
    Long createWorkOrder(QualityWorkOrderSaveReqVO createReqVO);

    /**
     * 创建内联ETL工作工单。
     */
    Long createInlineEtlWorkOrder(Long actorUserId, Long reportId, String orderName,
            String description, LocalDateTime deadline);

    /**
     * 读取可见内联报告工作工单编号。
     */
    Long getVisibleInlineReportWorkOrderId(Long reportId, Long userId);

    /**
     * 描述ETL工作工单回放。
     */
    QualityEtlWorkOrderReplayDescribeRespDTO describeEtlWorkOrderReplay(
            QualityEtlWorkOrderReplayDescribeReqDTO request);

    /**
     * 将已提交 ETL 回放批次与质量工单复检关联，并处理重复请求。
     */
    Long attachEtlWorkOrderRecheck(QualityEtlWorkOrderRecheckAttachReqDTO request);

    /**
     * 更新问题工单。
     */
    void updateWorkOrder(QualityWorkOrderSaveReqVO updateReqVO);

    /**
     * 删除问题工单。
     */
    void deleteWorkOrder(Long id);

    /**
     * 获得问题工单。
     */
    QualityWorkOrderRespVO getWorkOrder(Long id);

    /**
     * 获得问题工单分页。
     */
    PageResult<QualityWorkOrderRespVO> getWorkOrderPage(QualityWorkOrderPageReqVO pageReqVO);

    /**
     * 获得我的待办工单列表。
     */
    List<QualityWorkOrderRespVO> getMyTodoList();

    /**
     * 将质量工单分派给指定处理人，并记录分派信息。
     */
    QualityWorkOrderRespVO assign(QualityWorkOrderCommandReqVO reqVO);

    /**
     * 领取待处理的质量工单任务或记录。
     */
    QualityWorkOrderRespVO claim(QualityWorkOrderCommandReqVO reqVO);

    /**
     * 将质量工单转交给指定处理人，并保留操作轨迹。
     */
    QualityWorkOrderRespVO transfer(QualityWorkOrderCommandReqVO reqVO);

    /**
     * 提交处理结果。
     */
    QualityWorkOrderRespVO submitResolution(QualityWorkOrderCommandReqVO reqVO);

    /**
     * 受理质量工单复检请求并关联本次提交记录。
     */
    QualityWorkOrderRespVO recheck(QualityWorkOrderCommandReqVO reqVO);

    /**
     * 校验质量工单处理证据后记录确认结论。
     */
    QualityWorkOrderRespVO confirm(QualityWorkOrderCommandReqVO reqVO);

    /**
     * 撤销质量工单当前提交或处理状态。
     */
    QualityWorkOrderRespVO withdraw(QualityWorkOrderCommandReqVO reqVO);

    /**
     * 延长截止时间。
     */
    QualityWorkOrderRespVO extendDeadline(QualityWorkOrderCommandReqVO reqVO);

    /**
     * 重新打开已结束的质量工单。
     */
    QualityWorkOrderRespVO reopen(QualityWorkOrderCommandReqVO reqVO);

    /**
     * 记录质量工单协调信息并更新相关处理安排。
     */
    QualityWorkOrderRespVO coordinate(QualityWorkOrderCommandReqVO reqVO);

    /**
     * 读取质量工单历史。
     */
    List<QualityWorkOrderRespVO.QualityWorkOrderActionRespVO> getHistory(Long id);

    /**
     * 读取通知分页。
     */
    PageResult<QualityWorkOrderNotifyRespVO> getNotifyPage(QualityWorkOrderNotifyPageReqVO reqVO);

    /**
     * 读取通知详情。
     */
    QualityWorkOrderNotifyRespVO getNotifyDetail(Long id);

    /**
     * 人工核验结果未知的通知。
     */
    QualityWorkOrderNotifyRespVO reviewNotify(QualityWorkOrderNotifyReviewReqVO reqVO);

    /**
     * 基于人工未送达结论显式授权重试。
     */
    QualityWorkOrderNotifyRespVO authorizeNotifyRetry(QualityWorkOrderNotifyRetryReqVO reqVO);

    /**
     * 读取原始问题。
     */
    Map<String, Object> getRawIssues(Long id);

    /**
     * 获得当前用户可见工单的趋势、状态与责任人统计。
     */
    QualityWorkOrderStatisticsRespVO getWorkOrderStatistics(Integer days);

    /**
     * 扫描待评估工单并核验复检结果，推进可确认的处理状态。
     */
    void evaluateRechecks();

    /**
     * 派发到期通知。
     */
    void dispatchDueNotifies();

}
