package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 在线填报-组织填报实例（工作项）Mapper
 *
 * 状态迁移与版本推进必须使用本类的 CAS 方法，禁止直接 updateById 改状态
 *
 * @author Li
 */
@Mapper
public interface FillWorkItemMapper extends BaseMapperX<FillWorkItemDO> {

    /**
     * 按任务编号查询填报工作项列表。
     *
     * @param taskId 任务编号
     * @return 按任务编号查询填报工作项列表结果集合
     */
    default List<FillWorkItemDO> selectListByTaskId(Long taskId) {
        return selectList(FillWorkItemDO::getTaskId, taskId);
    }

    /**
     * 按任务编号与组织编号查询填报工作项。
     *
     * @param taskId 任务编号
     * @param reportingOrgId 填报组织编号
     * @return 填报工作项
     */
    default FillWorkItemDO selectByTaskIdAndOrgId(Long taskId, Long reportingOrgId) {
        return selectOne(new LambdaQueryWrapperX<FillWorkItemDO>()
                .eq(FillWorkItemDO::getTaskId, taskId)
                .eq(FillWorkItemDO::getReportingOrgId, reportingOrgId));
    }

    /**
     * 按编号集合查询填报工作项列表。
     *
     * @param ids 记录编号集合
     * @return 按编号集合查询填报工作项列表结果集合
     */
    default List<FillWorkItemDO> selectListByIds(Collection<Long> ids) {
        return selectByIds(ids);
    }

    /**
     * 按填报组织编号查询填报工作项列表。
     *
     * @param reportingOrgId 填报组织编号
     * @return 按填报组织编号查询填报工作项列表结果集合
     */
    default List<FillWorkItemDO> selectListByReportingOrgId(Long reportingOrgId) {
        return selectList(new LambdaQueryWrapperX<FillWorkItemDO>()
                .eq(FillWorkItemDO::getReportingOrgId, reportingOrgId)
                .orderByDesc(FillWorkItemDO::getId));
    }

    /**
     * 进行中的数据更正由发起者主动重新开放，不按原截止时间判定逾期或提醒，从候选中排除。
     */
    String NO_ACTIVE_CORRECTION = "NOT EXISTS (SELECT 1 FROM dh_dataforge_fill_correction c "
            + "WHERE c.work_item_id = dh_dataforge_fill_work_item.id AND c.status IN (0, 1) AND c.deleted = 0)";

    /**
     * 逾期候选：未提交（待填报/填报中/整改中）、尚未标记逾期且已过截止时间的工作项。
     *
     * @param now 当前时间
     * @param limit 最大返回条数
     * @return 逾期候选
     */
    default List<FillWorkItemDO> selectOverdueCandidates(java.time.LocalDateTime now, int limit) {
        return selectList(new LambdaQueryWrapperX<FillWorkItemDO>()
                .in(FillWorkItemDO::getStatus, 0, 1, 5)
                .eq(FillWorkItemDO::getOverdue, 0)
                .isNotNull(FillWorkItemDO::getDeadlineTime)
                .lt(FillWorkItemDO::getDeadlineTime, now)
                .apply(NO_ACTIVE_CORRECTION)
                .last("LIMIT " + limit));
    }

    /**
     * 临近截止提醒候选，按（截止时间，编号）升序分页；传入上一页最后一条的截止时间与编号取下一页。
     *
     * @param now 当前时间
     * @param until 截止时间上界
     * @param afterDeadline 上一页最后一条的截止时间，首页传 null
     * @param afterId 上一页最后一条的编号，首页传 null
     * @param limit 每页条数
     * @return 提醒候选
     */
    default List<FillWorkItemDO> selectRemindCandidates(java.time.LocalDateTime now,
            java.time.LocalDateTime until, java.time.LocalDateTime afterDeadline, Long afterId, int limit) {
        LambdaQueryWrapperX<FillWorkItemDO> query = new LambdaQueryWrapperX<>();
        query.in(FillWorkItemDO::getStatus, 0, 1, 5)
                .eq(FillWorkItemDO::getOverdue, 0)
                .isNotNull(FillWorkItemDO::getDeadlineTime)
                .ge(FillWorkItemDO::getDeadlineTime, now)
                .le(FillWorkItemDO::getDeadlineTime, until)
                .apply(NO_ACTIVE_CORRECTION);
        if (afterDeadline != null && afterId != null) {
            query.and(cursor -> cursor.gt(FillWorkItemDO::getDeadlineTime, afterDeadline)
                    .or(same -> same.eq(FillWorkItemDO::getDeadlineTime, afterDeadline)
                            .gt(FillWorkItemDO::getId, afterId)));
        }
        query.orderByAsc(FillWorkItemDO::getDeadlineTime).orderByAsc(FillWorkItemDO::getId)
                .last("LIMIT " + limit);
        return selectList(query);
    }

    /**
     * 系统写入草稿（如上期数据带入）一次推进 delta 个版本：仅在可编辑状态且版本匹配时生效
     *
     * @return 影响行数，0 表示版本或状态已变化
     */
    @Update("UPDATE dh_dataforge_fill_work_item "
            + "SET server_version = server_version + #{delta}, "
            + "    last_modify_user_id = #{userId}, last_modify_time = CURRENT_TIMESTAMP, "
            + "    update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND server_version = #{expectedVersion} "
            + "  AND status IN (0, 1, 5) AND deleted = 0")
    int advanceServerVersionBy(@Param("id") Long id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("delta") Long delta,
            @Param("userId") Long userId);

    /**
     * 状态 CAS：仅当当前状态等于 fromStatus 时迁移到 toStatus
     *
     * @return 影响行数，0 表示状态已被并发修改
     */
    @Update("UPDATE dh_dataforge_fill_work_item "
            + "SET status = #{toStatus}, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int compareAndSetStatus(@Param("id") Long id,
            @Param("fromStatus") Integer fromStatus,
            @Param("toStatus") Integer toStatus);

    /**
     * 提交冻结 CAS：状态与服务端版本同时匹配才允许迁移（编辑与提交竞争同一行版本）
     *
     * @return 影响行数，0 表示版本或状态已变化
     */
    @Update("UPDATE dh_dataforge_fill_work_item "
            + "SET status = #{toStatus}, server_version = server_version + 1, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = #{fromStatus} AND server_version = #{expectedVersion} AND deleted = 0")
    int compareAndSetStatusWithVersion(@Param("id") Long id,
            @Param("fromStatus") Integer fromStatus,
            @Param("toStatus") Integer toStatus,
            @Param("expectedVersion") Long expectedVersion);

    /**
     * 草稿操作推进版本：仅在可编辑状态下自增 serverVersion 并返回影响行数
     */
    @Update("UPDATE dh_dataforge_fill_work_item "
            + "SET server_version = server_version + 1, "
            + "    last_modify_user_id = #{userId}, last_modify_time = CURRENT_TIMESTAMP, "
            + "    update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND server_version = #{expectedVersion} "
            + "  AND status IN (0, 1, 5) AND deleted = 0")
    int advanceServerVersion(@Param("id") Long id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("userId") Long userId);

}
