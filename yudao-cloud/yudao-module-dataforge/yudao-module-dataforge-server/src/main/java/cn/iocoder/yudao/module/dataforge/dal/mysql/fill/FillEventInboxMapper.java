package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillEventInboxDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 在线填报-外部事件收件箱 Mapper（唯一键 (event_id) 为幂等最终保障）
 *
 * @author Li
 */
@Mapper
public interface FillEventInboxMapper extends BaseMapperX<FillEventInboxDO> {

    /**
     * 按事件编号查询填报事件收件箱。
     *
     * @param eventId 事件编号
     * @return 填报事件收件箱
     */
    default FillEventInboxDO selectByEventId(String eventId) {
        return selectOne(FillEventInboxDO::getEventId, eventId);
    }

    /**
     * 重新取得一条已存在事件的处理权：处理失败的事件，或已接收但迟迟未处理完（进程中断遗留）的事件。
     * 成功时状态回到“已接收”并刷新更新时间，并发重投只有一个成功。
     *
     * @param id 收件箱主键
     * @param staleBefore 已接收事件的超时时间点
     * @return 影响行数，1 表示可以重新处理
     */
    @Update("UPDATE dh_dataforge_fill_event_inbox "
            + "SET status = 0, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND deleted = 0 "
            + "  AND (status = 3 OR (status = 0 AND update_time < #{staleBefore}))")
    int reacquireForRetry(@Param("id") Long id, @Param("staleBefore") LocalDateTime staleBefore);

}
