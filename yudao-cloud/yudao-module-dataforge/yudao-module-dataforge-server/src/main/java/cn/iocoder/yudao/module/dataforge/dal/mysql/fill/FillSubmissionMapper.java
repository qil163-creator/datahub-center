package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 在线填报-提交记录 Mapper（快照头不可变，只允许更新审批状态镜像字段）
 *
 * @author Li
 */
@Mapper
public interface FillSubmissionMapper extends BaseMapperX<FillSubmissionDO> {

    /**
     * 按流程实例编号查询填报提交。
     *
     * @param processInstanceId 流程实例编号
     * @return 填报提交
     */
    default FillSubmissionDO selectByProcessInstanceId(String processInstanceId) {
        return selectOne(FillSubmissionDO::getProcessInstanceId, processInstanceId);
    }

    /**
     * 按工作项编号查询填报提交列表。
     *
     * @param workItemId 工作项编号
     * @return 按工作项编号查询填报提交列表结果集合
     */
    default List<FillSubmissionDO> selectListByWorkItemId(Long workItemId) {
        return selectList(new LambdaQueryWrapperX<FillSubmissionDO>()
                .eq(FillSubmissionDO::getWorkItemId, workItemId)
                .orderByDesc(FillSubmissionDO::getAttemptNo));
    }

    /**
     * 按工作项编号查询最新。
     *
     * @param workItemId 工作项编号
     * @return 填报提交
     */
    default FillSubmissionDO selectLatestByWorkItemId(Long workItemId) {
        return selectOne(new LambdaQueryWrapperX<FillSubmissionDO>()
                .eq(FillSubmissionDO::getWorkItemId, workItemId)
                .orderByDesc(FillSubmissionDO::getAttemptNo)
                .last("LIMIT 1"));
    }

    /** 对账扫描：长时间停留在审批中的提交（BPM 回调可能丢失） */
    default List<FillSubmissionDO> selectApprovingBefore(java.time.LocalDateTime submitBefore, int limit) {
        return selectList(new LambdaQueryWrapperX<FillSubmissionDO>()
                .eq(FillSubmissionDO::getApprovalStatus, 1)
                .isNotNull(FillSubmissionDO::getProcessInstanceId)
                .lt(FillSubmissionDO::getSubmitTime, submitBefore)
                .orderByAsc(FillSubmissionDO::getSubmitTime)
                .last("LIMIT " + limit));
    }

    /**
     * 上级单位审核结论（CAS：只处理仍在等待上级审核的提交）。
     *
     * @param id 提交编号
     * @param approvalStatus 审核结论（审批通过 / 驳回）
     * @param reviewerUserId 审核人
     * @param reason 审核意见
     * @param endTime 审核时间
     * @return 更新行数
     */
    default int markReviewed(Long id, Integer approvalStatus, Long reviewerUserId, String reason,
                             java.time.LocalDateTime endTime) {
        return update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FillSubmissionDO>()
                .set(FillSubmissionDO::getApprovalStatus, approvalStatus)
                .set(FillSubmissionDO::getReviewerUserId, reviewerUserId)
                .set(FillSubmissionDO::getApprovalReason, reason)
                .set(FillSubmissionDO::getApprovalEndTime, endTime)
                .eq(FillSubmissionDO::getId, id)
                .eq(FillSubmissionDO::getReviewMode, FillSubmissionDO.REVIEW_PARENT)
                .eq(FillSubmissionDO::getApprovalStatus,
                        cn.iocoder.yudao.module.dataforge.enums.fill.FillApprovalStatusEnum.APPROVING.getStatus()));
    }

}
