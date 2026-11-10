package cn.iocoder.yudao.module.dataforge.dal.mysql.operations;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo.OpsInstanceModels.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.operations.OpsDevelopmentSnapshotDO;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 运维只读投影，复用集成迁入实例和平台执行表，不复制平台运行状态。 */
@Mapper
public interface OpsInstanceMapper extends BaseMapperX<OpsDevelopmentSnapshotDO> {

    @Select("SELECT ds_project_code,ds_instance_id FROM dh_dataforge_integration_record WHERE id=#{id} AND task_id=#{taskId} AND deleted=0 AND ds_project_code IS NOT NULL")
    InstanceResp selectManagedIntegrationIdentity(@Param("id") Long id, @Param("taskId") Long taskId);

    /**
     * 查询运维实例的实例。
     *
     * @param req 请求参数
     * @param categoryIds 分类编号集合
     * @param categoryOrigin 分类原始
     * @param offset 读取偏移量
     * @return 运维实例的实例结果集合
     */
    @SelectProvider(type = Sql.class, method = "page")
    List<InstanceResp> selectInstances(@Param("req") PageReq req, @Param("categoryIds") List<Long> categoryIds,
            @Param("categoryOrigin") String categoryOrigin, @Param("offset") long offset);

    /**
     * 统计运维实例的实例数量。
     *
     * @param req 请求参数
     * @param categoryIds 分类编号集合
     * @param categoryOrigin 分类原始
     * @return 符合条件或受影响的记录数量
     */
    @SelectProvider(type = Sql.class, method = "count")
    Long countInstances(@Param("req") PageReq req, @Param("categoryIds") List<Long> categoryIds,
            @Param("categoryOrigin") String categoryOrigin);

    /**
     * 查询运维实例的实例。
     *
     * @param req 请求参数
     * @return 实例
     */
    @SelectProvider(type = Sql.class, method = "get")
    InstanceResp selectInstance(@Param("req") IdentityReq req);

    /**
     * 查询运维实例的统计。
     *
     * @param kind 种类
     * @param dayStart 天数开始
     * @param dayEnd 天数结束
     * @param categoryIds 分类编号集合
     * @param categoryOrigin 分类原始
     * @param taskType 任务类型
     * @return 统计
     */
    @SelectProvider(type = Sql.class, method = "statistics")
    StatisticsResp selectStatistics(@Param("kind") String kind, @Param("dayStart") LocalDateTime dayStart,
            @Param("dayEnd") LocalDateTime dayEnd, @Param("categoryIds") List<Long> categoryIds,
            @Param("categoryOrigin") String categoryOrigin, @Param("taskType") String taskType);

    /**
     * 查询运维实例的集成日志。
     *
     * @param id 记录编号
     * @return 运维实例的集成日志文本
     */
    @Select("SELECT coalesce(log_content,'') FROM dh_dataforge_integration_record WHERE id=#{id} AND deleted=0")
    String selectIntegrationLog(@Param("id") Long id);

    /**
     * 查询运维实例的开发日志。
     *
     * @param id 记录编号
     * @return 运维实例的开发日志文本
     */
    @Select("SELECT coalesce(log_content,'') FROM dh_dataforge_ops_development_snapshot WHERE id=#{id} AND deleted=0")
    String selectDevelopmentLog(@Param("id") Long id);

    /**
     * 查询数据同步和整库同步实例日志；具体内容仍由服务层统一脱敏和截断。
     *
     * @param id 记录编号
     * @return 执行日志文本
     */
    @Select("SELECT coalesce(real_time_log,'') || CASE WHEN coalesce(error_info,'')='' THEN '' ELSE E'\\n' || error_info END "
            + "FROM dh_dataforge_access_task WHERE id=#{id} AND deleted=0")
    String selectAccessLog(@Param("id") Long id);

    /** 作业历史子实例只包含展示字段，由上层按实例身份校验后读取。 */
    @Select("SELECT children_json::text FROM dh_dataforge_job_record WHERE id=#{id} AND origin=#{origin} AND deleted=0")
    String selectJobChildren(@Param("id") Long id,@Param("origin") String origin);

    /** 读取已确认身份的作业日志摘要。 */
    @Select("SELECT coalesce(log_content,'') FROM dh_dataforge_job_record WHERE id=#{id} AND origin=#{origin} AND deleted=0")
    String selectJobLog(@Param("id") Long id,@Param("origin") String origin);

    /** 整库同步演示历史仅用于查看。 */
    @Select("SELECT coalesce(log_content,'') FROM dh_dataforge_ops_aux_snapshot WHERE id=#{id} AND kind='FULL_SYNC' AND deleted=0")
    String selectFullSyncSnapshotLog(@Param("id") Long id);

    /** 向导日志只返回本地控制摘要，不返回连接配置或执行凭据。 */
    @Select("SELECT '整库同步 DS 实例：'||coalesce(ds_instance_id::text,'未返回')||E'\\n本地提交状态：'||status||E'\\n本地提交不代表引擎执行成功。' FROM dh_dataforge_full_sync_wizard WHERE id=#{id} AND deleted=0")
    String selectWizardLog(@Param("id") Long id);


    /** 本地提交状态不能证明引擎执行状态；通过受管身份只读核对。 */
    @Select("SELECT ds_project_code project_code,ds_instance_id instance_id,ds_workflow_code workflow_code FROM dh_dataforge_full_sync_wizard WHERE id=#{id} AND deleted=0 AND execution_mode='DOLPHIN_DATAX'")
    WizardTarget selectWizardTarget(@Param("id") Long id);

    final class Sql {

        /**
         * 限制SQL工具的实例化，统一通过静态能力使用。
         */
        private Sql() {
        }

        /**
         * 获取SQL的投影。
         *
         * @param kind 种类
         * @param details 明细
         * @return SQL的投影文本
         */
        private static String projection(String kind, boolean details) {
            if ("JOB".equals(kind)) {
                return "SELECT r.id,r.origin,'JOB' kind,r.job_id task_id,'platform:'||j.category_id category_id,"
                        + "j.name task_name,r.instance_name,NULL::text task_type,r.execution_type,r.status,r.start_time,r.end_time,r.duration,"
                        + "j.owner_name,CASE WHEN r.creator='migration:0255' THEN '演示用户' ELSE r.creator END creator_name,r.create_time,r.update_time,"
                        + (details ? "j.description,r.flow_json::text flow_json " : "NULL::text description,NULL::text flow_json ")
                        + "FROM dh_dataforge_job_record r JOIN dh_dataforge_job j ON j.id=r.job_id AND j.deleted=0 WHERE r.deleted=0";
            }
            if ("FULL_SYNC".equals(kind)) {
                return "SELECT r.id,'SOURCE' origin,'FULL_SYNC' kind,r.task_id,'platform:'||r.category_id category_id,"
                        + "r.task_name,r.instance_name,'OFFLINE' task_type,'START' execution_type,r.status,r.start_time,r.end_time,r.duration,"
                        + "r.owner_name,CASE WHEN r.creator='migration:0255' THEN '演示用户' ELSE r.creator END creator_name,r.create_time,r.update_time,"
                        + (details ? "r.description,r.flow_json::text flow_json " : "NULL::text description,NULL::text flow_json ")
                        + "FROM dh_dataforge_ops_aux_snapshot r WHERE r.kind='FULL_SYNC' AND r.deleted=0 "
                        + "UNION ALL SELECT w.id,'WIZARD','FULL_SYNC',w.id,NULL::text,coalesce(w.config_json->>'taskName','整库同步'),"
                        + "coalesce(w.config_json->>'taskName','整库同步')||'-'||w.ds_instance_id::text,'OFFLINE','START',"
                        + "'UNKNOWN',NULL::timestamp,NULL::timestamp,NULL::bigint,w.config_json->>'responsibleName',w.creator,w.create_time,w.update_time,"
                        + (details ? "coalesce(w.config_json->>'description',''),NULL::text " : "NULL::text,NULL::text ")
                        + "FROM dh_dataforge_full_sync_wizard w WHERE w.deleted=0 AND w.execution_mode='DOLPHIN_DATAX' AND w.ds_instance_id IS NOT NULL "
                        + "UNION ALL " + projection("DATA_ACCESS",details)
                        + " AND t.task_type IN (2,3)";
            }
            if ("DATA_ACCESS".equals(kind) || "FULL_SYNC".equals(kind)) {
                String outputKind = "FULL_SYNC".equals(kind) ? "'FULL_SYNC'" : "'DATA_ACCESS'";
                String fullSyncFilter = "FULL_SYNC".equals(kind) ? " AND t.task_type IN (2,3)" : "";
                return "SELECT r.id,'PLATFORM' origin," + outputKind + " kind,r.data_access_id task_id,NULL::text category_id,"
                        + "t.task_name,t.task_name,"
                        + "CASE t.engine_type WHEN 2 THEN 'REALTIME' WHEN 3 THEN 'API' ELSE 'OFFLINE' END task_type,"
                        + "CASE r.run_type WHEN 1 THEN 'SCHEDULE' ELSE 'MANUAL' END execution_type,"
                        + "CASE r.run_status WHEN 1 THEN 'WAITING' WHEN 2 THEN 'RUNNING' WHEN 3 THEN 'SUCCESS' "
                        + "WHEN 4 THEN 'FAILURE' WHEN 5 THEN 'UNKNOWN' WHEN 6 THEN 'STOPPED' ELSE 'IDLE' END status,"
                        + "r.start_time,r.end_time,greatest(0,extract(epoch FROM coalesce(r.end_time,localtimestamp)-r.start_time))::bigint duration,"
                        + "NULL::text owner_name,t.creator creator_name,r.create_time,r.update_time,"
                        + (details ? "t.description,NULL::text flow_json " : "NULL::text description,NULL::text flow_json ")
                        + "FROM dh_dataforge_access_task r JOIN dh_dataforge_access t ON t.id=r.data_access_id AND t.deleted=0 "
                        + "WHERE r.deleted=0" + fullSyncFilter;
            }
            if ("INTEGRATION".equals(kind)) {
                return "SELECT r.id,CASE WHEN r.ds_project_code IS NULL THEN 'SOURCE' ELSE 'MANAGED' END origin,'INTEGRATION' kind,r.task_id,'source:'||t.category_id category_id,"
                        + "t.name task_name,coalesce(r.instance_name,t.name) instance_name,t.mode task_type,"
                        + "CASE WHEN r.command_type='9' THEN 'STOP' ELSE 'START' END execution_type,r.status,"
                        + "r.start_time,r.end_time,r.duration,coalesce(r.owner_name,t.owner_name) owner_name,"
                        + "CASE WHEN r.ds_project_code IS NULL THEN coalesce(r.creator_name,t.creator_name) ELSE r.creator END creator_name,"
                        + "coalesce(r.source_create_time,r.start_time,r.create_time) create_time,"
                        + "coalesce(r.source_update_time,r.end_time,r.start_time,r.update_time) update_time,"
                        + (details ? "coalesce(r.description,'') description,coalesce(r.flow_json,t.flow_json)::text flow_json "
                        : "NULL::text description,NULL::text flow_json ")
                        + "FROM dh_dataforge_integration_record r JOIN dh_dataforge_integration_task t ON t.id=r.task_id AND t.deleted=0 WHERE r.deleted=0 "
                        + "UNION ALL SELECT r.id,'PLATFORM','INTEGRATION',t.id,'source:'||t.category_id,t.name,r.name,t.mode,"
                        + "CASE WHEN r.run_status=5 THEN 'STOP' ELSE 'START' END,"
                        + "CASE r.run_status WHEN 1 THEN 'WAITING' WHEN 3 THEN 'SUCCESS' WHEN 4 THEN 'FAILURE' WHEN 5 THEN 'STOPPED' ELSE 'RUNNING' END,"
                        + "r.start_time,r.end_time,greatest(0,extract(epoch FROM coalesce(r.end_time,localtimestamp)-r.start_time))::bigint,"
                        + "t.owner_name,t.creator_name,r.create_time,r.update_time,"
                        + (details ? "t.description,r.config_json::text " : "NULL::text,NULL::text ")
                        + "FROM dh_dataforge_etl_record r JOIN dh_dataforge_integration_task t ON t.pipeline_id=r.pipeline_id AND t.deleted=0 "
                        + "AND t.id=(SELECT min(t2.id) FROM dh_dataforge_integration_task t2 WHERE t2.pipeline_id=r.pipeline_id AND t2.deleted=0) WHERE r.deleted=0";
            }
            if (!"DEVELOPMENT".equals(kind)) {
                throw new IllegalArgumentException("实例种类无效");
            }
            return "SELECT r.id,'SOURCE' origin,'DEVELOPMENT' kind,r.task_id,'platform:'||r.category_id category_id,"
                    + "r.task_name,r.instance_name,r.task_type,r.execution_type,r.status,r.start_time,r.end_time,r.duration,"
                    + "r.owner_name,r.creator_name,r.create_time,r.update_time,"
                    + (details ? "r.description,NULL::text flow_json " : "NULL::text description,NULL::text flow_json ")
                    + "FROM dh_dataforge_ops_development_snapshot r WHERE r.deleted=0 "
                    + "UNION ALL SELECT r.id,'PLATFORM','DEVELOPMENT',r.task_id,'platform:'||t.dir_id,r.task_name,r.task_name||'-'||r.id::text,"
                    + "CASE WHEN t.program_config_json->>'workloadMode'='STREAMING' THEN 'REALTIME' ELSE 'OFFLINE' END,"
                    + "CASE WHEN r.status='STOPPED' THEN 'STOP' ELSE 'START' END,"
                    + "CASE WHEN r.trigger_type='JOB' AND r.ds_instance_id IS NULL AND r.status IN ('WAITING','SUBMITTING') THEN 'WAITING' WHEN r.status='FAILED' THEN 'FAILURE' WHEN r.status IN ('SUBMITTING','UNKNOWN','CANCEL_REQUESTED') THEN 'RUNNING' ELSE r.status END,"
                    + "r.start_time,r.end_time,coalesce(r.duration_ms/1000,greatest(0,extract(epoch FROM coalesce(r.end_time,localtimestamp)-r.start_time))::bigint),"
                    + "t.owner_name,r.creator,r.create_time,r.update_time,"
                    + (details ? "t.description,NULL::text " : "NULL::text,NULL::text ")
                    + "FROM dh_dataforge_dev_record r LEFT JOIN dh_dataforge_dev_task t ON t.id=r.task_id AND t.deleted=0 WHERE r.deleted=0";
        }

        /**
         * 获取SQL的筛选条件。
         *
         * @return SQL的筛选条件文本
         */
        private static String where() {
            return " WHERE 1=1 <if test='req.name != null and req.name != &quot;&quot;'> AND instance_name ILIKE concat('%',#{req.name},'%') </if>"
                    + "<if test='req.taskId != null'> AND task_id=#{req.taskId} </if>"
                    + "<if test='req.status != null'> AND status=#{req.status} </if>"
                    + "<if test='req.taskType != null'> AND task_type=#{req.taskType} </if>"
                    + "<if test='req.startDate != null'> AND start_time &gt;= #{req.startDate} </if>"
                    + "<if test='req.endDate != null'> AND start_time &lt; (#{req.endDate}::date + INTERVAL '1 day') </if>"
                    + "<if test='categoryIds != null'> AND category_id IN <foreach collection='categoryIds' item='category' open='(' separator=',' close=')'>#{categoryOrigin}||':'||#{category}::text</foreach></if>";
        }

        /**
         * 分页读取SQL。
         *
         * @param args 参数
         * @return 分页读取SQL文本
         */
        public static String page(Map<String, Object> args) {
            var req = (PageReq) args.get("req");
            String sort = switch (String.valueOf(req.getSortField())) {
                case "id" -> "id";
                case "createTime" -> "create_time";
                case "endTime" -> "end_time";
                default -> "start_time";
            };
            String direction = "asc".equals(req.getSortOrder()) ? "ASC" : "DESC";
            return "<script>SELECT * FROM (" + projection(req.getKind(), false) + ") instances" + where()
                    + " ORDER BY " + sort + " " + direction + " NULLS LAST,id DESC,origin LIMIT #{req.pageSize} OFFSET #{offset}</script>";
        }

        /**
         * 统计SQL。
         *
         * @param args 参数
         * @return 统计SQL文本
         */
        public static String count(Map<String, Object> args) {
            return "<script>SELECT count(*) FROM (" + projection(((PageReq) args.get("req")).getKind(), false)
                    + ") instances" + where() + "</script>";
        }

        /**
         * 读取SQL。
         *
         * @param args 参数
         * @return SQL文本
         */
        public static String get(Map<String, Object> args) {
            return "SELECT * FROM (" + projection(((IdentityReq) args.get("req")).getKind(), true)
                    + ") instances WHERE id=#{req.id} AND origin=#{req.origin}";
        }

        /**
         * 统计SQL的运行及业务指标。
         *
         * @param args 参数
         * @return 统计SQL的运行及业务指标文本
         */
        public static String statistics(Map<String, Object> args) {
            return "<script>SELECT count(DISTINCT (origin||':'||task_id::text)) FILTER(WHERE status='RUNNING') running,"
                    + "count(*) FILTER(WHERE status='FAILURE' AND start_time &gt;= #{dayStart} AND start_time &lt; #{dayEnd}) today_failed,"
                    + "count(*) FILTER(WHERE start_time &gt;= #{dayStart} AND start_time &lt; #{dayEnd}) today_executed,"
                    + "coalesce(100*count(*) FILTER(WHERE status='SUCCESS' AND start_time &gt;= #{dayStart} AND start_time &lt; #{dayEnd})/"
                    + "nullif(count(*) FILTER(WHERE status IN ('SUCCESS','FAILURE') AND start_time &gt;= #{dayStart} AND start_time &lt; #{dayEnd}),0),0)::integer today_success_rate,"
                    + "coalesce(bool_or(origin='SOURCE'),false) imported_snapshot FROM (" + projection(
                    (String) args.get("kind"), false) + ") instances WHERE 1=1 "
                    + "<if test='taskType != null'> AND task_type=#{taskType} </if>"
                    + "<if test='categoryIds != null'> AND category_id IN <foreach collection='categoryIds' item='category' open='(' separator=',' close=')'>#{categoryOrigin}||':'||#{category}::text</foreach></if></script>";
        }
    }
}
