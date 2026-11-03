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

    /** 合并 Job 扫描：待执行 或 失败且到达重试时间 */
    default List<FillMergeBatchDO> selectListToExecute(LocalDateTime now, int limit) {
        return selectList(new LambdaQueryWrapperX<FillMergeBatchDO>()
                .in(FillMergeBatchDO::getStatus,
                        FillMergeBatchStatusEnum.PENDING.getStatus(),
                        FillMergeBatchStatusEnum.FAILED.getStatus())
                .and(wrapper -> wrapper
                        .isNull(FillMergeBatchDO::getNextRetryTime)
                        .or().le(FillMergeBatchDO::getNextRetryTime, now))
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

}
