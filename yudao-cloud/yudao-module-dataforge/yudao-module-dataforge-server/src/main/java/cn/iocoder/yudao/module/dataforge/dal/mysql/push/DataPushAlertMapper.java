package cn.iocoder.yudao.module.dataforge.dal.mysql.push;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushAlertDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskLogDO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DataPushAlertMapper extends BaseMapperX<DataPushAlertDO> {

    /**
     * 新增数据推送告警的入队记录。
     *
     * @param task 任务记录
     * @param run 运行
     * @param kind 种类
     * @param recipient 接收人
     * @param name 名称
     * @return 入队数值
     */
    @Insert("INSERT INTO dh_dataforge_push_alert(task_id,run_id,execute_no,event_type,recipient_id,task_name,status,last_error) "
            + "SELECT push_task_id,id,COALESCE(execute_no,CAST(id AS varchar)),#{kind},#{recipient},#{name}, "
            + "CASE WHEN CAST(#{recipient} AS bigint) IS NULL THEN 3 ELSE 0 END,CASE WHEN CAST(#{recipient} AS bigint) IS NULL THEN 'NO_RECIPIENT' ELSE NULL END "
            + "FROM dh_dataforge_push_task_log WHERE push_task_id=#{task} AND deleted=0 "
            + "AND ((#{kind}='UNKNOWN' AND run_status=5) OR (#{kind}='DISPATCH_EXHAUSTED' AND id=#{run} AND run_status=4)) "
            + "ON CONFLICT (run_id,event_type) DO NOTHING")
    int enqueue(@Param("task") Long task, @Param("run") Long run,
            @Param("kind") String kind, @Param("recipient") Long recipient, @Param("name") String name);

    /** 全局仅发现记录，后续必须校验记录再进行写入。 */
    @Select("SELECT * FROM dh_dataforge_push_alert WHERE deleted=0 AND status IN (0,1) "
            + "AND available_at<=clock_timestamp() AND (status=0 OR lease_until<=clock_timestamp()) "
            + "ORDER BY available_at,id LIMIT 20")
    List<DataPushAlertDO> candidates();

    /**
     * 查询数据推送告警的缺失未知。
     *
     * @return 数据推送告警的缺失未知结果集合
     */
    @Select("SELECT r.* FROM dh_dataforge_push_task_log r WHERE r.deleted=0 AND r.run_status=5 "
            + "AND NOT EXISTS (SELECT 1 FROM dh_dataforge_push_alert a WHERE 1=1 "
            + "AND a.run_id=r.id AND a.event_type='UNKNOWN') ORDER BY r.id LIMIT 100")
    List<DataPushTaskLogDO> missingUnknown();

    /**
     * 更新数据推送告警的认领。
     *
     * @param id 记录编号
     * @param token 当前执行或租约令牌
     * @return 认领数值
     */
    @Update("UPDATE dh_dataforge_push_alert SET status=1,attempts=attempts+1,lease_token=#{token}, "
            + "lease_until=clock_timestamp()+interval '120 seconds',available_at=clock_timestamp()+interval '120 seconds', "
            + "update_time=clock_timestamp() WHERE id=#{id} AND deleted=0 AND attempts<5 "
            + "AND ((status=0 AND available_at<=clock_timestamp()) OR (status=1 AND lease_until<=clock_timestamp()))")
    int claim(@Param("id") Long id, @Param("token") String token);

    /**
     * 更新数据推送告警的重试耗尽。
     *
     * @param id 记录编号
     * @return 重试耗尽数值
     */
    @Update("UPDATE dh_dataforge_push_alert SET status=3,last_error='ATTEMPTS_EXHAUSTED',lease_token=NULL, "
            + "lease_until=NULL,update_time=clock_timestamp() WHERE id=#{id} AND deleted=0 "
            + "AND attempts>=5 AND ((status=0 AND available_at<=clock_timestamp()) OR (status=1 AND lease_until<=clock_timestamp()))")
    int exhaust(@Param("id") Long id);

    /**
     * 更新数据推送告警的已投递。
     *
     * @param id 记录编号
     * @param token 当前执行或租约令牌
     * @param message 消息
     * @return 已投递数值
     */
    @Update("UPDATE dh_dataforge_push_alert SET status=2,notify_message_id=#{message},handled_at=clock_timestamp(), "
            + "last_error=NULL,lease_token=NULL,lease_until=NULL,update_time=clock_timestamp() "
            + "WHERE id=#{id} AND deleted=0 AND status=1 AND lease_token=#{token} "
            + "AND lease_until>clock_timestamp()")
    int delivered(@Param("id") Long id, @Param("token") String token, @Param("message") Long message);

    /**
     * 更新数据推送告警的失败。
     *
     * @param id 记录编号
     * @param token 当前执行或租约令牌
     * @param error 失败原因
     * @return 失败数值
     */
    @Update("UPDATE dh_dataforge_push_alert SET status=CASE WHEN attempts>=5 THEN 3 ELSE 0 END, "
            + "available_at=clock_timestamp()+((30*attempts)*interval '1 second'),last_error=#{error}, "
            + "lease_token=NULL,lease_until=NULL,update_time=clock_timestamp() "
            + "WHERE id=#{id} AND deleted=0 AND status=1 AND lease_token=#{token} "
            + "AND lease_until>clock_timestamp()")
    int failed(@Param("id") Long id, @Param("token") String token, @Param("error") String error);

    /**
     * 锁定并读取数据推送告警记录。
     *
     * @param id 记录编号
     * @return 数据推送告警
     */
    @Select("SELECT * FROM dh_dataforge_push_alert WHERE id=#{id} AND deleted=0 FOR UPDATE")
    DataPushAlertDO lockOwned(@Param("id") Long id);

    /**
     * 查询数据推送告警的命令存在性。
     *
     * @param command 待处理命令
     * @return 命令存在性数值
     */
    @Select("SELECT count(*) FROM dh_dataforge_push_alert_disposition WHERE command_id=#{command}")
    int commandExists(@Param("command") String command);

    /**
     * 查询数据推送告警的匹配命令。
     *
     * @param command 待处理命令
     * @param id 记录编号
     * @param action 待执行的操作
     * @param reason 操作原因
     * @return 匹配命令数值
     */
    @Select("SELECT count(*) FROM dh_dataforge_push_alert_disposition WHERE command_id=#{command} "
            + "AND alert_id=#{id} AND action=#{action} AND reason=#{reason}")
    int matchesCommand(@Param("command") String command, @Param("id") Long id,
            @Param("action") String action, @Param("reason") String reason);

    /**
     * 新增数据推送告警的审计记录。
     *
     * @param command 待处理命令
     * @param id 记录编号
     * @param action 待执行的操作
     * @param reason 操作原因
     * @return 审计数值
     */
    @Insert("INSERT INTO dh_dataforge_push_alert_disposition(command_id,alert_id,action,reason) "
            + "VALUES(#{command},#{id},#{action},#{reason})")
    int audit(@Param("command") String command, @Param("id") Long id,
            @Param("action") String action, @Param("reason") String reason);

    /**
     * 更新数据推送告警的处置。
     *
     * @param id 记录编号
     * @param action 待执行的操作
     * @return 处置数值
     */
    @Update("UPDATE dh_dataforge_push_alert SET status=CASE WHEN #{action}='RETRY' THEN 0 ELSE 4 END, "
            + "attempts=CASE WHEN #{action}='RETRY' THEN 0 ELSE attempts END,available_at=clock_timestamp(), "
            + "handled_at=CASE WHEN #{action}='RETRY' THEN NULL ELSE clock_timestamp() END, "
            + "lease_token=NULL,lease_until=NULL,update_time=clock_timestamp() "
            + "WHERE id=#{id} AND deleted=0 AND status=3")
    int dispose(@Param("id") Long id, @Param("action") String action);

    /**
     * 查询数据推送告警的死信数量。
     *
     * @return 死信数量数值
     */
    @Select("SELECT count(*) FROM dh_dataforge_push_alert WHERE deleted=0 AND status=3")
    long deadCount();


    /** 仅清理已获得回执且无人工处置审计的老通知；原未知执行与死信不清理。 */
    @Delete("DELETE FROM dh_dataforge_push_alert WHERE id IN (SELECT a.id FROM dh_dataforge_push_alert a "
            + "WHERE a.status=2 AND a.handled_at<clock_timestamp()-interval '90 days' "
            + "AND NOT EXISTS (SELECT 1 FROM dh_dataforge_push_alert_disposition d WHERE d.alert_id=a.id) "
            + "AND NOT EXISTS (SELECT 1 FROM dh_dataforge_push_task_log r WHERE r.id=a.run_id) "
            + "ORDER BY a.id LIMIT 100)")
    int cleanDelivered();
}
