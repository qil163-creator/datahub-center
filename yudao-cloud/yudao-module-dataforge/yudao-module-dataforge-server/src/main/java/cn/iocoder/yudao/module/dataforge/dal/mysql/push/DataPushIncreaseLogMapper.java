package cn.iocoder.yudao.module.dataforge.dal.mysql.push;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushIncreaseLogDO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Mapper
public interface DataPushIncreaseLogMapper extends BaseMapperX<DataPushIncreaseLogDO> {

    /**
     * 查询最新。
     *
     * @param pushTaskId 推送任务编号
     * @return 推送增量日志
     */
    default DataPushIncreaseLogDO selectNewest(Long pushTaskId) {
        return selectOne(new LambdaQueryWrapperX<DataPushIncreaseLogDO>()
                .eq(DataPushIncreaseLogDO::getPushTaskId, pushTaskId)
                .orderByDesc(DataPushIncreaseLogDO::getId)
                .last("LIMIT 1"));
    }

    /**
     * 按任务编号集合查询最新。
     *
     * @param taskIds 任务编号集合
     * @return 按任务编号集合查询最新结果集合
     */
    default List<DataPushIncreaseLogDO> selectNewestByTaskIds(Collection<Long> taskIds) {
        if (CollUtil.isEmpty(taskIds)) {
            return Collections.emptyList();
        }
        List<Object> ids = selectObjs(new QueryWrapper<DataPushIncreaseLogDO>()
                .select("MAX(id)")
                .in("push_task_id", taskIds)
                .groupBy("push_task_id"));
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return selectByIds(ids.stream().map(id -> ((Number) id).longValue()).toList());
    }

    /**
     * 新增或更新任务的最新增量水位，并清理该任务的多余历史水位记录。
     *
     * @param row 当前行数据
     */
    default void upsert(DataPushIncreaseLogDO row) {
        DataPushIncreaseLogDO last = selectNewest(row.getPushTaskId());
        if (last == null) {
            insert(row);
            return;
        }
        last.setIncreaseColumn(row.getIncreaseColumn());
        last.setStartVal(row.getStartVal());
        last.setEndVal(row.getEndVal());
        updateById(last);
        delete(new LambdaQueryWrapperX<DataPushIncreaseLogDO>()
                .eq(DataPushIncreaseLogDO::getPushTaskId, row.getPushTaskId())
                .ne(DataPushIncreaseLogDO::getId, last.getId()));
    }

    /**
     * 按任务编号删除推送增量日志。
     *
     * @param pushTaskId 推送任务编号
     * @return 符合条件或受影响的记录数量
     */
    default int deleteByTaskId(Long pushTaskId) {
        return delete(new LambdaQueryWrapperX<DataPushIncreaseLogDO>()
                .eq(DataPushIncreaseLogDO::getPushTaskId, pushTaskId));
    }

}
