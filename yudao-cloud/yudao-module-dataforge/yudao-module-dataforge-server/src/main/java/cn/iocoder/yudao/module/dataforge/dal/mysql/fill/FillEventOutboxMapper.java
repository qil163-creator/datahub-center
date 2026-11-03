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

    /** 投递 Job 扫描：待发送 或 失败且到达重试时间 */
    default List<FillEventOutboxDO> selectListToSend(LocalDateTime now, int limit) {
        return selectList(new LambdaQueryWrapperX<FillEventOutboxDO>()
                .in(FillEventOutboxDO::getStatus,
                        FillOutboxStatusEnum.PENDING.getStatus(),
                        FillOutboxStatusEnum.FAILED.getStatus())
                .and(wrapper -> wrapper
                        .isNull(FillEventOutboxDO::getNextRetryTime)
                        .or().le(FillEventOutboxDO::getNextRetryTime, now))
                .orderByAsc(FillEventOutboxDO::getId)
                .last("LIMIT " + limit));
    }

    /**
     * 投递抢占 CAS：迁移到发送中，防止多实例重复投递
     *
     * @return 影响行数，0 表示被其他实例抢占
     */
    @Update("UPDATE dh_dataforge_fill_event_outbox "
            + "SET status = 1, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int compareAndSetSending(@Param("id") Long id, @Param("fromStatus") Integer fromStatus);

}
