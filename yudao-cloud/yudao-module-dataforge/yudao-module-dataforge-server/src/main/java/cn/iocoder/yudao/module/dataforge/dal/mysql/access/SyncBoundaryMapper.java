package cn.iocoder.yudao.module.dataforge.dal.mysql.access;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 由运行 Mapper 继承；仅在真实短事务内分配，数据源维度锁顺序最后，事务中不得 RPC。 */
public interface SyncBoundaryMapper {

    record Boundary(String requestKey, Long databaseId, String configurationRevision, String product,
                    String schemaName, String tableName, Long position) {

    }

    /**
     * 新增同步边界的确保同步时钟记录。
     *
     * @param database 数据源
     * @return 确保同步时钟数值
     */
    @Insert("INSERT INTO dh_dataforge_sync_clock(database_id) VALUES(#{database}) ON CONFLICT DO NOTHING")
    @Options(timeout = 3)
    int ensureSyncClock(@Param("database") long database);

    /**
     * 锁定并读取同步边界记录。
     *
     * @param database 数据源
     * @return 锁同步时钟数值
     */
    @Select("SELECT last_position FROM dh_dataforge_sync_clock WHERE database_id=#{database} FOR UPDATE")
    @Options(timeout = 3, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockSyncClock(@Param("database") long database);

    /**
     * 获取同步边界的下次同步位置。
     *
     * @param database 数据源
     * @return 下次同步位置数值
     */
    @Select(value = "UPDATE dh_dataforge_sync_clock SET last_position=last_position+1,update_time=clock_timestamp() "
            + "WHERE database_id=#{database} RETURNING last_position", affectData = true)
    @Options(timeout = 3, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long nextSyncPosition(@Param("database") long database);

    /**
     * 查询同步边界的同步边界。
     *
     * @param key 标识键
     * @return 边界
     */
    @Select("SELECT request_key,database_id,configuration_revision,product,schema_name,table_name,position "
            + "FROM dh_dataforge_sync_boundary WHERE request_key=#{key}")
    @Options(timeout = 3, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Boundary findSyncBoundary(@Param("key") String key);

    /**
     * 新增同步边界的同步边界记录。
     *
     * @param boundary 边界
     * @param actor 操作人编号
     * @return 新增同步边界数值
     */
    @Insert("INSERT INTO dh_dataforge_sync_boundary(request_key,database_id,configuration_revision,product,"
            + "schema_name,table_name,position,creator) VALUES(#{b.requestKey},#{b.databaseId},"
            + "#{b.configurationRevision},#{b.product},#{b.schemaName},#{b.tableName},#{b.position},#{actor})")
    @Options(timeout = 3)
    int insertSyncBoundary(@Param("b") Boundary boundary, @Param("actor") String actor);
}

