package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskObjectDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据治理-数据质量-任务检查对象关联 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityTaskObjectMapper extends BaseMapperX<QualityTaskObjectDO> {

    /**
     * 按任务编号查询质量任务对象列表。
     */
    default List<QualityTaskObjectDO> selectListByTaskId(Long taskId) {
        return selectList(new LambdaQueryWrapperX<QualityTaskObjectDO>()
                .eq(QualityTaskObjectDO::getTaskId, taskId)
                .orderByAsc(QualityTaskObjectDO::getId));
    }

    /**
     * 按任务编号查询质量任务对象列表。
     */
    default List<QualityTaskObjectDO> selectListByTaskIds(java.util.Collection<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<QualityTaskObjectDO>()
                .in(QualityTaskObjectDO::getTaskId, taskIds));
    }

    /**
     * 按任务编号删除质量任务对象记录。
     */
    default void deleteByTaskId(Long taskId) {
        delete(new LambdaQueryWrapperX<QualityTaskObjectDO>()
                .eq(QualityTaskObjectDO::getTaskId, taskId));
    }

    /**
     * 按对象编号查询质量任务对象数量。
     */
    default Long selectCountByObjectId(Long objectId) {
        return selectCount(new LambdaQueryWrapperX<QualityTaskObjectDO>()
                .eq(QualityTaskObjectDO::getObjectId, objectId));
    }

    /**
     * 按对象编号查询质量任务对象列表。
     */
    default List<QualityTaskObjectDO> selectListByObjectId(Long objectId) {
        return selectList(new LambdaQueryWrapperX<QualityTaskObjectDO>()
                .eq(QualityTaskObjectDO::getObjectId, objectId)
                .orderByAsc(QualityTaskObjectDO::getId));
    }

}
