package cn.iocoder.yudao.module.dataforge.dal.mysql.dev;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.hutool.core.collection.CollUtil;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 数据开发-开发任务 Mapper
 *
 * @author Li
 */
@Mapper
public interface DevTaskMapper extends BaseMapperX<DevTaskDO> {

    /** 页面归属优先；未明确分类时沿用原生模式，实时页面不意味着持续流执行。 */
    String WORKLOAD_MODE_SQL = "COALESCE(execution_config_json ->> 'workloadMode', "
            + "program_config_json ->> 'workloadMode', 'BATCH')";

    String WORKLOAD_MODE_JOIN_SQL = "COALESCE(t.execution_config_json ->> 'workloadMode', "
            + "t.program_config_json ->> 'workloadMode', 'BATCH')";

    /**
     * 锁定并读取开发任务记录。
     *
     * @param id 记录编号
     * @return 开发任务
     */
    @org.apache.ibatis.annotations.Select("SELECT * FROM dh_dataforge_dev_task WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    DevTaskDO selectForUpdate(@org.apache.ibatis.annotations.Param("id") Long id);


    /**
     * 按查询条件分页读取开发任务。
     *
     * @param reqVO 请求参数
     * @param dirIds 目录编号集合
     * @return 按查询条件分页读取开发任务结果
     */
    default PageResult<DevTaskDO> selectPage(DevTaskPageReqVO reqVO, Collection<Long> dirIds) {
        var query = new LambdaQueryWrapperX<DevTaskDO>()
                .likeIfPresent(DevTaskDO::getName, reqVO.getName())
                .inIfPresent(DevTaskDO::getDirId, dirIds)
                .eqIfPresent(DevTaskDO::getStatus, reqVO.getStatus())
                .eqIfPresent(DevTaskDO::getDbType, reqVO.getDbType())
                .orderByDesc(DevTaskDO::getId);
        if (reqVO.getWorkloadMode() != null) {
            query.apply("(" + WORKLOAD_MODE_SQL + ") = {0}",
                    reqVO.getWorkloadMode());
        }
        String latest = "(SELECT r.status FROM dh_dataforge_dev_record r WHERE r.task_id = dh_dataforge_dev_task.id "
                + "AND r.deleted = 0 ORDER BY r.id DESC LIMIT 1)";
        if ("NEVER".equals(reqVO.getExecutionStatus())) {
            query.apply(latest + " IS NULL");
        } else if ("RUNNING".equals(reqVO.getExecutionStatus())) {
            query.apply(latest + " IN ('WAITING','SUBMITTING','RUNNING','UNKNOWN','CANCEL_REQUESTED')");
        } else if (reqVO.getExecutionStatus() != null) {
            query.apply(latest + " = {0}", reqVO.getExecutionStatus());
        }
        var range = reqVO.getExecutionStartTime();
        if (range != null && range.length == 2) {
            String latestTime = "(SELECT r.start_time FROM dh_dataforge_dev_record r WHERE r.task_id = dh_dataforge_dev_task.id "
                    + "AND r.deleted = 0 ORDER BY r.id DESC LIMIT 1)";
            if (range[0] != null) {
                query.apply(latestTime + " >= {0}", range[0]);
            }
            if (range[1] != null) {
                query.apply(latestTime + " <= {0}", range[1]);
            }
        }
        return selectPage(reqVO, query);
    }

    /**
     * 按目录编号集合查询开发任务列表。
     *
     * @param dirIds 目录编号集合
     * @return 按目录编号集合查询开发任务列表结果集合
     */
    default List<DevTaskDO> selectListByDirIds(Collection<Long> dirIds) {
        if (CollUtil.isEmpty(dirIds)) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<DevTaskDO>()
                .in(DevTaskDO::getDirId, dirIds)
                .orderByAsc(DevTaskDO::getId));
    }

    /**
     * 查询列表全部。
     *
     * @return 列表全部结果集合
     */
    default List<DevTaskDO> selectListAll() {
        return selectList(new LambdaQueryWrapperX<DevTaskDO>().orderByAsc(DevTaskDO::getId));
    }

    /**
     * 按名称查询开发任务。
     *
     * @param name 名称
     * @return 开发任务
     */
    default DevTaskDO selectByName(String name) {
        return selectOne(DevTaskDO::getName, name);
    }

    /**
     * 按目录编号查询开发任务数量。
     *
     * @param dirId 目录编号
     * @return SELECT数量按目录编号数值
     */
    default Long selectCountByDirId(Long dirId) {
        return selectCount(DevTaskDO::getDirId, dirId);
    }

    // ==================== 血缘上报（单平台后台扫描，不经数据权限改写） ====================

    /**
     * 有界扫描当前版本或解析器版本尚未上报的开发任务。
     *
     * @param analyzerVersion 当前解析器版本
     * @param limit           最大条数
     * @return 待上报候选
     */
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(dataPermission = "true")
    @org.apache.ibatis.annotations.Select("SELECT id, name AS source_name, current_version_id AS source_revision, "
            + "lineage_report_key, FALSE AS removed FROM dh_dataforge_dev_task "
            + "WHERE deleted = 0 AND current_version_id IS NOT NULL "
            + "AND (lineage_report_key IS NULL OR lineage_report_key <> CONCAT(current_version_id, ':', #{analyzerVersion})) "
            + "ORDER BY id LIMIT #{limit}")
    List<cn.iocoder.yudao.module.dataforge.service.lineage.LineageMarkerRow> selectLineagePending(
            @org.apache.ibatis.annotations.Param("analyzerVersion") int analyzerVersion,
            @org.apache.ibatis.annotations.Param("limit") int limit);

    /**
     * 有界扫描已删除但仍保留上报标记的开发任务，用于上报 REMOVED。
     *
     * @param limit 最大条数
     * @return 已删除候选
     */
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(dataPermission = "true")
    @org.apache.ibatis.annotations.Select("SELECT id, name AS source_name, current_version_id AS source_revision, "
            + "lineage_report_key, TRUE AS removed FROM dh_dataforge_dev_task "
            + "WHERE deleted = 1 AND lineage_report_key IS NOT NULL ORDER BY id LIMIT #{limit}")
    List<cn.iocoder.yudao.module.dataforge.service.lineage.LineageMarkerRow> selectLineageRemoved(
            @org.apache.ibatis.annotations.Param("limit") int limit);

    /**
     * 按旧标记 CAS 更新血缘上报标记；只改这一列，不触碰修订号、更新时间与逻辑删除状态。
     *
     * @param id     任务编号
     * @param oldKey 扫描时读到的标记（可为 null）
     * @param newKey 新标记（来源已删除时为 null）
     * @return 更新行数
     */
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(dataPermission = "true")
    @org.apache.ibatis.annotations.Update("UPDATE dh_dataforge_dev_task "
            + "SET lineage_report_key = CAST(#{newKey,jdbcType=VARCHAR} AS varchar) WHERE id = #{id} "
            + "AND lineage_report_key IS NOT DISTINCT FROM CAST(#{oldKey,jdbcType=VARCHAR} AS varchar)")
    int updateLineageReportKey(@org.apache.ibatis.annotations.Param("id") Long id,
                               @org.apache.ibatis.annotations.Param("oldKey") String oldKey,
                               @org.apache.ibatis.annotations.Param("newKey") String newKey);

}
