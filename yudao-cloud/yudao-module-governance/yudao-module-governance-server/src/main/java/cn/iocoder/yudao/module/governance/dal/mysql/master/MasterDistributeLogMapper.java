package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeLogPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeLogDO;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 主数据派发执行记录 Mapper
 *
 * @author Li
 */
@Mapper
public interface MasterDistributeLogMapper extends BaseMapperX<MasterDistributeLogDO> {

    /**
     * 汇总指定分发任务的运行结果。
     */
    @Select("<script>SELECT DISTINCT ON(distribute_id) distribute_id,run_status,start_time,"
            + "SUM(total_count) OVER(PARTITION BY distribute_id) AS total_count,"
            + "SUM(success_count) OVER(PARTITION BY distribute_id) AS success_count,"
            + "SUM(failure_count) OVER(PARTITION BY distribute_id) AS failure_count,"
            + "SUM(unknown_count) OVER(PARTITION BY distribute_id) AS unknown_count "
            + "FROM dh_governance_master_distribute_log WHERE deleted=0 AND distribute_id IN "
            + "<foreach collection='tasks' item='task' open='(' separator=',' close=')'>#{task}</foreach> ORDER BY distribute_id,id DESC</script>")
    List<MasterDistributeLogDO> summaries(@Param("tasks") java.util.Collection<Long> tasks);

    /**
     * 领取待处理的主数据分发日志任务或记录。
     */
    default int claim(Long id, String token) {
        return claim(id, token, 5);
    }

    /**
     * 将无法继续自动重试的分发运行转入耗尽状态。
     */
    default int exhaust(Long id) {
        return exhaust(id, 5, "发送前领取次数耗尽，未执行发送");
    }

    /**
     * 读取主数据分发日志最新记录。
     */
    @Select("SELECT * FROM dh_governance_master_distribute_log WHERE distribute_id=#{task} AND deleted=0 ORDER BY id DESC LIMIT 1")
    MasterDistributeLogDO latest(@Param("task") Long task);

    /**
     * 按业务条件更新主数据分发日志的进度状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_log SET total_count=#{r.totalCount},success_count=#{r.successCount},"
            + "failure_count=#{r.failureCount},unknown_count=#{r.unknownCount},data_count=#{r.dataCount},byte_count=#{r.byteCount},"
            + "update_time=clock_timestamp() WHERE id=#{r.id} AND distribute_id=#{r.distributeId} AND deleted=0 "
            + "AND run_protocol=1 AND run_status=2 AND lease_token=#{r.leaseToken} AND lease_until>clock_timestamp()")
    int progress(@Param("r") MasterDistributeLogDO run);

    /**
     * 按业务条件更新主数据分发日志的重试变更前发送状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_log SET lease_token=NULL,lease_until=NULL,"
            + "available_at=clock_timestamp()+make_interval(mins => #{minutes}),error_info=#{error},update_time=clock_timestamp() "
            + "WHERE id=#{r.id} AND distribute_id=#{r.distributeId} AND deleted=0 AND run_status=1 "
            + "AND lease_token=#{r.leaseToken} AND lease_until>clock_timestamp()")
    int retryBeforeSend(@Param("r") MasterDistributeLogDO run, @Param("minutes") int minutes,
            @Param("error") String error);

    /**
     * 判断主数据分发日志状态是否阻止后续运行。
     */
    @Select("SELECT COALESCE(MAX(run_status),0) FROM dh_governance_master_distribute_log "
            + "WHERE distribute_id=#{task} AND deleted=0 AND run_status IN (1,2,5)")
    int blockingState(@Param("task") Long task);

    /**
     * 读取数据库当前时间，用于分发租约与调度计算。
     */
    @Select("SELECT clock_timestamp()::timestamp")
    LocalDateTime databaseNow();

    /**
     * 查询满足恢复条件的未完成分发运行。
     */
    @Select("SELECT * FROM dh_governance_master_distribute_log WHERE deleted=0 AND run_protocol=1 "
            + "AND ((run_status=1 AND available_at<=clock_timestamp() AND (lease_until IS NULL OR lease_until<=clock_timestamp())) "
            + "OR (run_status=2 AND lease_until<=clock_timestamp())) ORDER BY available_at,id LIMIT 100")
    List<MasterDistributeLogDO> recoverable();

    /**
     * 按业务条件更新主数据分发日志的恢复未知状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_log SET run_status=5,end_time=clock_timestamp(),update_time=clock_timestamp(), "
            + "error_info='发送后租约到期，外部结果未知，禁止直接重跑' WHERE id=#{id} "
            + "AND deleted=0 AND run_protocol=1 AND run_status=2 AND lease_until<=clock_timestamp()")
    int recoverUnknown(@Param("id") Long id);

    /**
     * 将无法继续自动重试的分发运行转入耗尽状态。
     */
    @Update("UPDATE dh_governance_master_distribute_log SET run_status=4,end_time=clock_timestamp(),update_time=clock_timestamp(), "
            + "error_info=#{reason} WHERE id=#{id} AND deleted=0 "
            + "AND run_protocol=1 AND run_status=1 AND attempts>=#{maximum} AND available_at<=clock_timestamp() "
            + "AND (lease_until IS NULL OR lease_until<=clock_timestamp())")
    int exhaust(@Param("id") Long id, @Param("maximum") int maximum, @Param("reason") String reason);

    /**
     * 按业务条件更新主数据分发日志的领取状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_log SET lease_token=#{token},lease_until=clock_timestamp()+interval '120 seconds', "
            + "attempts=attempts+1,update_time=clock_timestamp() WHERE id=#{id} AND deleted=0 "
            + "AND run_protocol=1 AND run_status=1 AND attempts<#{maximum} AND available_at<=clock_timestamp() "
            + "AND (lease_until IS NULL OR lease_until<=clock_timestamp())")
    int claim(@Param("id") Long id, @Param("token") String token, @Param("maximum") int maximum);

    /**
     * 按业务条件更新主数据分发日志的开始状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_log SET run_status=2,update_time=clock_timestamp() WHERE id=#{r.id} "
            + "AND distribute_id=#{r.distributeId} AND deleted=0 AND run_protocol=1 "
            + "AND run_status=1 AND lease_token=#{r.leaseToken} AND lease_until>clock_timestamp()")
    int begin(@Param("r") MasterDistributeLogDO run);

    /**
     * 按业务条件更新主数据分发日志的心跳状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_log SET lease_until=clock_timestamp()+interval '120 seconds',update_time=clock_timestamp() "
            + "WHERE id=#{r.id} AND distribute_id=#{r.distributeId} AND deleted=0 AND run_protocol=1 "
            + "AND run_status IN (1,2) AND lease_token=#{r.leaseToken} AND lease_until>clock_timestamp()")
    int heartbeat(@Param("r") MasterDistributeLogDO run);

    /**
     * 按业务条件更新主数据分发日志的rejected状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_log SET lease_token=NULL,lease_until=NULL,available_at=clock_timestamp()+interval '30 seconds', "
            + "update_time=clock_timestamp() WHERE id=#{r.id} AND deleted=0 "
            + "AND run_status=1 AND lease_token=#{r.leaseToken} AND lease_until>clock_timestamp()")
    int rejected(@Param("r") MasterDistributeLogDO run);

    /**
     * 按业务条件更新主数据分发日志的结束状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_log SET run_status=#{result},end_time=clock_timestamp(),update_time=clock_timestamp(), "
            + "error_info=#{error},data_count=#{r.dataCount},byte_count=#{r.byteCount},"
            + "total_count=COALESCE(#{r.totalCount},total_count),success_count=COALESCE(#{r.successCount},success_count),"
            + "failure_count=COALESCE(#{r.failureCount},failure_count),unknown_count=COALESCE(#{r.unknownCount},unknown_count) WHERE id=#{r.id} "
            + "AND distribute_id=#{r.distributeId} AND deleted=0 AND run_protocol=1 "
            + "AND run_status=#{expected} AND lease_token=#{r.leaseToken} AND lease_until>clock_timestamp()")
    int finish(@Param("r") MasterDistributeLogDO run, @Param("expected") int expected, @Param("result") int result,
            @Param("error") String error);

    /**
     * 按业务条件更新主数据分发日志的取消状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_log SET run_status=CASE WHEN run_status=1 THEN 4 ELSE 5 END, "
            + "end_time=clock_timestamp(),update_time=clock_timestamp(),error_info=CASE WHEN run_status=1 "
            + "THEN '发送前取消，未执行发送' ELSE '发送后取消，外部结果未知，禁止直接重跑' END "
            + "WHERE distribute_id=#{task} AND deleted=0 AND run_status IN (1,2)")
    int cancel(@Param("task") Long task);

    // 在同一 CAS 内依据实际状态划分发送边界，不能采用控制器读到的旧状态。

    /**
     * 按业务条件更新主数据分发日志的停止本人持有状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_log SET run_status=CASE WHEN run_status=1 THEN 4 ELSE 5 END, "
            + "end_time=clock_timestamp(),update_time=clock_timestamp(),updater=CAST(#{actor} AS varchar), "
            + "stop_requested_by=#{actor},stop_requested_at=clock_timestamp(),stop_reason=#{reason}, "
            + "error_info=CASE WHEN run_status=1 THEN '发送前停止，未执行发送' "
            + "ELSE '发送后请求停止，外部结果未知，禁止直接重跑' END "
            + "WHERE id=#{record} AND distribute_id=#{task} AND deleted=0 "
            + "AND run_protocol=1 AND run_status IN (1,2) AND stop_requested_at IS NULL")
    int stopOwned(@Param("record") Long record, @Param("task") Long task,
            @Param("actor") Long actor, @Param("reason") String reason);

    // 包含逻辑删除凭证，禁止通过删除后复用请求号改写审计。

    /**
     * 查询处理结果。
     */
    @Select("SELECT * FROM dh_governance_master_distribute_log WHERE resolution_request_id=#{requestId}")
    MasterDistributeLogDO selectResolution(@Param("requestId") String requestId);

    /**
     * 按指定条件统计主数据分发日志的有效记录数。
     */
    @Select("SELECT count(*) FROM dh_governance_master_distribute_log WHERE "
            + "distribute_id=#{task} AND deleted=0 AND run_status IN (1,2)")
    long countActive(@Param("task") Long task);

    /**
     * 按业务条件更新主数据分发日志的resolve本人持有状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_log SET run_status=6,resolution_request_id=#{r.requestId}, "
            + "resolution_outcome=#{r.outcome},resolution_reason=#{r.reason},stopped_evidence_ref=#{r.stoppedEvidenceRef}, "
            + "output_evidence_ref=#{r.outputEvidenceRef},resolved_by=#{actor},resolved_at=clock_timestamp(), "
            + "updater=CAST(#{actor} AS varchar),update_time=clock_timestamp() "
            + "WHERE id=#{r.recordId} AND deleted=0 AND run_status=5 "
            + "AND resolution_request_id IS NULL AND (lease_until IS NULL OR lease_until<=clock_timestamp())")
    int resolveOwned(@Param("actor") Long actor,
            @Param("r") cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeResolveReqVO request);

    /**
     * 查询主数据分发日志分页。
     */
    default PageResult<MasterDistributeLogDO> selectPage(MasterDistributeLogPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MasterDistributeLogDO>()
                .eq(MasterDistributeLogDO::getDistributeId, reqVO.getDistributeId())
                .eqIfPresent(MasterDistributeLogDO::getRunStatus, reqVO.getRunStatus())
                .orderByDesc(MasterDistributeLogDO::getId));
    }

}
