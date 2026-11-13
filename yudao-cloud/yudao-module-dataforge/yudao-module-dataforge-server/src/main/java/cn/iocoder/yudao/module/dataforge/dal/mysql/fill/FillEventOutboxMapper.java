package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillEventOutboxDO;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillOutboxStatusEnum;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 在线填报-可靠事件发件箱 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillEventOutboxMapper extends BaseMapperX<FillEventOutboxDO> {

    /**
     * 查询待投递事件：待发送；失败且已到重试时间；发送中但最后更新时间早于超时点（进程中断遗留）。
     *
     * @param now 当前时间
     * @param staleBefore 发送中事件的超时时间点
     * @param limit 最大返回条数
     * @return 待投递事件
     */
    default List<FillEventOutboxDO> selectListToSend(LocalDateTime now, LocalDateTime staleBefore, int limit) {
        return selectList(new LambdaQueryWrapperX<FillEventOutboxDO>()
                .and(wrapper -> wrapper
                        .and(pending -> pending
                                .in(FillEventOutboxDO::getStatus,
                                        FillOutboxStatusEnum.PENDING.getStatus(),
                                        FillOutboxStatusEnum.FAILED.getStatus())
                                .and(due -> due.isNull(FillEventOutboxDO::getNextRetryTime)
                                        .or().le(FillEventOutboxDO::getNextRetryTime, now)))
                        .or(sending -> sending
                                .eq(FillEventOutboxDO::getStatus, FillOutboxStatusEnum.SENDING.getStatus())
                                .lt(FillEventOutboxDO::getUpdateTime, staleBefore)))
                .orderByAsc(FillEventOutboxDO::getId)
                .last("LIMIT " + limit));
    }

    /**
     * 管理员切换任务审批流程版本后，把该任务下失败待重试的“启动审批”事件改为立即到期，
     * 由投递 Job 按新版本重新发起；成功、发送中的事件不受影响。
     *
     * @param taskId 任务编号
     * @param eventType 启动审批事件类型
     * @param now 当前时间
     * @return 影响行数
     */
    @Update("UPDATE dh_dataforge_fill_event_outbox SET next_retry_time = #{now}, update_time = CURRENT_TIMESTAMP "
            + "WHERE event_type = #{eventType} AND status = 3 AND deleted = 0 AND biz_id IN ("
            + "SELECT id FROM dh_dataforge_fill_submission WHERE task_id = #{taskId} AND deleted = 0)")
    int rescheduleFailedByTask(@Param("taskId") Long taskId, @Param("eventType") String eventType,
            @Param("now") LocalDateTime now);

    /**
     * 投递抢占 CAS：迁移到发送中，防止多实例重复投递
     *
     * @return 影响行数，0 表示被其他实例抢占
     */
    @Update("UPDATE dh_dataforge_fill_event_outbox "
            + "SET status = 1, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int compareAndSetSending(@Param("id") Long id, @Param("fromStatus") Integer fromStatus);

    /**
     * 接管发送超时的事件：仅当仍为发送中且最后更新时间早于超时点时抢占成功，并刷新更新时间。
     *
     * @param id 事件主键
     * @param staleBefore 超时时间点
     * @return 影响行数，1 表示接管成功
     */
    @Update("UPDATE dh_dataforge_fill_event_outbox "
            + "SET update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 1 AND update_time < #{staleBefore} AND deleted = 0")
    int takeOverStaleSending(@Param("id") Long id, @Param("staleBefore") LocalDateTime staleBefore);

}
