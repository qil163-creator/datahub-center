package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillMergeBatchPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillMergeBatchRespVO;

/**
 * 在线填报-合并服务接口（审批通过快照 → 正式目标表）
 *
 * 幂等定义：合并批次以 UNIQUE(submissionId) 为唯一幂等键；
 * 执行前按批次清理/覆盖自身范围，重复执行收敛到同一结果；
 * 合并失败不改变 BPM 已通过事实，重试不要求重新审批
 *
 * @author Li
 */
public interface FillMergeService {

    /**
     * 为审批通过的提交创建合并批次（重复调用返回既有批次）
     */
    Long createBatchForSubmission(Long submissionId);

    /**
     * 执行合并批次（内部先做执行抢占 CAS，Job 与人工触发均安全）
     */
    void executeBatch(Long batchId);

    /**
     * 人工重试失败批次：置回待执行，由合并 Job 拾取
     */
    void retryBatch(Long batchId);

    /**
     * 把合并失败的批次退回整改：放弃本次合并，组织实例回到“整改中”，由填报人修改后重新提交审批。
     * 用于目标表约束冲突等重试无法解决的数据问题；正式表保持合并前的内容。
     *
     * @param batchId 批次编号
     * @param reason 退回原因，随通知发给填报人
     */
    void returnToRework(Long batchId, String reason);

    /**
     * 获得合并批次分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 合并批次分页结果
     */
    PageResult<FillMergeBatchRespVO> getBatchPage(FillMergeBatchPageReqVO pageReqVO);

    /**
     * 获得合并批次。
     *
     * @param id 记录编号
     * @return 填报合并批次响应数据
     */
    FillMergeBatchRespVO getBatch(Long id);

}
