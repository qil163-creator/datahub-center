package cn.iocoder.yudao.module.dataforge.dal.mysql.access;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessCheckpointDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DataAccessCheckpointMapper extends BaseMapperX<DataAccessCheckpointDO> {

    /**
     * 读取当前数据同步任务检查点。
     *
     * @param task 任务记录
     * @param schema 结构
     * @param table 表
     * @return 数据同步任务检查点
     */
    default DataAccessCheckpointDO current(Long task, String schema, String table) {
        return selectOne(new LambdaQueryWrapperX<DataAccessCheckpointDO>()
                .eq(DataAccessCheckpointDO::getDataAccessId, task)
                .eq(DataAccessCheckpointDO::getSchemaName, schema).eq(DataAccessCheckpointDO::getTableName, table));
    }

    /**
     * 更新数据同步任务检查点的比较并设置。
     *
     * @param id 记录编号
     * @param task 任务记录
     * @param schema 结构
     * @param table 表
     * @param revision 修订号
     * @param run 运行
     * @param json JSON 内容
     * @return 比较并设置数值
     */
    @Update("UPDATE dh_dataforge_access_checkpoint SET checkpoint_json=CAST(#{json} AS jsonb),"
            + "revision=revision+1,owner_run_id=#{run},update_time=clock_timestamp() WHERE id=#{id} "
            + "AND data_access_id=#{task} AND schema_name=#{schema} AND table_name=#{table} "
            + "AND revision=#{revision} AND deleted=0")
    int compareAndSet(@Param("id") Long id, @Param("task") Long task,
            @Param("schema") String schema, @Param("table") String table, @Param("revision") long revision,
            @Param("run") Long run, @Param("json") String json);
}
