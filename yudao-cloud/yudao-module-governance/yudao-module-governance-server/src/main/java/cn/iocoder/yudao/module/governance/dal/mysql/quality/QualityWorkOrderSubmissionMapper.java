package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderSubmissionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface QualityWorkOrderSubmissionMapper extends BaseMapperX<QualityWorkOrderSubmissionDO> {

    /**
     * 按工作工单编号查询质量工单提交记录列表。
     */
    default List<QualityWorkOrderSubmissionDO> selectListByWorkOrderId(Long workOrderId) {
        return selectList(new LambdaQueryWrapperX<QualityWorkOrderSubmissionDO>()
                .eq(QualityWorkOrderSubmissionDO::getWorkOrderId, workOrderId)
                .orderByDesc(QualityWorkOrderSubmissionDO::getRevision));
    }

    /**
     * 按请求键查询质量工单提交记录。
     */
    default QualityWorkOrderSubmissionDO selectByRequestKey(String requestKey) {
        return selectOne(new LambdaQueryWrapperX<QualityWorkOrderSubmissionDO>()
                .eq(QualityWorkOrderSubmissionDO::getRequestKey, requestKey)
                .last("LIMIT 1"));
    }

    /**
     * 查询已过期例外。
     */
    default List<QualityWorkOrderSubmissionDO> selectExpiredExceptions(java.time.LocalDateTime now, int limit) {
        return selectExpiredExceptions(now, limit, 0L, Long.MAX_VALUE);
    }

    /**
     * 查询扫描上限编号。
     */
    default long selectScanUpperId() {
        var last = selectOne(new LambdaQueryWrapperX<QualityWorkOrderSubmissionDO>()
                .select(QualityWorkOrderSubmissionDO::getId)
                .orderByDesc(QualityWorkOrderSubmissionDO::getId).last("LIMIT 1"));
        return last == null ? 0L : last.getId();
    }

    /**
     * 查询已过期例外。
     */
    default List<QualityWorkOrderSubmissionDO> selectExpiredExceptions(java.time.LocalDateTime now, int limit,
            long afterId, long throughId) {
        return selectList(new LambdaQueryWrapperX<QualityWorkOrderSubmissionDO>()
                .gt(QualityWorkOrderSubmissionDO::getId, afterId)
                .le(QualityWorkOrderSubmissionDO::getId, throughId)
                .eq(QualityWorkOrderSubmissionDO::getSubmissionType, QualityWorkOrderSubmissionDO.TYPE_EXCEPTION)
                .eq(QualityWorkOrderSubmissionDO::getStatus, QualityWorkOrderSubmissionDO.STATUS_ACTIVE)
                .le(QualityWorkOrderSubmissionDO::getExceptionExpireAt, now)
                // 先排除无效/已处理项再 LIMIT，否则第一页永久占满后续到期单无法推进。
                .exists("SELECT 1 FROM dh_governance_quality_work_order wo "
                        + "WHERE wo.id = dh_governance_quality_work_order_submission.work_order_id "
                        + "AND wo.current_submission_id = dh_governance_quality_work_order_submission.id "
                        + "AND wo.order_status = 'PROCESSED' AND wo.close_type = 'EXCEPTION_ACCEPTED' AND wo.deleted = 0")
                .notExists("SELECT 1 FROM dh_governance_quality_work_order_action a "
                        + "WHERE a.work_order_id = dh_governance_quality_work_order_submission.work_order_id "
                        + "AND a.action_type = 'EXCEPTION_EXPIRED' AND a.deleted = 0")
                .orderByAsc(QualityWorkOrderSubmissionDO::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 50))));
    }

    /**
     * 查询有效复检中。
     */
    default List<QualityWorkOrderSubmissionDO> selectActiveRechecking(int limit) {
        return selectList(new LambdaQueryWrapperX<QualityWorkOrderSubmissionDO>()
                .eq(QualityWorkOrderSubmissionDO::getStatus, QualityWorkOrderSubmissionDO.STATUS_ACTIVE)
                .in(QualityWorkOrderSubmissionDO::getRecheckStatus,
                        QualityWorkOrderSubmissionDO.RECHECK_QUEUED,
                        QualityWorkOrderSubmissionDO.RECHECK_RUNNING)
                .orderByAsc(QualityWorkOrderSubmissionDO::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 50))));
    }

}
