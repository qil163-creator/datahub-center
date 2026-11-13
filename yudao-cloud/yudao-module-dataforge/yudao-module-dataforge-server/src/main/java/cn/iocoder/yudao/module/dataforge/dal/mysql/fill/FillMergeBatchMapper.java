package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillMergeBatchPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillMergeBatchDO;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillMergeBatchStatusEnum;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 在线填报-合并批次 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillMergeBatchMapper extends BaseMapperX<FillMergeBatchDO> {

    /**
     * 按提交编号查询填报合并批次。
     *
     * @param submissionId 提交编号
     * @return 填报合并批次
     */
    default FillMergeBatchDO selectBySubmissionId(Long submissionId) {
        return selectOne(FillMergeBatchDO::getSubmissionId, submissionId);
    }

    /**
     * 按查询条件分页读取填报合并批次。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取填报合并批次结果
     */
    default PageResult<FillMergeBatchDO> selectPage(FillMergeBatchPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<FillMergeBatchDO>()
                .eqIfPresent(FillMergeBatchDO::getTaskId, reqVO.getTaskId())
                .eqIfPresent(FillMergeBatchDO::getWorkItemId, reqVO.getWorkItemId())
                .eqIfPresent(FillMergeBatchDO::getStatus, reqVO.getStatus())
                .likeIfPresent(FillMergeBatchDO::getTargetTableName, reqVO.getTargetTableName())
                .orderByDesc(FillMergeBatchDO::getId));
    }

    /**
     * 查询可执行的合并批次：
     * 待执行；失败且未超过自动重试上限、已到重试时间；执行中但开始时间早于超时点（进程中断遗留）。
     *
     * @param now 当前时间
     * @param staleBefore 执行中批次的超时时间点
     * @param maxAutoRetry 自动重试次数上限，超过后只能人工重试或退回整改
     * @param limit 最大返回条数
     * @return 可执行的合并批次
     */
    default List<FillMergeBatchDO> selectListToExecute(LocalDateTime now, LocalDateTime staleBefore,
            int maxAutoRetry, int limit) {
        return selectList(new LambdaQueryWrapperX<FillMergeBatchDO>()
                .and(wrapper -> wrapper
                        .eq(FillMergeBatchDO::getStatus, FillMergeBatchStatusEnum.PENDING.getStatus())
                        .or(failed -> failed
                                .eq(FillMergeBatchDO::getStatus, FillMergeBatchStatusEnum.FAILED.getStatus())
                                .lt(FillMergeBatchDO::getRetryCount, maxAutoRetry)
                                .and(due -> due.isNull(FillMergeBatchDO::getNextRetryTime)
                                        .or().le(FillMergeBatchDO::getNextRetryTime, now)))
                        .or(running -> running
                                .eq(FillMergeBatchDO::getStatus, FillMergeBatchStatusEnum.RUNNING.getStatus())
                                .lt(FillMergeBatchDO::getStartedAt, staleBefore)))
                .orderByAsc(FillMergeBatchDO::getId)
                .last("LIMIT " + limit));
    }

    /**
     * 执行抢占 CAS：从 待执行/失败 迁移到 执行中，防止多实例重复执行
     *
     * @return 影响行数，0 表示被其他实例抢占
     */
    @Update("UPDATE dh_dataforge_fill_merge_batch "
            + "SET status = 1, started_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int compareAndSetRunning(@Param("id") Long id, @Param("fromStatus") Integer fromStatus);

    /**
     * 接管执行超时的批次：仅当仍为执行中且开始时间早于超时点时抢占成功，并刷新开始时间。
     *
     * @param id 批次编号
     * @param staleBefore 超时时间点
     * @return 影响行数，1 表示接管成功
     */
    @Update("UPDATE dh_dataforge_fill_merge_batch "
            + "SET started_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 1 AND started_at < #{staleBefore} AND deleted = 0")
    int takeOverStaleRunning(@Param("id") Long id, @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * 批次状态 CAS 迁移。
     *
     * @param id 批次编号
     * @param fromStatus 期望的当前状态
     * @param toStatus 目标状态
     * @return 影响行数
     */
    @Update("UPDATE dh_dataforge_fill_merge_batch "
            + "SET status = #{toStatus}, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int compareAndSetStatus(@Param("id") Long id, @Param("fromStatus") Integer fromStatus,
            @Param("toStatus") Integer toStatus);

}
