package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog;

import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance.QualityTaskInstanceRespVO;

import java.util.List;

/** 报告只采用该实例的冻结定义；百分数在线协议中为精确十进制字符串。 */
public record QualityReportRespVO(QualityTaskInstanceRespVO instance, List<Dimension> dimensions,
                                  List<Trend> trend, List<Rule> rules) {

    public record Dimension(String qualityDim, int ruleCount, Long issueCount, Long totalCount,
                            String problemRatio, String score, String previousProblemRatio) {

    }

    public record Trend(String date, String score) {

    }

    public record Rule(Long id, Long auditLogId, Long ruleId, Long evaluationId, String ruleName, String ruleType,
                       String ruleTypeName, String warningLevel, String errorExample, String repairAdvice,
                       java.time.LocalDateTime checkTime,
                       String qualityDim, Long checkObjectId, String datasourceName, String tableName,
                       String checkField, List<String> fieldLabels, Long totalCount, Long failCount,
                       String problemRatio, String resultType, List<Long> issueIds) {

    }
}
