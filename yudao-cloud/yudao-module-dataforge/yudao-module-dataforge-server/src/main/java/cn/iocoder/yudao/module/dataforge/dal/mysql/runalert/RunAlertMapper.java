package cn.iocoder.yudao.module.dataforge.dal.mysql.runalert;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.runalert.RunAlertDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.runalert.RunAlertCandidate;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务运行失败通知。
 *
 * <p>creator 列只有是数字用户编号时才作为接收人候选。扫描只读五类平台运行记录，失败条件与迁移 0292 的部分索引一致；源系统迁入快照（origin=SOURCE、无受管 DS 身份的集成实例）
 * 不通知。状态迁移全部是带前置状态和租约令牌的条件更新，时间判断统一使用数据库时钟。</p>
 */
@Mapper
public interface RunAlertMapper extends BaseMapperX<RunAlertDO> {

    String FAILED_RUNS = "SELECT 'DEVELOPMENT' source_kind,r.id run_id,r.task_id,coalesce(t.name,r.task_name) task_name,"
            + "coalesce(r.task_name,t.name)||'-'||r.id::text run_name,coalesce(r.end_time,r.update_time) occurred_time,"
            + "t.owner_id,NULL::bigint release_user_id,r.execution_user_id,"
            + "CASE WHEN t.creator ~ '^[1-9][0-9]{0,17}$' THEN t.creator::bigint END creator_id,NULL::text integration_name "
            + "FROM dh_dataforge_dev_record r JOIN dh_dataforge_dev_task t ON t.id=r.task_id AND t.deleted=0 "
            + "WHERE r.deleted=0 AND r.status='FAILED' AND coalesce(r.end_time,r.update_time)>=#{since} "
            + "UNION ALL SELECT 'DATA_ACCESS',r.id,r.data_access_id,t.task_name,coalesce(r.task_name,t.task_name)||'-'||r.id::text,"
            + "coalesce(r.end_time,r.update_time),NULL::bigint,t.release_user_id,r.execution_user_id,"
            + "CASE WHEN t.creator ~ '^[1-9][0-9]{0,17}$' THEN t.creator::bigint END,NULL::text "
            + "FROM dh_dataforge_access_task r JOIN dh_dataforge_access t ON t.id=r.data_access_id AND t.deleted=0 "
            + "WHERE r.deleted=0 AND r.run_status=4 AND coalesce(r.end_time,r.update_time)>=#{since} "
            + "UNION ALL SELECT 'ETL',r.id,r.pipeline_id,p.name,coalesce(r.name,p.name),coalesce(r.end_time,r.update_time),"
            + "i.owner_id,p.release_user_id,r.execution_user_id,"
            + "CASE WHEN p.creator ~ '^[1-9][0-9]{0,17}$' THEN p.creator::bigint END,i.name "
            + "FROM dh_dataforge_etl_record r JOIN dh_dataforge_etl_pipeline p ON p.id=r.pipeline_id AND p.deleted=0 "
            + "LEFT JOIN LATERAL (SELECT it.owner_id,it.name FROM dh_dataforge_integration_task it "
            + "WHERE it.pipeline_id=r.pipeline_id AND it.deleted=0 ORDER BY it.id LIMIT 1) i ON true "
            + "WHERE r.deleted=0 AND r.run_status=4 AND coalesce(r.end_time,r.update_time)>=#{since} "
            + "UNION ALL SELECT 'INTEGRATION',r.id,r.task_id,t.name,coalesce(r.instance_name,t.name),coalesce(r.end_time,r.update_time),"
            + "t.owner_id,NULL::bigint,CASE WHEN r.creator ~ '^[1-9][0-9]{0,17}$' THEN r.creator::bigint END,"
            + "CASE WHEN t.creator ~ '^[1-9][0-9]{0,17}$' THEN t.creator::bigint END,NULL::text "
            + "FROM dh_dataforge_integration_record r JOIN dh_dataforge_integration_task t ON t.id=r.task_id AND t.deleted=0 "
            + "WHERE r.deleted=0 AND r.status='FAILURE' AND r.ds_project_code IS NOT NULL "
            + "AND coalesce(r.end_time,r.update_time)>=#{since} "
            + "UNION ALL SELECT 'JOB',r.id,r.job_id,j.name,r.instance_name,coalesce(r.end_time,r.update_time),"
            + "j.owner_id,NULL::bigint,r.execution_user_id,CASE WHEN j.creator ~ '^[1-9][0-9]{0,17}$' THEN j.creator::bigint END,NULL::text "
            + "FROM dh_dataforge_job_record r JOIN dh_dataforge_job j ON j.id=r.job_id AND j.deleted=0 "
            + "WHERE r.deleted=0 AND r.status='FAILURE' AND r.origin='PLATFORM' AND coalesce(r.end_time,r.update_time)>=#{since}";

    /**
     * 读取失败时间不早于 since、尚未登记过通知的平台失败运行，按失败时间先后。
     *
     * @param since 扫描窗口起点
     * @param limit 最多返回条数
     * @return 失败运行
     */
    @Select("SELECT * FROM (" + FAILED_RUNS + ") c WHERE NOT EXISTS (SELECT 1 FROM dh_dataforge_run_alert a "
            + "WHERE a.source_kind=c.source_kind AND a.run_id=c.run_id AND a.event_type='FAILURE') "
            + "ORDER BY c.occurred_time,c.source_kind,c.run_id LIMIT #{limit}")
    List<RunAlertCandidate> selectFailedRuns(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /**
     * 登记一条通知；同一运行实例、事件、渠道、接收人已登记时不重复写入。
     *
     * @param alert 通知记录
     * @return 新增行数
     */
    @Insert("INSERT INTO dh_dataforge_run_alert(source_kind,run_id,task_id,task_name,run_name,event_type,occurred_time,"
            + "receiver_user_id,channel,template_params,status,last_error) VALUES(#{a.sourceKind},#{a.runId},#{a.taskId},"
            + "#{a.taskName},#{a.runName},'FAILURE',#{a.occurredTime},#{a.receiverUserId},#{a.channel},"
            + "CAST(#{a.templateParams} AS jsonb),#{a.status},#{a.lastError}) "
            + "ON CONFLICT (source_kind,run_id,event_type,channel,receiver_user_id) DO NOTHING")
    int insertIgnore(@Param("a") RunAlertDO alert);

    /**
     * 读取可认领的待发送记录，以及租约已过期的发送中记录。
     *
     * @param limit 最多返回条数
     * @return 候选记录
     */
    @Select("SELECT * FROM dh_dataforge_run_alert WHERE deleted=0 AND status IN (0,1) "
            + "AND ((status=0 AND available_at<=clock_timestamp()) OR (status=1 AND lease_until<=clock_timestamp())) "
            + "ORDER BY available_at,id LIMIT #{limit}")
    List<RunAlertDO> selectDue(@Param("limit") int limit);

    /**
     * 短信、邮件发送中租约过期：无法确认是否已发出，转为结果未知，不再自动重发。
     *
     * @param id 记录编号
     * @return 更新行数
     */
    @Update("UPDATE dh_dataforge_run_alert SET status=4,last_error='LEASE_EXPIRED',lease_token=NULL,lease_until=NULL,"
            + "update_time=clock_timestamp() WHERE id=#{id} AND deleted=0 AND status=1 AND channel<>1 "
            + "AND lease_until<=clock_timestamp()")
    int expireUnconfirmed(@Param("id") Long id);

    /**
     * 认领次数已用尽的可认领记录转为死信。
     *
     * @param id 记录编号
     * @param maxAttempts 最大认领次数
     * @return 更新行数
     */
    @Update("UPDATE dh_dataforge_run_alert SET status=3,last_error='ATTEMPTS_EXHAUSTED',lease_token=NULL,lease_until=NULL,"
            + "update_time=clock_timestamp() WHERE id=#{id} AND deleted=0 AND attempts>=#{maxAttempts} "
            + "AND ((status=0 AND available_at<=clock_timestamp()) OR (status=1 AND channel=1 AND lease_until<=clock_timestamp()))")
    int exhaust(@Param("id") Long id, @Param("maxAttempts") int maxAttempts);

    /**
     * 认领一条记录用于发送；站内信的过期租约可被接管（system 侧按幂等键去重），短信、邮件的过期租约不可接管。
     *
     * @param id 记录编号
     * @param token 租约令牌
     * @param leaseSeconds 租约秒数
     * @param maxAttempts 最大认领次数
     * @return 更新行数
     */
    @Update("UPDATE dh_dataforge_run_alert SET status=1,attempts=attempts+1,lease_token=#{token},"
            + "lease_until=clock_timestamp()+(#{leaseSeconds}*interval '1 second'),update_time=clock_timestamp() "
            + "WHERE id=#{id} AND deleted=0 AND attempts<#{maxAttempts} "
            + "AND ((status=0 AND available_at<=clock_timestamp()) OR (status=1 AND channel=1 AND lease_until<=clock_timestamp()))")
    int claim(@Param("id") Long id, @Param("token") String token, @Param("leaseSeconds") int leaseSeconds,
              @Param("maxAttempts") int maxAttempts);

    /**
     * 记录送达回执。
     *
     * @param id 记录编号
     * @param token 租约令牌
     * @param receiptId system 返回的消息或日志编号
     * @return 更新行数
     */
    @Update("UPDATE dh_dataforge_run_alert SET status=2,receipt_id=#{receiptId},handled_at=clock_timestamp(),last_error=NULL,"
            + "lease_token=NULL,lease_until=NULL,update_time=clock_timestamp() "
            + "WHERE id=#{id} AND deleted=0 AND status=1 AND lease_token=#{token}")
    int delivered(@Param("id") Long id, @Param("token") String token, @Param("receiptId") Long receiptId);

    /**
     * 确定未发出（system 明确拒绝或站内信结果未确认）：按次数退避后重试，次数用尽转为死信。
     *
     * @param id 记录编号
     * @param token 租约令牌
     * @param error 失败原因代码
     * @param maxAttempts 最大认领次数
     * @param backoffSeconds 每次退避的基础秒数
     * @return 更新行数
     */
    @Update("UPDATE dh_dataforge_run_alert SET status=CASE WHEN attempts>=#{maxAttempts} THEN 3 ELSE 0 END,"
            + "available_at=clock_timestamp()+((#{backoffSeconds}*attempts)*interval '1 second'),last_error=#{error},"
            + "lease_token=NULL,lease_until=NULL,update_time=clock_timestamp() "
            + "WHERE id=#{id} AND deleted=0 AND status=1 AND lease_token=#{token}")
    int retryLater(@Param("id") Long id, @Param("token") String token, @Param("error") String error,
                   @Param("maxAttempts") int maxAttempts, @Param("backoffSeconds") int backoffSeconds);

    /**
     * 以终态结束本次发送：死信（重试无益）或结果未知（短信、邮件未确认）。
     *
     * @param id 记录编号
     * @param token 租约令牌
     * @param status 目标状态：3 或 4
     * @param error 失败原因代码
     * @return 更新行数
     */
    @Update("UPDATE dh_dataforge_run_alert SET status=#{status},last_error=#{error},lease_token=NULL,lease_until=NULL,"
            + "update_time=clock_timestamp() WHERE id=#{id} AND deleted=0 AND status=1 AND lease_token=#{token} "
            + "AND #{status} IN (3,4)")
    int settle(@Param("id") Long id, @Param("token") String token, @Param("status") int status,
               @Param("error") String error);

    /**
     * 读取并锁定一条记录，供人工处置。
     *
     * @param id 记录编号
     * @return 记录
     */
    @Select("SELECT * FROM dh_dataforge_run_alert WHERE id=#{id} AND deleted=0 FOR UPDATE")
    RunAlertDO selectForUpdate(@Param("id") Long id);

    /**
     * 人工处置死信或结果未知记录：重新发送（清零次数）或确认关闭。
     *
     * @param id 记录编号
     * @param action RETRY 或 ACKNOWLEDGE
     * @param reason 处置原因
     * @param command 调度执行身份
     * @return 更新行数
     */
    @Update("UPDATE dh_dataforge_run_alert SET status=CASE WHEN #{action}='RETRY' THEN 0 ELSE 5 END,"
            + "attempts=CASE WHEN #{action}='RETRY' THEN 0 ELSE attempts END,available_at=clock_timestamp(),"
            + "handled_at=CASE WHEN #{action}='RETRY' THEN NULL ELSE clock_timestamp() END,"
            + "disposition_action=#{action},disposition_reason=#{reason},disposition_command=#{command},"
            + "lease_token=NULL,lease_until=NULL,update_time=clock_timestamp() "
            + "WHERE id=#{id} AND deleted=0 AND status IN (3,4) AND (#{action}<>'RETRY' OR receiver_user_id>0)")
    int dispose(@Param("id") Long id, @Param("action") String action, @Param("reason") String reason,
                @Param("command") String command);

    /**
     * 统计需要人工关注的记录（死信与结果未知）。
     *
     * @return 记录数
     */
    @Select("SELECT count(*) FROM dh_dataforge_run_alert WHERE deleted=0 AND status IN (3,4)")
    long countAttention();

    /**
     * 有界清理早于保留期的已送达、已关闭记录；死信与结果未知保留至人工处置。
     *
     * @param retentionDays 保留天数
     * @param limit 单次最多清理条数
     * @return 删除行数
     */
    @Delete("DELETE FROM dh_dataforge_run_alert WHERE id IN (SELECT id FROM dh_dataforge_run_alert "
            + "WHERE deleted=0 AND status IN (2,5) AND handled_at<clock_timestamp()-(#{retentionDays}*interval '1 day') "
            + "ORDER BY handled_at,id LIMIT #{limit})")
    int deleteHandled(@Param("retentionDays") int retentionDays, @Param("limit") int limit);

}
