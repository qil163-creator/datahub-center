package cn.iocoder.yudao.module.dataforge.dal.mysql.dev;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskVersionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据开发-任务版本 Mapper
 *
 * @author Li
 */
@Mapper
public interface DevTaskVersionMapper extends BaseMapperX<DevTaskVersionDO> {

    /**
     * 按任务编号查询开发任务版本列表。
     *
     * @param taskId 任务编号
     * @return 按任务编号查询开发任务版本列表结果集合
     */
    default List<DevTaskVersionDO> selectListByTaskId(Long taskId) {
        return selectList(new LambdaQueryWrapperX<DevTaskVersionDO>()
                .eq(DevTaskVersionDO::getTaskId, taskId)
                .orderByDesc(DevTaskVersionDO::getVersion));
    }

    /**
     * 按任务编号与版本查询开发任务版本。
     *
     * @param taskId 任务编号
     * @param version 版本
     * @return 开发任务版本
     */
    default DevTaskVersionDO selectByTaskIdAndVersion(Long taskId, Integer version) {
        return selectOne(new LambdaQueryWrapperX<DevTaskVersionDO>()
                .eq(DevTaskVersionDO::getTaskId, taskId)
                .eq(DevTaskVersionDO::getVersion, version));
    }

    /**
     * 按任务编号删除开发任务版本。
     *
     * @param taskId 任务编号
     */
    default void deleteByTaskId(Long taskId) {
        delete(new LambdaQueryWrapperX<DevTaskVersionDO>().eq(DevTaskVersionDO::getTaskId, taskId));
    }

}
