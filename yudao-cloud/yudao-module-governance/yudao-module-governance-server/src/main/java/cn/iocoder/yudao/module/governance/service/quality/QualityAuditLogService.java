package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog.QualityAuditLogPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog.QualityAuditLogRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityAuditLogDO;

import java.util.List;
import java.util.Map;

/**
 * 数据治理-数据质量-检测结果 Service 接口（只读）
 *
 * @author Li
 */
public interface QualityAuditLogService {

    /**
     * 获得检测结果详情。
     */
    QualityAuditLogDO getAuditLog(Long id);

    /**
     * 获得与指定检测结果同一规则、检查对象与口径的表级指标趋势（最近若干次，按检测时间升序）。
     *
     * @param id 检测结果编号
     * @param limit 最多返回点数
     * @return 趋势点；检测结果不存在或不是表级指标时为空列表
     */
    java.util.List<cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog.QualityMetricTrendRespVO>
            getMetricTrend(Long id, int limit);

    /**
     * 获得检测结果分页。
     */
    PageResult<QualityAuditLogRespVO> getAuditLogPage(QualityAuditLogPageReqVO pageReqVO);

    /**
     * 质量报告统计：检测总次数、通过、未通过、平均通过率
     */
    Map<String, Object> getReportSummary();

    /**
     * 六维质量维度统计（雷达图）：按规则的质量维度聚合最近检测结果的通过率
     */
    List<Map<String, Object>> getDimensionSummary();

    /**
     * 质量趋势（复合图表）：近 N 天按日统计检测次数与平均通过率
     */
    List<Map<String, Object>> getReportTrend(Integer days);

}
