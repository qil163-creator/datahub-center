package cn.iocoder.yudao.module.dataforge.dal.mysql.access;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.execution.RunCapacityMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 数据同步任务-执行记录 Mapper
 *
 * @author Li
 */
@Mapper
public interface DataAccessTaskMapper extends BaseMapperX<DataAccessTaskDO>, RunCapacityMapper, SyncBoundaryMapper {

    /** 只投影诊断所需字段；保留占位墓碑和未知/失败运行，不读取日志、凭据或整个配置。 */
    @Select("SELECT id,attempt_count,row_version,run_status,occupies_slot,review_required,stop_requested,effect_state,sync_start_position,"
            + "CASE WHEN octet_length(sync_evidence::text)<=2097152 THEN sync_evidence::text END AS sync_evidence "
            + "FROM dh_dataforge_access_task WHERE 1=1 "
            + "AND config_json->>'targetDatabaseId'=#{target} AND protocol_version=1 "
            + "AND (deleted=0 OR occupies_slot) ORDER BY id DESC LIMIT #{limit}")
    @org.apache.ibatis.annotations.Options(timeout = 3, useCache = false,
            flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    java.util.List<DataAccessTaskDO> selectSyncObservations(
            @Param("target") String target, @Param("limit") int limit);

    /**
     * 查询数据同步任务任务的停止计时。
     *
     * @param id 记录编号
     * @param version 版本
     * @return 运行停止计时
     */
    @org.apache.ibatis.annotations.Select("SELECT stop_requested_at AS requested,end_time AS confirmed"
            + " FROM dh_dataforge_access_task WHERE id=#{id} AND row_version=#{version}"
            + " AND stop_requested AND run_status IN (3,4,6)")
    @org.apache.ibatis.annotations.Options(timeout = 2, useCache = false,
            flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    cn.iocoder.yudao.module.dataforge.dal.dataobject.execution.RunStopTiming selectStopTiming(
            @org.apache.ibatis.annotations.Param("id") long id,
            @org.apache.ibatis.annotations.Param("version") long version);

    /**
     * 读取数据库当前时间，供持久状态及租约判断使用。
     *
     * @return 本地日期时间
     */
    @Select("SELECT clock_timestamp()::timestamp")
    LocalDateTime databaseNow();

    // 墓碑记录保留逻辑运行标识。

    /**
     * 查询数据同步任务任务的身份。
     *
     * @param key 标识键
     * @return 处理后的键值映射
     */
    @Select("SELECT id, deleted FROM dh_dataforge_access_task WHERE run_key=#{key}")
    java.util.Map<String, Object> identity(@Param("key") String key);

    /**
     * 统计数据同步任务任务的占位数量。
     *
     * @param task 任务记录
     * @return 符合条件或受影响的记录数量
     */
    @Select("SELECT count(*) FROM dh_dataforge_access_task WHERE data_access_id=#{task} AND occupies_slot")
    long countSlots(@Param("task") Long task);

    /**
     * 查询数据同步任务任务的最早等待中。
     *
     * @param task 任务记录
     * @return 最早等待中数值
     */
    @Select("SELECT id FROM dh_dataforge_access_task WHERE data_access_id=#{task} "
            + "AND deleted=0 AND run_status=1 ORDER BY COALESCE(scheduled_time,create_time),id LIMIT 1")
    Long oldestWaiting(@Param("task") Long task);

    /**
     * 查询数据同步任务任务的命令身份。
     *
     * @param request 请求参数
     * @return 处理后的键值映射
     */
    @Select("SELECT request_hash,result_json FROM dh_dataforge_run_command WHERE domain='ACCESS' AND request_id=#{request}")
    java.util.Map<String, Object> commandIdentity(@Param("request") String request);

    /**
     * 新增数据同步任务任务的命令记录。
     *
     * @param command 待处理命令
     * @return 新增命令数值
     */
    @org.apache.ibatis.annotations.Insert("INSERT INTO dh_dataforge_run_command(domain,record_id,request_id,action,request_hash,actor_id,expected_version,result_json,before_json,after_json) "
            + "VALUES('ACCESS',#{c.recordId},#{c.requestId},#{c.action},#{c.requestHash},#{c.actorId},#{c.expectedVersion},"
            + "CAST(#{c.resultJson} AS jsonb),CAST(#{c.beforeJson} AS jsonb),CAST(#{c.afterJson} AS jsonb))")
    int insertCommand(
            @Param("c") cn.iocoder.yudao.module.dataforge.dal.dataobject.execution.RunCommandDO command);

    /** 按资源组公平取候选任务，繁忙数据源不能占满整个调度批次。 */

    @Select(RUN_CAPACITY_CTE + "SELECT fair.id,fair.data_access_id FROM ("
            + "SELECT r.id,r.data_access_id,COALESCE(r.scheduled_time,r.create_time) AS due_time,"
            + "ROW_NUMBER() OVER (ORDER BY COALESCE(r.scheduled_time,r.create_time),r.id) AS queue_rank,"
            + "ROW_NUMBER() OVER (PARTITION BY COALESCE(r.resource_ids::text,'UNKNOWN')"
            + " ORDER BY COALESCE(r.scheduled_time,r.create_time),r.id) AS resource_rank "
            + "FROM dh_dataforge_access_task r "
            + "WHERE r.deleted=0 AND r.protocol_version=1 AND r.run_status=1 AND r.execution_stage='PREPARE' AND NOT r.occupies_slot AND NOT r.stop_requested AND NOT r.review_required "
            + "AND (r.next_attempt_at IS NULL OR r.next_attempt_at <= clock_timestamp()) "
            + "AND NOT EXISTS(SELECT 1 FROM occupied o WHERE o.slots>=#{platformCapacity}) "
            + "AND NOT EXISTS(SELECT 1 FROM occupied o WHERE 1=1 "
            + "AND o.unknown_slots>=#{dataSourceCapacity} AND r.resource_ids<>'[]'::jsonb) "
            + "AND NOT EXISTS(SELECT 1 FROM saturated_sources s WHERE 1=1 "
            + "AND r.resource_ids @> jsonb_build_array(s.resource_id)) "
            + "AND NOT EXISTS (SELECT 1 FROM dh_dataforge_access_task older WHERE older.data_access_id=r.data_access_id "
            + "AND older.deleted=0 AND older.run_status=1 AND (COALESCE(older.scheduled_time,older.create_time),older.id)<(COALESCE(r.scheduled_time,r.create_time),r.id)) "
            + "AND NOT EXISTS (SELECT 1 FROM dh_dataforge_access_task a WHERE a.data_access_id=r.data_access_id AND a.occupies_slot) "
            + ") fair ORDER BY fair.resource_rank,fair.queue_rank,fair.due_time,fair.id LIMIT #{limit}")
    java.util.List<DataAccessTaskDO> waitingCandidates(@Param("limit") int limit,
            @Param("platformCapacity") int platformCapacity, @Param("dataSourceCapacity") int dataSourceCapacity);

    /**
     * 按查询条件分页读取数据同步任务任务。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取数据同步任务任务结果
     */
    default PageResult<DataAccessTaskDO> selectPage(DataAccessTaskPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DataAccessTaskDO>()
                .eqIfPresent(DataAccessTaskDO::getDataAccessId, reqVO.getDataAccessId())
                .likeIfPresent(DataAccessTaskDO::getTaskName, reqVO.getTaskName())
                .eqIfPresent(DataAccessTaskDO::getRunStatus, reqVO.getRunStatus())
                .eqIfPresent(DataAccessTaskDO::getRunType, reqVO.getRunType())
                .orderByDesc(DataAccessTaskDO::getId));
    }

    /**
     * 按数据同步任务编号删除数据同步任务任务。
     *
     * @param dataAccessId 数据同步任务编号
     */
    default void deleteByDataAccessId(Long dataAccessId) {
        delete(new LambdaQueryWrapperX<DataAccessTaskDO>()
                .eq(DataAccessTaskDO::getDataAccessId, dataAccessId));
    }

}
