package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityExpiryFailurePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityExceptionExpiryFailureDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface QualityExceptionExpiryFailureMapper extends BaseMapperX<QualityExceptionExpiryFailureDO> {

    /**
     * 记录一次逐单失败：首次插入，再次失败累加次数并按 5 分钟 × 2^(n-1)（封顶 6 小时）退避；
     * 达到最大次数转为 EXHAUSTED，不再被自动扫描选中。已关闭（RESOLVED）的旧账本重新开始计数。
     */
    @Insert("INSERT INTO dh_governance_quality_expiry_failure AS f(submission_id,work_order_id,status,attempt_count,"
            + "last_error,first_failed_at,last_failed_at,next_attempt_at,version,creator,updater)"
            + " VALUES(#{submissionId},#{workOrderId},CASE WHEN 1>=#{maxAttempts} THEN 'EXHAUSTED' ELSE 'RETRYING' END,1,"
            + "#{lastError},#{now},#{now},#{now}+interval '5 minutes',0,'system','system')"
            + " ON CONFLICT (submission_id) WHERE deleted=0 DO UPDATE SET"
            + " attempt_count=CASE WHEN f.status='RESOLVED' THEN 1 ELSE f.attempt_count+1 END,"
            + " status=CASE WHEN (CASE WHEN f.status='RESOLVED' THEN 1 ELSE f.attempt_count+1 END)>=#{maxAttempts}"
            + " THEN 'EXHAUSTED' ELSE 'RETRYING' END,"
            + " first_failed_at=CASE WHEN f.status='RESOLVED' THEN EXCLUDED.first_failed_at ELSE f.first_failed_at END,"
            + " next_attempt_at=#{now}+make_interval(mins=>LEAST(360,5*power(2,LEAST("
            + "(CASE WHEN f.status='RESOLVED' THEN 1 ELSE f.attempt_count+1 END)-1,10))::int)),"
            + " last_error=EXCLUDED.last_error,"
            + " last_failed_at=EXCLUDED.last_failed_at,resolution_action=NULL,resolution_reason=NULL,"
            + " resolved_by=NULL,resolved_at=NULL,version=f.version+1,updater='system',update_time=CURRENT_TIMESTAMP")
    int recordFailure(@Param("submissionId") Long submissionId, @Param("workOrderId") Long workOrderId,
            @Param("lastError") String lastError,
            @Param("now") LocalDateTime now, @Param("maxAttempts") int maxAttempts);

    /** 逐单处理成功（或已不再满足到期条件）后关闭未关闭的账本；没有账本时不产生写入 */
    @Update("UPDATE dh_governance_quality_expiry_failure SET status='RESOLVED',resolution_action='SUCCEEDED',"
            + "resolved_at=#{now},version=version+1,updater='system',update_time=CURRENT_TIMESTAMP"
            + " WHERE submission_id=#{submissionId} AND deleted=0 AND status<>'RESOLVED'")
    int markSucceeded(@Param("submissionId") Long submissionId, @Param("now") LocalDateTime now);

    /** 人工授权重试：按读取时版本 CAS，清零自动次数并立即到期，由下一轮扫描处理 */
    @Update("UPDATE dh_governance_quality_expiry_failure SET status='RETRYING',attempt_count=0,next_attempt_at=#{now},"
            + "resolution_action='RETRY_AUTHORIZED',resolution_reason=#{reason},resolved_by=#{actorUserId},"
            + "resolved_at=#{now},version=version+1,updater=#{actorUserId},update_time=CURRENT_TIMESTAMP"
            + " WHERE id=#{id} AND deleted=0 AND version=#{expectedVersion} AND status IN ('RETRYING','EXHAUSTED')")
    int authorizeRetry(@Param("id") Long id, @Param("expectedVersion") int expectedVersion,
            @Param("reason") String reason, @Param("actorUserId") Long actorUserId, @Param("now") LocalDateTime now);

    default PageResult<QualityExceptionExpiryFailureDO> selectPage(QualityExpiryFailurePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityExceptionExpiryFailureDO>()
                .eqIfPresent(QualityExceptionExpiryFailureDO::getStatus, reqVO.getStatus())
                .eqIfPresent(QualityExceptionExpiryFailureDO::getWorkOrderId, reqVO.getWorkOrderId())
                .orderByDesc(QualityExceptionExpiryFailureDO::getLastFailedAt)
                .orderByDesc(QualityExceptionExpiryFailureDO::getId));
    }
}
