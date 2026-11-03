package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeAlertDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeLogDO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MasterDistributeAlertMapper extends BaseMapperX<MasterDistributeAlertDO> {

    /**
     * 按业务条件新增主数据分发告警的入队状态或数据，返回受影响行数。
     */
    @Insert("INSERT INTO dh_governance_master_distribute_alert(task_id,run_id,event_type,recipient_id,task_name,status,last_error) "
            + "SELECT distribute_id,id,#{kind},#{recipient},#{name}, "
            + "CASE WHEN CAST(#{recipient} AS bigint) IS NULL THEN 3 ELSE 0 END,CASE WHEN CAST(#{recipient} AS bigint) IS NULL THEN 'NO_RECIPIENT' ELSE NULL END "
            + "FROM dh_governance_master_distribute_log WHERE distribute_id=#{task} AND deleted=0 "
            + "AND ((#{kind}='UNKNOWN' AND run_status=5) OR (#{kind}='DISPATCH_EXHAUSTED' AND id=#{run} AND run_status=4 AND run_protocol=1 AND ((attempts>=5 AND error_info='发送前领取次数耗尽，未执行发送') OR error_info='发送前重试耗尽，转人工处理，未执行发送'))) "
            + "ON CONFLICT (run_id,event_type) DO NOTHING")
    int enqueue(@Param("task") Long task, @Param("run") Long run,
            @Param("kind") String kind, @Param("recipient") Long recipient, @Param("name") String name);

    /** 全局仅发现记录，后续通过领域状态机进行写入。 */
    @Select("SELECT * FROM dh_governance_master_distribute_alert WHERE deleted=0 AND status IN (0,1) "
            + "AND available_at<=clock_timestamp() AND (status=0 OR lease_until<=clock_timestamp()) "
            + "ORDER BY available_at,id LIMIT 20")
    List<MasterDistributeAlertDO> candidates();

    /**
     * 查询缺少终态告警记录的已结束分发运行。
     */
    @Select("SELECT r.* FROM dh_governance_master_distribute_log r WHERE r.deleted=0 AND (r.run_status=5 OR (r.run_status=4 AND r.run_protocol=1 AND ((r.attempts>=5 AND r.error_info='发送前领取次数耗尽，未执行发送') OR r.error_info='发送前重试耗尽，转人工处理，未执行发送'))) "
            + "AND NOT EXISTS (SELECT 1 FROM dh_governance_master_distribute_alert a WHERE "
            + "a.run_id=r.id AND a.event_type=CASE WHEN r.run_status=5 THEN 'UNKNOWN' ELSE 'DISPATCH_EXHAUSTED' END) ORDER BY r.id LIMIT 100")
    List<MasterDistributeLogDO> missingTerminal();

    /**
     * 按业务条件更新主数据分发告警的领取状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_alert SET status=1,attempts=attempts+1,lease_token=#{token}, "
            + "lease_until=clock_timestamp()+interval '120 seconds',available_at=clock_timestamp()+interval '120 seconds', "
            + "update_time=clock_timestamp() WHERE id=#{id} AND deleted=0 AND attempts<5 "
            + "AND ((status=0 AND available_at<=clock_timestamp()) OR (status=1 AND lease_until<=clock_timestamp()))")
    int claim(@Param("id") Long id, @Param("token") String token);

    /**
     * 按业务条件更新主数据分发告警的耗尽状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_alert SET status=3,last_error='ATTEMPTS_EXHAUSTED',lease_token=NULL, "
            + "lease_until=NULL,update_time=clock_timestamp() WHERE id=#{id} AND deleted=0 "
            + "AND attempts>=5 AND ((status=0 AND available_at<=clock_timestamp()) OR (status=1 AND lease_until<=clock_timestamp()))")
    int exhaust(@Param("id") Long id);

    /**
     * 按业务条件更新主数据分发告警的已交付状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_alert SET status=2,notify_message_id=#{message},handled_at=clock_timestamp(), "
            + "last_error=NULL,lease_token=NULL,lease_until=NULL,update_time=clock_timestamp() "
            + "WHERE id=#{id} AND deleted=0 AND status=1 AND lease_token=#{token} "
            + "AND lease_until>clock_timestamp()")
    int delivered(@Param("id") Long id, @Param("token") String token, @Param("message") Long message);

    /**
     * 按业务条件更新主数据分发告警的失败状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_alert SET status=CASE WHEN attempts>=5 THEN 3 ELSE 0 END, "
            + "available_at=clock_timestamp()+((30*attempts)*interval '1 second'),last_error=#{error}, "
            + "lease_token=NULL,lease_until=NULL,update_time=clock_timestamp() "
            + "WHERE id=#{id} AND deleted=0 AND status=1 AND lease_token=#{token} "
            + "AND lease_until>clock_timestamp()")
    int failed(@Param("id") Long id, @Param("token") String token, @Param("error") String error);

    /**
     * 按指定条件读取并锁定主数据分发告警记录，供事务内一致性校验或修改使用。
     */
    @Select("SELECT * FROM dh_governance_master_distribute_alert WHERE id=#{id} AND deleted=0 FOR UPDATE")
    MasterDistributeAlertDO lockOwned(@Param("id") Long id);

    /**
     * 按指定条件统计主数据分发告警的命令存在性记录数。
     */
    @Select("SELECT count(*) FROM dh_governance_master_distribute_alert_disposition WHERE command_id=#{command}")
    int commandExists(@Param("command") String command);

    /**
     * 按指定条件统计主数据分发告警的匹配命令记录数。
     */
    @Select("SELECT count(*) FROM dh_governance_master_distribute_alert_disposition WHERE command_id=#{command} "
            + "AND alert_id=#{id} AND action=#{action} AND reason=#{reason}")
    int matchesCommand(@Param("command") String command, @Param("id") Long id,
            @Param("action") String action, @Param("reason") String reason);

    /**
     * 按业务条件新增主数据分发告警的审计状态或数据，返回受影响行数。
     */
    @Insert("INSERT INTO dh_governance_master_distribute_alert_disposition(command_id,alert_id,action,reason) "
            + "VALUES(#{command},#{id},#{action},#{reason})")
    int audit(@Param("command") String command, @Param("id") Long id,
            @Param("action") String action, @Param("reason") String reason);

    /**
     * 按业务条件更新主数据分发告警的处置状态或数据，返回受影响行数。
     */
    @Update("UPDATE dh_governance_master_distribute_alert SET status=CASE WHEN #{action}='RETRY' THEN 0 ELSE 4 END, "
            + "attempts=CASE WHEN #{action}='RETRY' THEN 0 ELSE attempts END,available_at=clock_timestamp(), "
            + "handled_at=CASE WHEN #{action}='RETRY' THEN NULL ELSE clock_timestamp() END, "
            + "lease_token=NULL,lease_until=NULL,update_time=clock_timestamp() "
            + "WHERE id=#{id} AND deleted=0 AND status=3")
    int dispose(@Param("id") Long id, @Param("action") String action);

    /**
     * 按指定条件统计主数据分发告警的死信数量记录数。
     */
    @Select("SELECT count(*) FROM dh_governance_master_distribute_alert WHERE deleted=0 AND status=3")
    long deadCount();

    /** 仅清理已获得回执且无人工处置审计的老通知；原未知执行与死信不清理。 */
    @Delete("DELETE FROM dh_governance_master_distribute_alert WHERE id IN (SELECT a.id FROM dh_governance_master_distribute_alert a "
            + "WHERE a.status=2 AND a.handled_at<clock_timestamp()-interval '90 days' "
            + "AND NOT EXISTS (SELECT 1 FROM dh_governance_master_distribute_alert_disposition d WHERE d.alert_id=a.id) "
            + "AND NOT EXISTS (SELECT 1 FROM dh_governance_master_distribute_log r WHERE r.id=a.run_id) "
            + "ORDER BY a.id LIMIT 100)")
    int cleanDelivered();
}
