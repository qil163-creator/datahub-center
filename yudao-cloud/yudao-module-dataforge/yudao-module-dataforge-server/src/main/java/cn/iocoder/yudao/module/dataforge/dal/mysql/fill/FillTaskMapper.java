package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 在线填报-周期任务 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillTaskMapper extends BaseMapperX<FillTaskDO> {

    /**
     * 首次冻结审批流程版本：仅当任务尚未冻结时写入，已冻结的版本不会被覆盖。
     *
     * @param id 任务编号
     * @param processDefinitionId 流程定义编号
     * @return 影响行数，0 表示已被冻结（或任务不存在）
     */
    default int freezeProcessDefinitionIfAbsent(Long id, String processDefinitionId) {
        return update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FillTaskDO>()
                .set(FillTaskDO::getProcessDefinitionId, processDefinitionId)
                .eq(FillTaskDO::getId, id)
                .isNull(FillTaskDO::getProcessDefinitionId));
    }

    /**
     * 管理员切换冻结版本：以读取时的旧值做 CAS，防止并发切换互相覆盖。
     *
     * @param id 任务编号
     * @param expected 读取时的冻结版本（可为 null）
     * @param processDefinitionId 新的流程定义编号
     * @return 影响行数，0 表示已被并发修改
     */
    default int switchProcessDefinition(Long id, String expected, String processDefinitionId) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FillTaskDO>()
                .set(FillTaskDO::getProcessDefinitionId, processDefinitionId)
                .eq(FillTaskDO::getId, id);
        if (expected == null) {
            wrapper.isNull(FillTaskDO::getProcessDefinitionId);
        } else {
            wrapper.eq(FillTaskDO::getProcessDefinitionId, expected);
        }
        return update(null, wrapper);
    }

    /**
     * 按查询条件分页读取填报任务。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取填报任务结果
     */
    default PageResult<FillTaskDO> selectPage(FillTaskPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<FillTaskDO>()
                .eqIfPresent(FillTaskDO::getPlanId, reqVO.getPlanId())
                .likeIfPresent(FillTaskDO::getName, reqVO.getName())
                .eqIfPresent(FillTaskDO::getStatus, reqVO.getStatus())
                .eqIfPresent(FillTaskDO::getPeriodKey, reqVO.getPeriodKey())
                .orderByDesc(FillTaskDO::getId));
    }

    /**
     * 按计划编号与所属期标识查询填报任务。
     *
     * @param planId 计划编号
     * @param periodKey 所属期标识
     * @return 填报任务
     */
    default FillTaskDO selectByPlanIdAndPeriodKey(Long planId, String periodKey) {
        return selectOne(new LambdaQueryWrapperX<FillTaskDO>()
                .eq(FillTaskDO::getPlanId, planId)
                .eq(FillTaskDO::getPeriodKey, periodKey));
    }

    /**
     * 同一计划中所属期紧邻在前的任务（上期数据带入的来源）。
     *
     * @param planId 计划编号
     * @param periodStart 当前任务所属期开始时间
     * @param excludeId 当前任务编号
     * @return 上期任务，没有时返回 null
     */
    /**
     * 同一计划中所属期在前的任务（按所属期倒序），用于选择任意历史期复用数据。
     *
     * @param planId 计划编号
     * @param periodStart 当前任务所属期开始时间
     * @param excludeId 当前任务编号
     * @param limit 上限
     * @return 历史任务
     */
    default java.util.List<FillTaskDO> selectEarlierTasks(Long planId, java.time.LocalDateTime periodStart,
                                                         Long excludeId, int limit) {
        return selectList(new LambdaQueryWrapperX<FillTaskDO>()
                .eq(FillTaskDO::getPlanId, planId)
                .lt(FillTaskDO::getPeriodStart, periodStart)
                .ne(FillTaskDO::getId, excludeId)
                .orderByDesc(FillTaskDO::getPeriodStart)
                .orderByDesc(FillTaskDO::getId)
                .last("LIMIT " + limit));
    }

    /**
     * 查询计划最近若干期任务（按所属期倒序），用于期次汇总。
     *
     * @param planId 计划编号
     * @param limit 上限
     * @return 任务
     */
    default java.util.List<FillTaskDO> selectLatestByPlanId(Long planId, int limit) {
        return selectList(new LambdaQueryWrapperX<FillTaskDO>()
                .eq(FillTaskDO::getPlanId, planId)
                .orderByDesc(FillTaskDO::getPeriodStart)
                .orderByDesc(FillTaskDO::getId)
                .last("LIMIT " + limit));
    }

    default FillTaskDO selectPreviousTask(Long planId, java.time.LocalDateTime periodStart, Long excludeId) {
        return selectOne(new LambdaQueryWrapperX<FillTaskDO>()
                .eq(FillTaskDO::getPlanId, planId)
                .lt(FillTaskDO::getPeriodStart, periodStart)
                .ne(FillTaskDO::getId, excludeId)
                .orderByDesc(FillTaskDO::getPeriodStart)
                .orderByDesc(FillTaskDO::getId)
                .last("LIMIT 1"));
    }

    /**
     * 查询已到开放时间但仍为“待开始”的任务。
     *
     * @param now 当前时间
     * @param limit 最大返回条数
     * @return 待开放任务
     */
    default List<FillTaskDO> selectListToOpen(LocalDateTime now, int limit) {
        return selectList(new LambdaQueryWrapperX<FillTaskDO>()
                .eq(FillTaskDO::getStatus, 0)
                .le(FillTaskDO::getOpenTime, now)
                .orderByAsc(FillTaskDO::getOpenTime)
                .last("LIMIT " + limit));
    }

    /**
     * 任务状态 CAS 迁移。
     *
     * @param id 任务编号
     * @param fromStatus 期望的当前状态
     * @param toStatus 目标状态
     * @return 影响行数
     */
    @Update("UPDATE dh_dataforge_fill_task "
            + "SET status = #{toStatus}, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int compareAndSetStatus(@Param("id") Long id, @Param("fromStatus") Integer fromStatus,
            @Param("toStatus") Integer toStatus);

}
