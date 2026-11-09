package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyPageReqVO;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderNotifyDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.UUID;

@Mapper
public interface QualityWorkOrderNotifyMapper extends BaseMapperX<QualityWorkOrderNotifyDO> {

    /**
     * 按工作工单查询质量工单通知分页。
     */
    default PageResult<QualityWorkOrderNotifyDO> selectPageByWorkOrder(QualityWorkOrderNotifyPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityWorkOrderNotifyDO>()
                .eq(QualityWorkOrderNotifyDO::getWorkOrderId, reqVO.getWorkOrderId())
                .orderByDesc(QualityWorkOrderNotifyDO::getId));
    }

    /**
     * 按指定条件读取并锁定质量工单通知记录，供事务内一致性校验或修改使用。
     */
    @Select("SELECT * FROM dh_governance_quality_work_order_notify"
            + " WHERE id=#{id} AND deleted=0 FOR UPDATE")
    QualityWorkOrderNotifyDO selectForUpdate(@Param("id") Long id);

    /**
     * 按业务条件更新质量工单通知的resolve未知状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_quality_work_order_notify SET resolution_status=#{resolutionStatus},"
            + "resolution_version=#{newVersion},resolution_reason=#{reason},operator_message_id=#{operatorMessageId},"
            + "resolved_by=#{actorUserId},resolved_at=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai'),"
            + "updater=#{actorUserId},update_time=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')"
            + " WHERE id=#{id} AND deleted=0 AND send_status IN ('UNKNOWN','EXHAUSTED')"
            + " AND COALESCE(resolution_version,0)=#{expectedVersion} AND COALESCE(resolution_status,'UNRESOLVED')='UNRESOLVED'")
    int resolveUnknown(@Param("id") Long id,
            @Param("expectedVersion") int expectedVersion, @Param("newVersion") int newVersion,
            @Param("resolutionStatus") String resolutionStatus, @Param("reason") String reason,
            @Param("operatorMessageId") Long operatorMessageId, @Param("actorUserId") Long actorUserId);

    /**
     * 按业务条件更新质量工单通知的授权重试状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_quality_work_order_notify SET resolution_status='RETRY_AUTHORIZED',"
            + "manual_retry_count=COALESCE(manual_retry_count,0)+1,"
            + "resolution_version=#{newVersion},resolution_reason=#{reason},resolved_by=#{actorUserId},"
            + "resolved_at=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai'),send_status='FAILED',retry_count=0,"
            + "next_retry_time=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai'),last_error='人工授权重试',"
            + "updater=#{actorUserId},update_time=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')"
            + " WHERE id=#{id} AND deleted=0 AND send_status IN ('UNKNOWN','EXHAUSTED')"
            + " AND resolution_status='MANUAL_NOT_DELIVERED' AND resolution_version=#{expectedVersion}"
            + " AND COALESCE(manual_retry_count,0)<8")
    int authorizeRetry(@Param("id") Long id,
            @Param("expectedVersion") int expectedVersion, @Param("newVersion") int newVersion,
            @Param("reason") String reason, @Param("actorUserId") Long actorUserId);

    /**
     * 查询去重。
     */
    default QualityWorkOrderNotifyDO selectDedup(Long workOrderId, String eventType,
            Long receiverUserId, String timeSlice) {
        return selectOne(new LambdaQueryWrapperX<QualityWorkOrderNotifyDO>()
                .eq(QualityWorkOrderNotifyDO::getWorkOrderId, workOrderId)
                .eq(QualityWorkOrderNotifyDO::getEventType, eventType)
                .eq(QualityWorkOrderNotifyDO::getReceiverUserId, receiverUserId)
                .eq(QualityWorkOrderNotifyDO::getTimeSlice, timeSlice)
                .last("LIMIT 1"));
    }

    /**
     * 领取可重试。
     */
    @Select("WITH picked AS ("
            + " SELECT id FROM dh_governance_quality_work_order_notify"
            + " WHERE deleted=0"
            // 通知服务按幂等键去重，结果未知（UNKNOWN）也可用同一键安全重试；人工已核验的 UNKNOWN 不再自动领取
            + " AND (send_status IN ('PENDING','FAILED')"
            + " OR (send_status='UNKNOWN' AND COALESCE(resolution_status,'UNRESOLVED')='UNRESOLVED'))"
            + " AND next_retry_time<=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai') AND retry_count<8"
            + " ORDER BY id FOR UPDATE SKIP LOCKED LIMIT #{limit}"
            + ") UPDATE dh_governance_quality_work_order_notify AS n"
            + " SET send_status='SENDING',claim_token=#{claimToken},resolution_status='UNRESOLVED',"
            + " resolution_reason=NULL,operator_message_id=NULL,resolved_by=NULL,resolved_at=NULL,"
            + " lease_until=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')+make_interval(secs=>#{leaseSeconds}),"
            + " updater='system',update_time=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')"
            + " FROM picked WHERE n.id=picked.id AND n.deleted=0"
            + " RETURNING n.*")
    List<QualityWorkOrderNotifyDO> claimRetryable(
            @Param("limit") int limit,
            @Param("claimToken") String claimToken,
            @Param("leaseSeconds") long leaseSeconds);

    /**
     * 领取可重试。
     */
    default List<QualityWorkOrderNotifyDO> claimRetryable(int limit, long leaseSeconds) {

        return claimRetryable(Math.max(1, Math.min(limit, 50)), UUID.randomUUID().toString(), leaseSeconds);
    }

    /**
     * 按业务条件更新质量工单通知的mark已过期领取未知状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_quality_work_order_notify"
            + " SET send_status=CASE WHEN retry_count+1>=8 THEN 'EXHAUSTED' ELSE 'UNKNOWN' END,"
            + " retry_count=retry_count+1,"
            + " next_retry_time=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')+interval '5 minutes',"
            + " claim_token=NULL,lease_until=NULL,last_error='投递租约过期，结果未知，将按幂等键重试',"
            + " update_time=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai'),updater='system'"
            + " WHERE deleted=0 AND send_status='SENDING'"
            + " AND lease_until<=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')")
    int markExpiredClaimsUnknown();

    /**
     * 按业务条件更新质量工单通知的结束领取状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_quality_work_order_notify"
            + " SET send_status=CASE WHEN #{status} IN ('FAILED','UNKNOWN') AND retry_count+1>=8"
            + " THEN 'EXHAUSTED' ELSE #{status} END,claim_token=NULL,lease_until=NULL,"
            + " retry_count=retry_count+CASE WHEN #{status} IN ('FAILED','UNKNOWN') THEN 1 ELSE 0 END,"
            + " next_retry_time=CASE WHEN #{status} IN ('FAILED','UNKNOWN')"
            + " THEN (clock_timestamp() AT TIME ZONE 'Asia/Shanghai')+make_interval(secs=>#{retryDelaySeconds}) ELSE next_retry_time END,"
            + " last_error=#{lastError},update_time=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai'),updater='system'"
            + " WHERE id=#{id} AND deleted=0"
            + " AND send_status='SENDING' AND claim_token=#{claimToken}")
    int finishClaim(@Param("id") Long id,
            @Param("claimToken") String claimToken, @Param("status") String status,
            @Param("retryDelaySeconds") long retryDelaySeconds, @Param("lastError") String lastError);

}
