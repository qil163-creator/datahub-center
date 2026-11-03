package cn.iocoder.yudao.module.dataforge.dal.mysql.dev;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据开发-任务执行实例 Mapper
 *
 * @author Li
 */
@Mapper
public interface DevRecordMapper extends BaseMapperX<DevRecordDO> {

    /**
     * 统计开发记录的有效任务数量。
     *
     * @return 符合条件或受影响的记录数量
     */
    default Long countActiveTasks() {
        return countActiveTasks(null);
    }

    /** 运行模式按当前有效任务归属过滤；同一任务的多个活动实例只计一次。 */
    @org.apache.ibatis.annotations.Select("<script>SELECT COUNT(DISTINCT r.task_id) FROM dh_dataforge_dev_record r "
            + "WHERE r.deleted = 0 AND r.status IN ('WAITING','SUBMITTING','RUNNING','UNKNOWN','CANCEL_REQUESTED') "
            + "<if test='workloadMode != null'>AND EXISTS (SELECT 1 FROM dh_dataforge_dev_task t "
            + "WHERE t.id = r.task_id AND t.deleted = 0 "
            + "AND (" + DevTaskMapper.WORKLOAD_MODE_JOIN_SQL + ") = #{workloadMode})</if></script>")
    Long countActiveTasks(@org.apache.ibatis.annotations.Param("workloadMode") String workloadMode);

    /**
     * 统计已开始区间。
     *
     * @param start 开始位置或时间
     * @param end 结束位置或时间
     * @param status 目标状态
     * @return 符合条件或受影响的记录数量
     */
    default Long countStartedBetween(java.time.LocalDateTime start, java.time.LocalDateTime end, String status) {
        return countStartedBetween(start, end, status, null);
    }

    /** 按开始时间及当前任务运行模式聚合，沿用列表的历史批任务兼容规则。 */
    default Long countStartedBetween(java.time.LocalDateTime start, java.time.LocalDateTime end, String status,
            String workloadMode) {
        var query = new LambdaQueryWrapperX<DevRecordDO>().eqIfPresent(DevRecordDO::getStatus, status)
                .ge(DevRecordDO::getStartTime, start).lt(DevRecordDO::getStartTime, end);
        if (workloadMode != null) {
            query.apply("EXISTS (SELECT 1 FROM dh_dataforge_dev_task t "
                    + "WHERE t.id = dh_dataforge_dev_record.task_id AND t.deleted = 0 "
                    + "AND (" + DevTaskMapper.WORKLOAD_MODE_JOIN_SQL + ") = {0})", workloadMode);
        }
        return selectCount(query);
    }

    /**
     * 按任务编号集合查询最新。
     *
     * @param taskIds 任务编号集合
     * @return 按任务编号集合查询最新结果集合
     */
    default java.util.List<DevRecordDO> selectLatestByTaskIds(java.util.Collection<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return java.util.List.of();
        }
        return selectList(new LambdaQueryWrapperX<DevRecordDO>().in(DevRecordDO::getTaskId, taskIds)
                .apply("id = (SELECT MAX(r.id) FROM dh_dataforge_dev_record r WHERE r.task_id = dh_dataforge_dev_record.task_id "
                        + "AND r.deleted = 0)"));
    }

    /**
     * 查询已受理依赖。
     *
     * @param taskId 任务编号
     * @param versionId 版本编号
     * @param businessDate 业务日期
     * @return 开发记录
     */
    default DevRecordDO selectAcceptedDependency(Long taskId, Long versionId, java.time.LocalDate businessDate) {
        return selectOne(new LambdaQueryWrapperX<DevRecordDO>()
                .eq(DevRecordDO::getTaskId, taskId).eq(DevRecordDO::getVersionId, versionId)
                .eq(DevRecordDO::getBusinessDate, businessDate).eq(DevRecordDO::getStatus, DevRecordDO.STATUS_SUCCESS)
                .in(DevRecordDO::getQualityStatus, java.util.List.of("PASSED", "NOT_CONFIGURED"))
                .orderByDesc(DevRecordDO::getId).last("LIMIT 1"));
    }

    /**
     * 查询最新依赖。
     *
     * @param taskId 任务编号
     * @param versionId 版本编号
     * @param businessDate 业务日期
     * @return 开发记录
     */
    default DevRecordDO selectLatestDependency(Long taskId, Long versionId, java.time.LocalDate businessDate) {
        return selectOne(new LambdaQueryWrapperX<DevRecordDO>()
                .eq(DevRecordDO::getTaskId, taskId).eqIfPresent(DevRecordDO::getVersionId, versionId)
                .eq(DevRecordDO::getBusinessDate, businessDate)
                .orderByDesc(DevRecordDO::getId).last("LIMIT 1"));
    }

    /**
     * 按查询条件分页读取开发记录。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取开发记录结果
     */
    default PageResult<DevRecordDO> selectPage(DevRecordPageReqVO reqVO) {
        var query = new LambdaQueryWrapperX<DevRecordDO>()
                .likeIfPresent(DevRecordDO::getTaskName, reqVO.getTaskName())
                .eqIfPresent(DevRecordDO::getTaskId, reqVO.getTaskId())
                .eqIfPresent(DevRecordDO::getTriggerType, reqVO.getTriggerType())
                .betweenIfPresent(DevRecordDO::getStartTime, reqVO.getStartTime())
                .orderByDesc(DevRecordDO::getId);
        if (DevRecordDO.STATUS_RUNNING.equals(reqVO.getStatus())) {
            query.in(DevRecordDO::getStatus, DevRecordDO.ACTIVE_STATUSES);
        } else {
            query.eqIfPresent(DevRecordDO::getStatus, reqVO.getStatus());
        }
        return selectPage(reqVO, query);
    }

}
