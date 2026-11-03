package cn.iocoder.yudao.module.dataforge.dal.mysql.etl;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlRecordPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.execution.RunCapacityMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Result;

/**
 * 可视化 ETL 执行记录 Mapper
 *
 * @author Li
 */
@Mapper
public interface EtlRecordMapper extends BaseMapperX<EtlRecordDO>, RunCapacityMapper {

    /** 提交后只读观测；版本变化则放弃本次样本，不能把后续核查时间当作原停止时间。 */
    @Select("SELECT stop_requested_at AS requested,end_time AS confirmed FROM dh_dataforge_etl_record"
            + " WHERE id=#{id} AND row_version=#{version}"
            + " AND stop_requested AND run_status IN (3,4,5)")
    @org.apache.ibatis.annotations.Options(timeout = 2, useCache = false,
            flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    cn.iocoder.yudao.module.dataforge.dal.dataobject.execution.RunStopTiming selectStopTiming(@Param("id") long id,
            @Param("version") long version);

    /** 回执查询不排除软删除：历史请求标识永远不能重新分配。 */
    @Select("SELECT request_hash,result_json,deleted FROM dh_dataforge_run_command"
            + " WHERE domain='ETL' AND request_id=#{requestId}")
    @Results({@Result(column = "request_hash", property = "requestHash"),
            @Result(column = "result_json", property = "resultJson",
                    typeHandler = cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler.class)})
    cn.iocoder.yudao.module.dataforge.dal.dataobject.execution.RunCommandDO selectCommand(
            @Param("requestId") String requestId);

    /**
     * 新增ETL 运行记录的命令记录。
     *
     * @param command 待处理命令
     * @return 新增命令数值
     */
    @org.apache.ibatis.annotations.Insert("INSERT INTO dh_dataforge_run_command"
            + " (domain,record_id,request_id,action,request_hash,actor_id,expected_version,"
            + "result_json,before_json,after_json,creator,updater) VALUES"
            + " ('ETL',#{command.recordId},#{command.requestId},#{command.action},"
            + "#{command.requestHash},#{command.actorId},#{command.expectedVersion},"
            + "CAST(#{command.resultJson} AS jsonb),CAST(#{command.beforeJson} AS jsonb),"
            + "CAST(#{command.afterJson} AS jsonb),CAST(#{command.actorId} AS text),CAST(#{command.actorId} AS text))")
    int insertCommand(
            @Param("command") cn.iocoder.yudao.module.dataforge.dal.dataobject.execution.RunCommandDO command);

    /**
     * 读取数据库当前时间，供持久状态及租约判断使用。
     *
     * @return 本地日期时间
     */
    @Select("SELECT clock_timestamp()::timestamp")
    java.time.LocalDateTime databaseNow();

    /**
     * 统计ETL 运行记录的占位数量。
     *
     * @param pipelineId 流程编号
     * @return 符合条件或受影响的记录数量
     */
    @Select("SELECT count(*) FROM dh_dataforge_etl_record WHERE 1=1"
            + " AND pipeline_id=#{pipelineId} AND occupies_slot")
    long countSlots(@Param("pipelineId") Long pipelineId);

    /**
     * 查询ETL 运行记录的待处理队首。
     *
     * @param pipelineId 流程编号
     * @return SELECT待处理队首数值
     */
    @Select("SELECT id FROM dh_dataforge_etl_record WHERE pipeline_id=#{pipelineId}"
            + " AND protocol_version=1 AND run_status=1 AND NOT occupies_slot AND NOT stop_requested"
            + " AND NOT review_required AND deleted=0 ORDER BY id LIMIT 1")
    Long selectPendingHead(@Param("pipelineId") Long pipelineId);

    /** 单平台扫描，逐项验证执行人权限；每个任务只取队首，退避不会让后续运行插队。 */

    @Select(RUN_CAPACITY_CTE + "SELECT fair.id FROM (SELECT q.id,"
            + "ROW_NUMBER() OVER (ORDER BY q.id) AS queue_rank,"
            + "ROW_NUMBER() OVER (PARTITION BY COALESCE(q.resource_ids::text,'UNKNOWN')"
            + " ORDER BY q.id) AS resource_rank FROM ("
            + "SELECT DISTINCT ON (pipeline_id)"
            + " id,pipeline_id,next_attempt_at,resource_ids FROM dh_dataforge_etl_record"
            + " WHERE protocol_version=1 AND run_status=1 AND NOT occupies_slot AND NOT stop_requested"
            + " AND NOT review_required AND deleted=0 ORDER BY pipeline_id,id) q"
            + " WHERE (q.next_attempt_at IS NULL OR q.next_attempt_at<=clock_timestamp())"
            + " AND NOT EXISTS(SELECT 1 FROM occupied o WHERE o.slots>=#{platformCapacity})"
            + " AND NOT EXISTS(SELECT 1 FROM occupied o WHERE 1=1"
            + " AND o.unknown_slots>=#{dataSourceCapacity} AND q.resource_ids<>'[]'::jsonb)"
            + " AND NOT EXISTS(SELECT 1 FROM saturated_sources s WHERE 1=1"
            + " AND q.resource_ids @> jsonb_build_array(s.resource_id))"
            + " AND NOT EXISTS(SELECT 1 FROM dh_dataforge_etl_record held"
            + " WHERE held.pipeline_id=q.pipeline_id AND held.occupies_slot)"
            + ") fair ORDER BY fair.resource_rank,fair.queue_rank,fair.id LIMIT #{limit}")
    java.util.List<EtlRecordDO> selectDispatchCandidates(@Param("limit") int limit,
            @Param("platformCapacity") int platformCapacity, @Param("dataSourceCapacity") int dataSourceCapacity);

    /**
     * 查询ETL 运行记录的过期候选。
     *
     * @param limit 读取或处理上限
     * @return ETL 运行记录的过期候选结果集合
     */
    @Select("SELECT id FROM dh_dataforge_etl_record WHERE protocol_version=1"
            + " AND occupies_slot AND run_status IN (1,2) AND lease_until<=clock_timestamp()"
            + " AND deleted=0 ORDER BY lease_until,id LIMIT #{limit}")
    java.util.List<EtlRecordDO> selectExpiredCandidates(@Param("limit") int limit);

    /**
     * 查询ETL 运行记录的质量候选。
     *
     * @param limit 读取或处理上限
     * @param failureLimit 失败上限
     * @return ETL 运行记录的质量候选结果集合
     */
    @Select("SELECT id FROM dh_dataforge_etl_record WHERE protocol_version=1"
            + " AND occupies_slot AND run_status=3 AND quality_status IN ('PENDING','UNKNOWN')"
            + " AND reconcile_attempts < #{failureLimit} AND deleted=0"
            + " AND (next_reconcile_at IS NULL OR next_reconcile_at<=clock_timestamp())"
            + " AND (reconcile_lease_until IS NULL OR reconcile_lease_until<=clock_timestamp())"
            + " ORDER BY next_reconcile_at NULLS FIRST,id LIMIT #{limit}")
    java.util.List<EtlRecordDO> selectQualityCandidates(@Param("limit") int limit,
            @Param("failureLimit") int failureLimit);

    /**
     * 查询ETL 运行记录的身份。
     *
     * @param runKey 运行键
     * @return ETL 运行记录
     */
    @Select("SELECT id,request_hash,run_status,stop_requested,deleted FROM dh_dataforge_etl_record"
            + " WHERE run_key=#{runKey}")
    @Results({
            @Result(column = "request_hash", property = "requestHash"),
            @Result(column = "run_status", property = "runStatus"),
            @Result(column = "stop_requested", property = "stopRequested")
    })
    EtlRecordDO selectIdentity(@Param("runKey") String runKey);

    /** 观测快照默认不随记录加载，按需单独读取。 */
    default String selectRuntimeMetricsJson(Long id) {
        EtlRecordDO record = selectOne(new LambdaQueryWrapperX<EtlRecordDO>()
                .select(EtlRecordDO::getId, EtlRecordDO::getRuntimeMetricsJson)
                .eq(EtlRecordDO::getId, id));
        return record == null ? null : record.getRuntimeMetricsJson();
    }

    /**
     * 按查询条件分页读取ETL 运行记录。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取ETL 运行记录结果
     */
    default PageResult<EtlRecordDO> selectPage(EtlRecordPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<EtlRecordDO>()
                .likeIfPresent(EtlRecordDO::getName, reqVO.getName())
                .eqIfPresent(EtlRecordDO::getPipelineId, reqVO.getPipelineId())
                .eqIfPresent(EtlRecordDO::getRunStatus, reqVO.getRunStatus())
                .eqIfPresent(EtlRecordDO::getExecuteType, reqVO.getExecuteType())
                .betweenIfPresent(EtlRecordDO::getStartTime, reqVO.getStartTime())
                .apply(reqVO.getArchived() != null, (Boolean.TRUE.equals(reqVO.getArchived()) ? "" : "NOT ")
                        + "EXISTS (SELECT 1 FROM dh_dataforge_etl_archive a WHERE a.record_id=dh_dataforge_etl_record.id "
                        + "AND a.deleted=0 AND a.restored_at IS NULL)")
                .orderByDesc(EtlRecordDO::getId));
    }

}
