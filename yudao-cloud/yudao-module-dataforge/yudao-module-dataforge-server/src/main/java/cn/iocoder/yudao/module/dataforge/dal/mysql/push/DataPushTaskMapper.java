package cn.iocoder.yudao.module.dataforge.dal.mysql.push;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskDO;
import cn.iocoder.yudao.module.dataforge.enums.push.PushTaskTypeEnum;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Mapper
public interface DataPushTaskMapper extends BaseMapperX<DataPushTaskDO> {

    record CategoryTaskCount(Long id, Long taskCount) {}

    @Select("SELECT category_id AS id, count(*) AS task_count FROM dh_dataforge_push_task "
            + "WHERE deleted = 0 AND category_id IS NOT NULL GROUP BY category_id")
    List<CategoryTaskCount> selectCategoryTaskCounts();

    /**
     * 锁定并读取数据推送任务记录。
     *
     * @param id 记录编号
     * @return 数据推送任务
     */
    @Select("SELECT * FROM dh_dataforge_push_task WHERE id=#{id} AND deleted=0 FOR UPDATE")
    DataPushTaskDO lockOwned(@Param("id") Long id);

    /**
     * 按查询条件分页读取数据推送任务。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取数据推送任务结果
     */
    default PageResult<DataPushTaskDO> selectPage(DataPushTaskPageReqVO reqVO, List<Long> categoryIds) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DataPushTaskDO>()
                .inIfPresent(DataPushTaskDO::getCategoryId, categoryIds)
                .likeIfPresent(DataPushTaskDO::getName, reqVO.getName())
                .eqIfPresent(DataPushTaskDO::getPushType, reqVO.getPushType())
                .eqIfPresent(DataPushTaskDO::getPushMode, reqVO.getPushMode())
                .eqIfPresent(DataPushTaskDO::getTaskType, reqVO.getTaskType())
                .eqIfPresent(DataPushTaskDO::getStatus, reqVO.getStatus())
                .eqIfPresent(DataPushTaskDO::getSourceDatabaseId, reqVO.getSourceDatabaseId())
                .eqIfPresent(DataPushTaskDO::getOrgId, reqVO.getOrgId())
                .orderByDesc(DataPushTaskDO::getId));
    }

    /**
     * 按配置编号查询数据推送任务数量。
     *
     * @param pushConfigId 推送配置编号
     * @return SELECT数量按配置编号数值
     */
    default Long selectCountByConfigId(Long pushConfigId) {
        return selectCount(DataPushTaskDO::getPushConfigId, pushConfigId);
    }

    /**
     * 按配置编号查询已发布数量。
     *
     * @param pushConfigId 推送配置编号
     * @return SELECT已发布数量按配置编号数值
     */
    default Long selectReleasedCountByConfigId(Long pushConfigId) {
        return selectCount(new LambdaQueryWrapperX<DataPushTaskDO>()
                .eq(DataPushTaskDO::getPushConfigId, pushConfigId)
                .eq(DataPushTaskDO::getStatus, DataPushTaskDO.STATUS_RELEASED));
    }

    /**
     * 查询已发布配置编号集合。
     *
     * @param configIds 配置编号集合
     * @return 已发布配置编号集合结果集合
     */
    default Set<Long> selectReleasedConfigIds(Collection<Long> configIds) {
        if (configIds == null || configIds.isEmpty()) {
            return Collections.emptySet();
        }
        return selectList(new LambdaQueryWrapperX<DataPushTaskDO>()
                .in(DataPushTaskDO::getPushConfigId, configIds)
                .eq(DataPushTaskDO::getStatus, DataPushTaskDO.STATUS_RELEASED)
                .select(DataPushTaskDO::getPushConfigId))
                .stream()
                .map(DataPushTaskDO::getPushConfigId)
                .collect(Collectors.toSet());
    }

    /**
     * 查询到期列表。
     *
     * @param now 当前时间
     * @return 到期列表结果集合
     */
    default List<DataPushTaskDO> selectDueList(LocalDateTime now) {
        return selectList(new LambdaQueryWrapperX<DataPushTaskDO>()
                .eq(DataPushTaskDO::getStatus, DataPushTaskDO.STATUS_RELEASED)
                .eq(DataPushTaskDO::getTaskType, PushTaskTypeEnum.PERIODIC.getType())
                .isNotNull(DataPushTaskDO::getNextRunTime)
                .le(DataPushTaskDO::getNextRunTime, now)
                .notExists("SELECT 1 FROM dh_dataforge_push_task_log r WHERE r.push_task_id=dh_dataforge_push_task.id "
                        + "AND r.deleted=0 AND r.run_status IN (1,2,5)")
                .orderByAsc(DataPushTaskDO::getNextRunTime).orderByAsc(DataPushTaskDO::getId)
                .last("LIMIT 200"));
    }

}
