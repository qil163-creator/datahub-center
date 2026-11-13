package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillNotifyRecordDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 在线填报-通知与催办记录 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillNotifyRecordMapper extends BaseMapperX<FillNotifyRecordDO> {

    /** 催办频控：统计某工作项某类型在指定时间之后的发送次数 */
    default long selectCountSince(Long workItemId, Integer notifyType, LocalDateTime since) {
        return selectCount(new LambdaQueryWrapperX<FillNotifyRecordDO>()
                .eq(FillNotifyRecordDO::getWorkItemId, workItemId)
                .eq(FillNotifyRecordDO::getNotifyType, notifyType)
                .ge(FillNotifyRecordDO::getCreateTime, since));
    }

    /**
     * 查询需要重试的通知：发送失败，或停留在待发送（进程中断遗留）；带有参数快照、未超过重试上限、
     * 最后更新时间早于重试间隔且仍在重试时间窗内。
     *
     * @param updatedBefore 最后更新时间上界（重试间隔）
     * @param createdAfter 创建时间下界（重试时间窗）
     * @param maxRetry 重试次数上限
     * @param limit 最大返回条数
     * @return 待重试通知
     */
    default List<FillNotifyRecordDO> selectListToRetry(LocalDateTime updatedBefore, LocalDateTime createdAfter,
            int maxRetry, int limit) {
        return selectList(new LambdaQueryWrapperX<FillNotifyRecordDO>()
                .in(FillNotifyRecordDO::getSendStatus, 0, 2)
                .isNotNull(FillNotifyRecordDO::getTemplateParams)
                .lt(FillNotifyRecordDO::getRetryCount, maxRetry)
                .le(FillNotifyRecordDO::getUpdateTime, updatedBefore)
                .ge(FillNotifyRecordDO::getCreateTime, createdAfter)
                .orderByAsc(FillNotifyRecordDO::getId)
                .last("LIMIT " + limit));
    }

    /**
     * 取得一条通知的重试权：重试次数加一并刷新更新时间，多实例并发只有一个成功。
     *
     * @param id 通知记录编号
     * @param retryCount 期望的当前重试次数
     * @return 影响行数，1 表示可以重试
     */
    @Update("UPDATE dh_dataforge_fill_notify_record "
            + "SET send_status = 0, retry_count = retry_count + 1, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND retry_count = #{retryCount} AND send_status IN (0, 2) AND deleted = 0")
    int claimForRetry(@Param("id") Long id, @Param("retryCount") Integer retryCount);

}
