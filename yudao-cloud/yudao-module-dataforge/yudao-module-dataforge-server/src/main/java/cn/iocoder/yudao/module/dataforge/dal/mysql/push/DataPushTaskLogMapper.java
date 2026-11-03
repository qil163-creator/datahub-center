package cn.iocoder.yudao.module.dataforge.dal.mysql.push;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushTaskLogPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskLogDO;
import cn.iocoder.yudao.module.dataforge.enums.push.PushRunStatusEnum;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Mapper
public interface DataPushTaskLogMapper extends BaseMapperX<DataPushTaskLogDO> {

    /**
     * 按查询条件分页读取数据推送任务日志。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取数据推送任务日志结果
     */
    default PageResult<DataPushTaskLogDO> selectPage(DataPushTaskLogPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DataPushTaskLogDO>()
                .eq(DataPushTaskLogDO::getPushTaskId, reqVO.getPushTaskId())
                .eqIfPresent(DataPushTaskLogDO::getRunStatus, reqVO.getRunStatus())
                .orderByDesc(DataPushTaskLogDO::getId));
    }

    /**
     * 按任务编号集合查询最新。
     *
     * @param taskIds 任务编号集合
     * @return 按任务编号集合查询最新结果集合
     */
    default List<DataPushTaskLogDO> selectLatestByTaskIds(Collection<Long> taskIds) {
        if (CollUtil.isEmpty(taskIds)) {
            return Collections.emptyList();
        }
        List<Object> ids = selectObjs(new QueryWrapper<DataPushTaskLogDO>()
                .select("MAX(id)")
                .in("push_task_id", taskIds)
                .groupBy("push_task_id"));
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return selectByIds(ids.stream().map(id -> ((Number) id).longValue()).toList());
    }

    /**
     * 按任务编号删除数据推送任务日志。
     *
     * @param pushTaskId 推送任务编号
     * @return 符合条件或受影响的记录数量
     */
    default int deleteByTaskId(Long pushTaskId) {
        return delete(new LambdaQueryWrapperX<DataPushTaskLogDO>()
                .eq(DataPushTaskLogDO::getPushTaskId, pushTaskId)
                .in(DataPushTaskLogDO::getRunStatus, 3, 4));
    }

    /**
     * 读取数据库当前时间，供持久状态及租约判断使用。
     *
     * @return 本地日期时间
     */
    @Select("SELECT clock_timestamp()::timestamp")
    LocalDateTime databaseNow();

    /**
     * 统计数据推送任务日志的未解决数量。
     *
     * @param taskId 任务编号
     * @return 符合条件或受影响的记录数量
     */
    @Select("SELECT count(*) FROM dh_dataforge_push_task_log WHERE push_task_id=#{taskId} "
            + "AND deleted=0 AND run_status IN (1,2,5)")
    long countUnresolved(@Param("taskId") Long taskId);

    /**
     * 查询数据推送任务日志的阻塞状态。
     *
     * @param taskId 任务编号
     * @return 阻塞状态数值
     */
    @Select("SELECT COALESCE(MAX(run_status),0) FROM dh_dataforge_push_task_log WHERE push_task_id=#{taskId} "
            + "AND deleted=0 AND run_status IN (1,2,5)")
    int blockingState(@Param("taskId") Long taskId);

    /**
     * 查询数据推送任务日志的处置结论。
     *
     * @param requestId 请求编号
     * @return 数据推送任务日志
     */
    @Select("SELECT * FROM dh_dataforge_push_task_log WHERE 1=1 "
            + "AND resolution_request_id=#{requestId} AND deleted=0")
    DataPushTaskLogDO selectResolution(@Param("requestId") String requestId);

    /**
     * 统计数据推送任务日志的有效数量。
     *
     * @param taskId 任务编号
     * @return 符合条件或受影响的记录数量
     */
    @Select("SELECT count(*) FROM dh_dataforge_push_task_log WHERE push_task_id=#{taskId} "
            + "AND deleted=0 AND run_status IN (1,2)")
    long countActive(@Param("taskId") Long taskId);

    /**
     * 更新数据推送任务日志的解析持有执行权。
     *
     * @param actor 操作人编号
     * @param request 请求参数
     * @return 解析持有执行权数值
     */
    @Update("UPDATE dh_dataforge_push_task_log SET run_status=6,resolution_request_id=#{request.requestId}, "
            + "resolution_outcome=#{request.outcome},resolution_reason=#{request.reason}, "
            + "stopped_evidence_ref=#{request.stoppedEvidenceRef},output_evidence_ref=#{request.outputEvidenceRef}, "
            + "resolved_by=#{actor},resolved_at=clock_timestamp(),updater=CAST(#{actor} AS varchar), "
            + "update_time=clock_timestamp(),dispatch_token=NULL,dispatch_lease_until=NULL "
            + "WHERE id=#{request.recordId} AND deleted=0 AND run_status=5 "
            + "AND resolution_request_id IS NULL "
            + "AND (dispatch_lease_until IS NULL OR dispatch_lease_until<=clock_timestamp())")
    int resolveOwned(@Param("actor") Long actor,
            @Param("request") cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushResolveReqVO request);

    /**
     * 更新数据推送任务日志的刷新心跳。
     *
     * @param id 记录编号
     * @param token 当前执行或租约令牌
     * @param seconds 秒数
     * @return 刷新心跳数值
     */
    @Update("UPDATE dh_dataforge_push_task_log SET heartbeat_time=clock_timestamp(), "
            + "dispatch_lease_until=clock_timestamp()+(#{seconds} * interval '1 second') "
            + "WHERE id=#{id} AND deleted=0 AND run_status IN (1,2) "
            + "AND dispatch_protocol=1 AND dispatch_token=#{token} AND dispatch_lease_until>clock_timestamp()")
    int touchHeartbeat(@Param("id") Long id,
            @Param("token") String token, @Param("seconds") int seconds);

    /**
     * 更新数据推送任务日志的开始发送中。
     *
     * @param run 运行
     * @return 开始发送中数值
     */
    @Update("UPDATE dh_dataforge_push_task_log SET run_status=2, heartbeat_time=clock_timestamp(), "
            + "update_time=clock_timestamp() WHERE id=#{run.id} AND deleted=0 AND run_status=1 "
            + "AND dispatch_protocol=1 AND dispatch_token=#{run.dispatchToken} AND dispatch_lease_until>clock_timestamp()")
    int startSending(@Param("run") DataPushTaskLogDO run);

    /**
     * 更新数据推送任务日志的结束持有执行权。
     *
     * @param run 运行
     * @param expected 预期值
     * @return 结束持有执行权数值
     */
    @Update("UPDATE dh_dataforge_push_task_log SET run_status=#{run.runStatus}, end_time=clock_timestamp(), "
            + "data_count=#{run.dataCount}, real_time_log=#{run.realTimeLog}, error_info=#{run.errorInfo}, "
            + "update_time=clock_timestamp() WHERE id=#{run.id} AND push_task_id=#{run.pushTaskId} "
            + "AND deleted=0 AND run_status=#{expected} AND dispatch_protocol=1 "
            + "AND dispatch_token=#{run.dispatchToken} AND dispatch_lease_until>clock_timestamp()")
    int finishOwned(@Param("run") DataPushTaskLogDO run, @Param("expected") int expected);

    /**
     * 更新数据推送任务日志的进度持有执行权。
     *
     * @param run 运行
     * @param seconds 秒数
     * @param count 数量
     * @param message 消息
     * @return 进度持有执行权数值
     */
    @Update("UPDATE dh_dataforge_push_task_log SET data_count=#{count}, real_time_log=#{message}, "
            + "heartbeat_time=clock_timestamp(),dispatch_lease_until=clock_timestamp()+(#{seconds} * interval '1 second') "
            + "WHERE id=#{run.id} AND deleted=0 AND run_status=2 "
            + "AND dispatch_protocol=1 AND dispatch_token=#{run.dispatchToken} AND dispatch_lease_until>clock_timestamp()")
    int progressOwned(@Param("run") DataPushTaskLogDO run, @Param("seconds") int seconds,
            @Param("count") long count, @Param("message") String message);

    /** 全局只读候选；真正领取在任务锁内重新判断。 */
    @Select("SELECT * FROM dh_dataforge_push_task_log WHERE deleted=0 AND dispatch_protocol=1 AND run_status=1 "
            + "AND next_dispatch_at<=clock_timestamp() AND (dispatch_token IS NULL OR dispatch_lease_until<=clock_timestamp()) "
            + "ORDER BY next_dispatch_at,id LIMIT 200")
    List<DataPushTaskLogDO> selectDispatchCandidates();

    /**
     * 更新数据推送任务日志的获取分发。
     *
     * @param run 运行
     * @param token 当前执行或租约令牌
     * @param seconds 秒数
     * @param maxAttempts 最大值尝试
     * @return 获取分发数值
     */
    @Update("UPDATE dh_dataforge_push_task_log SET dispatch_token=#{token}, "
            + "dispatch_lease_until=clock_timestamp()+(#{seconds} * interval '1 second'), "
            + "heartbeat_time=clock_timestamp(),dispatch_attempts=dispatch_attempts+1,error_info=NULL,update_time=clock_timestamp() "
            + "WHERE id=#{run.id} AND push_task_id=#{run.pushTaskId} AND deleted=0 "
            + "AND dispatch_protocol=1 AND run_status=1 AND dispatch_attempts<#{maxAttempts} "
            + "AND next_dispatch_at<=clock_timestamp() AND (dispatch_token IS NULL OR dispatch_lease_until<=clock_timestamp())")
    int acquireDispatch(@Param("run") DataPushTaskLogDO run, @Param("token") String token,
            @Param("seconds") int seconds, @Param("maxAttempts") int maxAttempts);

    /**
     * 更新数据推送任务日志的重试耗尽分发。
     *
     * @param run 运行
     * @param maxAttempts 最大值尝试
     * @return 重试耗尽分发数值
     */
    @Update("UPDATE dh_dataforge_push_task_log SET run_status=4,end_time=clock_timestamp(), "
            + "error_info='发送前领取次数耗尽，未执行发送，请检查执行节点和队列',dispatch_token=NULL, "
            + "dispatch_lease_until=NULL,update_time=clock_timestamp() "
            + "WHERE id=#{run.id} AND push_task_id=#{run.pushTaskId} AND deleted=0 "
            + "AND dispatch_protocol=1 AND run_status=1 AND dispatch_attempts>=#{maxAttempts} "
            + "AND next_dispatch_at<=clock_timestamp() AND (dispatch_token IS NULL OR dispatch_lease_until<=clock_timestamp())")
    int exhaustDispatch(@Param("run") DataPushTaskLogDO run, @Param("maxAttempts") int maxAttempts);

    /**
     * 更新数据推送任务日志的延后分发。
     *
     * @param run 运行
     * @param delay 延迟
     * @return 延后分发数值
     */
    @Update("UPDATE dh_dataforge_push_task_log SET dispatch_token=NULL,dispatch_lease_until=NULL, "
            + "next_dispatch_at=clock_timestamp()+(#{delay} * interval '1 second'),update_time=clock_timestamp(), "
            + "error_info='本机队列未接收，保留同次执行等待重新领取' "
            + "WHERE id=#{run.id} AND deleted=0 AND dispatch_protocol=1 "
            + "AND run_status=1 AND dispatch_token=#{run.dispatchToken}")
    int deferDispatch(@Param("run") DataPushTaskLogDO run, @Param("delay") int delay);

    /**
     * 更新数据推送任务日志的取消持有执行权。
     *
     * @param taskId 任务编号
     * @return 取消持有执行权数值
     */
    @Update("UPDATE dh_dataforge_push_task_log SET run_status=CASE WHEN run_status=1 THEN 4 ELSE 5 END, "
            + "error_info=CASE WHEN run_status=1 THEN '发送前已取消' ELSE '发送后取消，结果未知，禁止重跑' END, "
            + "end_time=clock_timestamp(), update_time=clock_timestamp() WHERE push_task_id=#{taskId} "
            + "AND deleted=0 AND run_status IN (1,2)")
    int cancelOwned(@Param("taskId") Long taskId);

    /** 仅全局控制面发现候选；写操作仍显式校验每条业务记录。 */
    @Select("SELECT * FROM dh_dataforge_push_task_log WHERE deleted=0 AND ((dispatch_protocol=1 AND run_status=2 "
            + "AND dispatch_lease_until<=clock_timestamp()) OR (dispatch_protocol=0 AND run_status IN (1,2) "
            + "AND #{minutes}>0 AND COALESCE(heartbeat_time,start_time,create_time) "
            + "< clock_timestamp() - (#{minutes} * interval '1 minute'))) "
            + "ORDER BY COALESCE(heartbeat_time,start_time,create_time), id LIMIT 200")
    List<DataPushTaskLogDO> selectStaleRunning(@Param("minutes") int minutes);

    /**
     * 更新数据推送任务日志的恢复过期按编号。
     *
     * @param run 运行
     * @param minutes 分钟数
     * @return 恢复过期按编号数值
     */
    @Update("UPDATE dh_dataforge_push_task_log SET run_status=5, "
            + "error_info='发送后或旧协议心跳超时，结果未知，禁止重跑', "
            + "end_time=clock_timestamp(), update_time=clock_timestamp() "
            + "WHERE id=#{run.id} AND deleted=0 AND run_status=#{run.runStatus} "
            + "AND ((dispatch_protocol=1 AND run_status=2 AND dispatch_token=#{run.dispatchToken} "
            + "AND dispatch_lease_until<=clock_timestamp()) OR (dispatch_protocol=0 AND run_status IN (1,2) "
            + "AND #{minutes}>0 AND COALESCE(heartbeat_time,start_time,create_time) "
            + "< clock_timestamp() - (#{minutes} * interval '1 minute')))")
    int recoverStaleById(@Param("run") DataPushTaskLogDO run, @Param("minutes") int minutes);

    /**
     * 统计连续失败。
     *
     * @param pushTaskId 推送任务编号
     * @param limit 读取或处理上限
     * @return 符合条件或受影响的记录数量
     */
    default int countConsecutiveFailures(Long pushTaskId, int limit) {
        if (pushTaskId == null || limit <= 0) {
            return 0;
        }
        List<DataPushTaskLogDO> recent = selectList(new LambdaQueryWrapperX<DataPushTaskLogDO>()
                .eq(DataPushTaskLogDO::getPushTaskId, pushTaskId)
                .orderByDesc(DataPushTaskLogDO::getId)
                .last("LIMIT " + limit));
        int count = 0;
        Integer failed = PushRunStatusEnum.FAILED.getStatus();
        for (DataPushTaskLogDO item : recent) {
            if (failed.equals(item.getRunStatus())) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * 按任务编号删除历史。
     *
     * @param pushTaskId 推送任务编号
     * @param retain 保留
     * @return 符合条件或受影响的记录数量
     */
    default int deleteOldByTaskId(Long pushTaskId, int retain) {
        if (pushTaskId == null || retain <= 0) {
            return 0;
        }
        List<Object> keepIds = selectObjs(new QueryWrapper<DataPushTaskLogDO>()
                .select("id")
                .eq("push_task_id", pushTaskId)
                .orderByDesc("id")
                .last("LIMIT " + retain));
        if (keepIds == null || keepIds.size() < retain) {
            return 0;
        }
        List<Long> ids = keepIds.stream().map(id -> ((Number) id).longValue()).toList();
        return delete(new LambdaQueryWrapperX<DataPushTaskLogDO>()
                .eq(DataPushTaskLogDO::getPushTaskId, pushTaskId)
                .in(DataPushTaskLogDO::getRunStatus, 3, 4)
                .notIn(DataPushTaskLogDO::getId, ids));
    }

}
