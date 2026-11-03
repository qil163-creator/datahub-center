package cn.iocoder.yudao.module.governance.service.dashboard;

import cn.iocoder.yudao.module.governance.controller.admin.dashboard.vo.GovernanceDashboardRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderMapper;
import cn.iocoder.yudao.module.governance.service.metadata.MetaObjectService;
import cn.iocoder.yudao.module.governance.service.quality.QualityAlertService;
import cn.iocoder.yudao.module.governance.service.quality.QualityAuditLogService;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardElementService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;

/**
 * 首页看板（数据治理） Service 实现类：
 * 复用元数据 / 数据标准 / 数据质量 / 主数据各自已有的统计逻辑，一次返回工作台、数据总览与治理总览需要的治理域指标。
 *
 * @author Li
 */
@Service
public class GovernanceDashboardServiceImpl implements GovernanceDashboardService {

    @Resource
    private MetaObjectService metaObjectService;

    @Resource
    private StandardElementService standardElementService;

    @Resource
    private QualityAuditLogService qualityAuditLogService;

    @Resource
    private QualityAlertService qualityAlertService;

    @Resource
    private QualityRuleMapper qualityRuleMapper;

    @Resource
    private QualityTaskMapper qualityTaskMapper;

    @Resource
    private QualityWorkOrderMapper qualityWorkOrderMapper;

    @Resource
    private MasterModelMapper masterModelMapper;

    @Resource
    private MasterDistributeMapper masterDistributeMapper;

    /**
     * 获得首页看板汇总：元数据概览、标准数据元统计、质量规则 / 检核 / 告警 / 工单汇总、主数据模型 / 派发任务数量
     *
     *  @return 汇总
     */
    @Override
    public GovernanceDashboardRespVO getSummary() {
        GovernanceDashboardRespVO vo = new GovernanceDashboardRespVO();
        // 1. 元数据 / 数据标准：直接复用既有统计
        vo.setMeta(metaObjectService.getOverview());
        vo.setStandard(standardElementService.getStatistics());
        // 2. 数据质量：规则 / 任务 / 工单数量
        vo.setQualityRuleCount(qualityRuleMapper.selectCount());
        vo.setQualityRuleEnabledCount(qualityRuleMapper.selectCount(
                QualityRuleDO::getStatus, QualityRuleDO.STATUS_ENABLE));
        vo.setQualityTaskCount(qualityTaskMapper.selectCount());
        vo.setPendingWorkOrderCount(qualityWorkOrderMapper.selectCount(
                QualityWorkOrderDO::getOrderStatus, QualityWorkOrderDO.STATUS_PENDING));
        // 3. 数据质量：检核 / 六维 / 告警汇总
        vo.setQualityReport(toQualityReport(qualityAuditLogService.getReportSummary()));
        vo.setQualityDimensions(toQualityDimensions(qualityAuditLogService.getDimensionSummary()));
        vo.setQualityAlert(toQualityAlert(qualityAlertService.getAlertSummary()));
        // 4. 主数据：模型 / 派发任务数量
        vo.setMasterModelCount(masterModelMapper.selectCount());
        vo.setMasterModelReleasedCount(masterModelMapper.selectCount(
                MasterModelDO::getStatus, MasterModelDO.STATUS_RELEASED));
        vo.setMasterDistributeCount(masterDistributeMapper.selectCount());
        vo.setMasterDistributeReleasedCount(masterDistributeMapper.selectCount(
                MasterDistributeDO::getStatus, MasterDistributeDO.STATUS_RELEASED));
        return vo;
    }

    /**
     * 转换质量报告。
     */
    private GovernanceDashboardRespVO.QualityReport toQualityReport(Map<String, Object> summary) {
        GovernanceDashboardRespVO.QualityReport report = new GovernanceDashboardRespVO.QualityReport();
        if (summary == null) {
            return report;
        }
        report.setTotalCount(toLong(summary.get("totalCount")));
        report.setPassCount(toLong(summary.get("passCount")));
        report.setFailCount(toLong(summary.get("failCount")));
        report.setAvgPassRate(toBigDecimal(summary.get("avgPassRate")));
        return report;
    }

    /**
     * 转换质量维度。
     */
    private List<GovernanceDashboardRespVO.QualityDimension> toQualityDimensions(List<Map<String, Object>> rows) {
        return convertList(rows, row -> {
            GovernanceDashboardRespVO.QualityDimension item = new GovernanceDashboardRespVO.QualityDimension();
            item.setQualityDim(row.get("qualityDim") == null ? null : String.valueOf(row.get("qualityDim")));
            item.setCheckCount(toLong(row.get("checkCount")));
            item.setPassRate(toBigDecimal(row.get("passRate")));
            return item;
        });
    }

    /**
     * 转换质量告警。
     */
    private GovernanceDashboardRespVO.QualityAlert toQualityAlert(Map<String, Long> summary) {
        GovernanceDashboardRespVO.QualityAlert alert = new GovernanceDashboardRespVO.QualityAlert();
        if (summary == null) {
            return alert;
        }
        alert.setDangerCount(summary.get("dangerCount"));
        alert.setSeriousCount(summary.get("seriousCount"));
        alert.setGeneralCount(summary.get("generalCount"));
        alert.setUnprocessedCount(summary.get("unprocessedCount"));
        alert.setProcessedCount(summary.get("processedCount"));
        return alert;
    }

    /**
     * 转换长整数。
     */
    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    /**
     * 转换大数十进制数。
     */
    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

}
