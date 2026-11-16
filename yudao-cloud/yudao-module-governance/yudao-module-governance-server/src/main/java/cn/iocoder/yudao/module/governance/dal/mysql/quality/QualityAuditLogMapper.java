package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog.QualityAuditLogPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityAuditLogDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据治理-数据质量-检测结果明细 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityAuditLogMapper extends BaseMapperX<QualityAuditLogDO> {

    /**
     * 查询质量审计日志分页。
     */
    default PageResult<QualityAuditLogDO> selectPage(QualityAuditLogPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityAuditLogDO>()
                .eqIfPresent(QualityAuditLogDO::getInstanceId, reqVO.getInstanceId())
                .eqIfPresent(QualityAuditLogDO::getTaskId, reqVO.getTaskId())
                .likeIfPresent(QualityAuditLogDO::getTableName, reqVO.getTableName())
                .likeIfPresent(QualityAuditLogDO::getRuleName, reqVO.getRuleName())
                .eqIfPresent(QualityAuditLogDO::getResultType, reqVO.getResultType())
                .eqIfPresent(QualityAuditLogDO::getGroupName, reqVO.getGroupName())
                .betweenIfPresent(QualityAuditLogDO::getCheckTime, reqVO.getCheckTime())
                .orderByDesc(QualityAuditLogDO::getId));
    }

    /**
     * 读取表级指标的历史基准：同一任务、检查对象、指标键与口径下，本次检测之前已产生指标值的结果。
     *
     * <p>不按评测或规则编号区分：保存任务会重建评测，口径（受检表与 Where 模板）相同的行数属于同一序列。
     * 未给出 from/to 时取 before 之前最近一次（环比）；给出时取窗口内最接近 target 的一次（同比）。</p>
     */
    @org.apache.ibatis.annotations.Select({"<script>",
            "SELECT * FROM dh_governance_quality_audit_log WHERE deleted = 0 AND task_id = #{taskId}",
            " AND check_object_id = #{objectId} AND metric_key = #{metricKey} AND metric_scope = #{scope}",
            " AND metric_value IS NOT NULL AND result_type IN ('PASS', 'FAIL', 'SKIPPED')",
            " AND check_time &lt; #{before}",
            "<if test='from != null'> AND check_time &gt;= #{from} AND check_time &lt;= #{to}",
            " ORDER BY abs(extract(epoch FROM (check_time - #{target}))), check_time DESC, id DESC</if>",
            "<if test='from == null'> ORDER BY check_time DESC, id DESC</if>",
            " LIMIT 1",
            "</script>"})
    QualityAuditLogDO selectMetricBaseline(@org.apache.ibatis.annotations.Param("taskId") Long taskId,
            @org.apache.ibatis.annotations.Param("objectId") Long objectId,
            @org.apache.ibatis.annotations.Param("metricKey") String metricKey,
            @org.apache.ibatis.annotations.Param("scope") String scope,
            @org.apache.ibatis.annotations.Param("before") java.time.LocalDateTime before,
            @org.apache.ibatis.annotations.Param("target") java.time.LocalDateTime target,
            @org.apache.ibatis.annotations.Param("from") java.time.LocalDateTime from,
            @org.apache.ibatis.annotations.Param("to") java.time.LocalDateTime to);

    /**
     * 读取动态阈值所需的历史指标值（本次检测之前、同一任务、检查对象与口径，最新在前），可只取指定星期几（ISO：1=周一）。
     */
    @org.apache.ibatis.annotations.Select({"<script>",
            "SELECT metric_value FROM dh_governance_quality_audit_log WHERE deleted = 0 AND task_id = #{taskId}",
            " AND check_object_id = #{objectId} AND metric_key = #{metricKey} AND metric_scope = #{scope}",
            " AND metric_value IS NOT NULL AND result_type IN ('PASS', 'FAIL', 'SKIPPED')",
            " AND check_time &lt; #{before}",
            "<if test='weekday != null'> AND extract(isodow FROM check_time) = #{weekday}</if>",
            " ORDER BY check_time DESC, id DESC LIMIT #{limit}",
            "</script>"})
    List<java.math.BigDecimal> selectMetricHistory(@org.apache.ibatis.annotations.Param("taskId") Long taskId,
            @org.apache.ibatis.annotations.Param("objectId") Long objectId,
            @org.apache.ibatis.annotations.Param("metricKey") String metricKey,
            @org.apache.ibatis.annotations.Param("scope") String scope,
            @org.apache.ibatis.annotations.Param("before") java.time.LocalDateTime before,
            @org.apache.ibatis.annotations.Param("weekday") Integer weekday,
            @org.apache.ibatis.annotations.Param("limit") int limit);

    /**
     * 读取与指定检测结果同一任务、检查对象、指标键与口径的最近若干次结果，按检测时间升序，供趋势图展示。
     */
    @org.apache.ibatis.annotations.Select({"<script>",
            "SELECT * FROM (SELECT * FROM dh_governance_quality_audit_log WHERE deleted = 0 AND task_id = #{log.taskId}",
            " AND check_object_id = #{log.checkObjectId} AND metric_key = #{log.metricKey}",
            " AND metric_scope = #{log.metricScope} AND metric_value IS NOT NULL",
            " AND check_time &lt;= #{log.checkTime}",
            " ORDER BY check_time DESC, id DESC LIMIT #{limit}) recent ORDER BY check_time, id",
            "</script>"})
    List<QualityAuditLogDO> selectMetricTrend(@org.apache.ibatis.annotations.Param("log") QualityAuditLogDO log,
            @org.apache.ibatis.annotations.Param("limit") int limit);

    /**
     * 按实例编号查询质量审计日志列表。
     */
    default List<QualityAuditLogDO> selectListByInstanceId(Long instanceId) {
        return selectList(new LambdaQueryWrapperX<QualityAuditLogDO>()
                .eq(QualityAuditLogDO::getInstanceId, instanceId)
                .orderByAsc(QualityAuditLogDO::getId));
    }

}
