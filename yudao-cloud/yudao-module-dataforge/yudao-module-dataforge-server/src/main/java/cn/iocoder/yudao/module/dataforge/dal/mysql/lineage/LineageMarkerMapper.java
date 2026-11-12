package cn.iocoder.yudao.module.dataforge.dal.mysql.lineage;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDetailDO;
import cn.iocoder.yudao.module.dataforge.service.lineage.LineageMarkerRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 血缘上报扫描：可视化 ETL 流程、集成任务、数据同步的待上报候选与标记 CAS。
 * <p>
 * 单平台后台任务专用，不经数据权限改写；只读写 lineage_report_key 一列，不触碰修订号、更新时间与逻辑删除状态。
 * 开发任务的同类方法位于 {@code DevTaskMapper}。
 */
@Mapper
public interface LineageMarkerMapper {

    // ==================== 可视化 ETL 流程：发布修订号 ====================

    /**
     * 已发布且发布修订号或解析器版本尚未上报的流程。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT id, name AS source_name, published_revision AS source_revision, lineage_report_key, "
            + "FALSE AS removed FROM dh_dataforge_etl_pipeline "
            + "WHERE deleted = 0 AND published_revision IS NOT NULL AND published_config_json IS NOT NULL "
            + "AND (lineage_report_key IS NULL OR lineage_report_key <> CONCAT(published_revision, ':', #{analyzerVersion})) "
            + "ORDER BY id LIMIT #{limit}")
    List<LineageMarkerRow> selectEtlPipelinePending(@Param("analyzerVersion") int analyzerVersion,
                                                    @Param("limit") int limit);

    /**
     * 已删除但仍有标记的流程。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT id, name AS source_name, published_revision AS source_revision, lineage_report_key, "
            + "TRUE AS removed FROM dh_dataforge_etl_pipeline "
            + "WHERE deleted = 1 AND lineage_report_key IS NOT NULL ORDER BY id LIMIT #{limit}")
    List<LineageMarkerRow> selectEtlPipelineRemoved(@Param("limit") int limit);

    /**
     * 按旧标记 CAS 更新流程的上报标记。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Update("UPDATE dh_dataforge_etl_pipeline SET lineage_report_key = CAST(#{newKey,jdbcType=VARCHAR} AS varchar) "
            + "WHERE id = #{id} AND lineage_report_key IS NOT DISTINCT FROM CAST(#{oldKey,jdbcType=VARCHAR} AS varchar)")
    int updateEtlPipelineKey(@Param("id") Long id, @Param("oldKey") String oldKey, @Param("newKey") String newKey);

    // ==================== 集成任务：任务修订号 ====================

    /**
     * 已保存画布且修订号或解析器版本尚未上报的集成任务。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT id, name AS source_name, COALESCE(revision, 0) AS source_revision, lineage_report_key, "
            + "FALSE AS removed FROM dh_dataforge_integration_task "
            + "WHERE deleted = 0 AND flow_json IS NOT NULL "
            + "AND (lineage_report_key IS NULL OR lineage_report_key <> CONCAT(COALESCE(revision, 0), ':', #{analyzerVersion})) "
            + "ORDER BY id LIMIT #{limit}")
    List<LineageMarkerRow> selectIntegrationPending(@Param("analyzerVersion") int analyzerVersion,
                                                    @Param("limit") int limit);

    /**
     * 已删除但仍有标记的集成任务。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT id, name AS source_name, COALESCE(revision, 0) AS source_revision, lineage_report_key, "
            + "TRUE AS removed FROM dh_dataforge_integration_task "
            + "WHERE deleted = 1 AND lineage_report_key IS NOT NULL ORDER BY id LIMIT #{limit}")
    List<LineageMarkerRow> selectIntegrationRemoved(@Param("limit") int limit);

    /**
     * 按旧标记 CAS 更新集成任务的上报标记。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Update("UPDATE dh_dataforge_integration_task SET lineage_report_key = CAST(#{newKey,jdbcType=VARCHAR} AS varchar) "
            + "WHERE id = #{id} AND lineage_report_key IS NOT DISTINCT FROM CAST(#{oldKey,jdbcType=VARCHAR} AS varchar)")
    int updateIntegrationKey(@Param("id") Long id, @Param("oldKey") String oldKey, @Param("newKey") String newKey);

    // ==================== 数据同步：最近一次已结束运行编号 ====================

    /**
     * 最近一次运行已结束（正常结束、异常结束、已停止）且该运行尚未上报的数据同步任务。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT a.id, a.task_name AS source_name, a.latest_run_id AS source_revision, a.lineage_report_key, "
            + "FALSE AS removed FROM dh_dataforge_access a "
            + "JOIN dh_dataforge_access_task t ON t.id = a.latest_run_id AND t.deleted = 0 "
            + "WHERE a.deleted = 0 AND t.run_status IN (3, 4, 6) "
            + "AND (a.lineage_report_key IS NULL OR a.lineage_report_key <> CONCAT(a.latest_run_id, ':', #{analyzerVersion})) "
            + "ORDER BY a.id LIMIT #{limit}")
    List<LineageMarkerRow> selectDataAccessPending(@Param("analyzerVersion") int analyzerVersion,
                                                   @Param("limit") int limit);

    /**
     * 已删除但仍有标记的数据同步任务。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT id, task_name AS source_name, latest_run_id AS source_revision, lineage_report_key, "
            + "TRUE AS removed FROM dh_dataforge_access "
            + "WHERE deleted = 1 AND lineage_report_key IS NOT NULL ORDER BY id LIMIT #{limit}")
    List<LineageMarkerRow> selectDataAccessRemoved(@Param("limit") int limit);

    /**
     * 按旧标记 CAS 更新数据同步任务的上报标记。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Update("UPDATE dh_dataforge_access SET lineage_report_key = CAST(#{newKey,jdbcType=VARCHAR} AS varchar) "
            + "WHERE id = #{id} AND lineage_report_key IS NOT DISTINCT FROM CAST(#{oldKey,jdbcType=VARCHAR} AS varchar)")
    int updateDataAccessKey(@Param("id") Long id, @Param("oldKey") String oldKey, @Param("newKey") String newKey);

    /**
     * 最近若干次已结束运行中同步成功的表级明细（当前发布修订号下的运行）。
     *
     * @param dataAccessId 数据同步任务编号
     * @param revision     当前发布修订号；为 null 时不按修订号过滤
     * @param runs         最多取的运行次数
     */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT d.* FROM dh_dataforge_access_task_detail d WHERE d.deleted = 0 AND d.if_success = 1 "
            + "AND d.task_id IN (SELECT t.id FROM dh_dataforge_access_task t WHERE t.deleted = 0 "
            + "AND t.data_access_id = #{dataAccessId} AND t.run_status IN (3, 4, 6) "
            + "AND (CAST(#{revision,jdbcType=INTEGER} AS int4) IS NULL OR t.release_revision = #{revision,jdbcType=INTEGER}) "
            + "ORDER BY t.id DESC LIMIT #{runs}) ORDER BY d.id")
    List<DataAccessTaskDetailDO> selectRecentSuccessfulDetails(@Param("dataAccessId") Long dataAccessId,
                                                               @Param("revision") Integer revision,
                                                               @Param("runs") int runs);
}
