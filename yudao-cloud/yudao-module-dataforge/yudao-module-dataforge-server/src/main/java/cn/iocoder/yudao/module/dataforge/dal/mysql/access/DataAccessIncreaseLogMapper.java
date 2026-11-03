package cn.iocoder.yudao.module.dataforge.dal.mysql.access;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessIncreaseLogDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据同步任务-增量同步日志 Mapper
 *
 * @author Li
 */
@Mapper
public interface DataAccessIncreaseLogMapper extends BaseMapperX<DataAccessIncreaseLogDO> {

    /**
     * 查询某张表最近一次的增量日志
     */
    default DataAccessIncreaseLogDO selectNewest(Long dataAccessId, String schemaName,
            String tableName, String increaseColumn) {
        return selectOne(new LambdaQueryWrapperX<DataAccessIncreaseLogDO>()
                .eq(DataAccessIncreaseLogDO::getDataAccessId, dataAccessId)
                .eqIfPresent(DataAccessIncreaseLogDO::getSchemaName, schemaName)
                .eq(DataAccessIncreaseLogDO::getTableName, tableName)
                .eq(DataAccessIncreaseLogDO::getIncreaseColumn, increaseColumn)
                .orderByDesc(DataAccessIncreaseLogDO::getId)
                .last("LIMIT 1"));
    }

    /**
     * 按数据同步任务编号删除数据同步任务增量日志。
     *
     * @param dataAccessId 数据同步任务编号
     */
    default void deleteByDataAccessId(Long dataAccessId) {
        delete(new LambdaQueryWrapperX<DataAccessIncreaseLogDO>()
                .eq(DataAccessIncreaseLogDO::getDataAccessId, dataAccessId));
    }

}
