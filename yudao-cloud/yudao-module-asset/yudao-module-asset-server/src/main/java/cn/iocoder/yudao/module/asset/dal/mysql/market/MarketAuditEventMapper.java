package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketAuditEventDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MarketAuditEventMapper extends BaseMapperX<MarketAuditEventDO> {

    /** 人工交付核查与状态 CAS 在同一事务；显式记录操作人和对象。 */
    @Insert("""
            INSERT INTO dh_market_audit_event
              (event_id,event_type,actor_user_id,dept_id,object_type,object_id,
               from_status,to_status,reason,result,occurred_at)
            VALUES (#{event.eventId},#{event.eventType},#{event.actorUserId},#{event.deptId},
                    #{event.objectType},#{event.objectId},#{event.fromStatus},#{event.toStatus},
                    #{event.reason},#{event.result},#{event.occurredAt})
            """)
    int insertDeliveryReview(@Param("event") MarketAuditEventDO event);

    /** 普通领域审计：显式记录操作人与数据库时钟；调用方核对恰好一行，并与业务写入同一事务提交。 */
    @Insert("""
            INSERT INTO dh_market_audit_event
              (event_id,event_type,actor_user_id,dept_id,object_type,object_id,
               from_status,to_status,reason,result,occurred_at,creator,updater)
            VALUES (#{event.eventId},#{event.eventType},#{event.actorUserId},#{event.deptId},
                    #{event.objectType},#{event.objectId},#{event.fromStatus},#{event.toStatus},
                    #{event.reason},#{event.result},clock_timestamp() AT TIME ZONE 'Asia/Shanghai',
                    #{event.creator},#{event.updater})
            """)
    int insertEvent(@Param("event") MarketAuditEventDO event);
}
