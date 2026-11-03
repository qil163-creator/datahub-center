package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog.QualityAuditLogPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog.QualityAuditLogRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityAuditLogDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 数据治理-数据质量-检测结果 Service 实现（只读）
 *
 * @author Li
 */
@Service
@Validated
public class QualityAuditLogServiceImpl implements QualityAuditLogService {

    @Resource
    private QualityAuditLogMapper auditLogMapper;

    @Resource
    private QualityTaskMapper taskMapper;

    @Resource
    private QualityRuleMapper ruleMapper;

    /**
     * 获得检测结果详情。
     */
    @Override
    public QualityAuditLogDO getAuditLog(Long id) {
        return auditLogMapper.selectById(id);
    }

    /**
     * 获得检测结果分页。
     */
    @Override
    public PageResult<QualityAuditLogRespVO> getAuditLogPage(QualityAuditLogPageReqVO pageReqVO) {
        PageResult<QualityAuditLogDO> pageResult = auditLogMapper.selectPage(pageReqVO);
        PageResult<QualityAuditLogRespVO> result = BeanUtils.toBean(pageResult, QualityAuditLogRespVO.class);
        List<Long> taskIds = result.getList().stream()
                .map(QualityAuditLogRespVO::getTaskId).filter(Objects::nonNull).distinct().toList();
        if (!taskIds.isEmpty()) {
            Map<Long, String> taskNameMap = taskMapper.selectBatchIds(taskIds).stream()
                    .collect(Collectors.toMap(QualityTaskDO::getId, QualityTaskDO::getTaskName));
            result.getList().forEach(vo -> vo.setTaskName(taskNameMap.get(vo.getTaskId())));
        }
        return result;
    }

    /**
     * 六维质量维度统计（雷达图）：按规则的质量维度聚合最近检测结果的通过率
     */
    @Override
    public List<Map<String, Object>> getDimensionSummary() {
        // 取最近 1000 条检测结果，按规则质量维度聚合通过率
        List<QualityAuditLogDO> logs = auditLogMapper.selectList(
                new LambdaQueryWrapperX<QualityAuditLogDO>()
                        .isNotNull(QualityAuditLogDO::getRuleId)
                        .orderByDesc(QualityAuditLogDO::getId)
                        .last("LIMIT 1000"));
        if (logs.isEmpty()) {
            return List.of();
        }
        Map<Long, String> ruleDimMap = ruleMapper.selectBatchIds(
                        logs.stream().map(QualityAuditLogDO::getRuleId).distinct().toList()).stream()
                .collect(Collectors.toMap(QualityRuleDO::getId,
                        rule -> StrUtil.blankToDefault(rule.getQualityDim(), "OTHER")));
        Map<String, long[]> dimStats = new LinkedHashMap<>();
        for (QualityAuditLogDO log : logs) {
            String dim = ruleDimMap.getOrDefault(log.getRuleId(), "OTHER");
            long[] stats = dimStats.computeIfAbsent(dim, k -> new long[]{0, 0, 0});
            stats[0] += log.getTotalCount() == null ? 0 : log.getTotalCount();
            stats[1] += log.getFailCount() == null ? 0 : log.getFailCount();
            stats[2]++;
        }
        return dimStats.entrySet().stream().map(entry -> {
            long total = entry.getValue()[0];
            long fail = entry.getValue()[1];
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("qualityDim", entry.getKey());
            item.put("checkCount", entry.getValue()[2]);
            item.put("passRate", total <= 0 ? null
                    : BigDecimal.valueOf((total - fail) * 100.0 / total).setScale(2, RoundingMode.HALF_UP));
            item.put("sampleStatus", total <= 0 ? "NO_SAMPLE" : "SCORED");
            return item;
        }).toList();
    }

    /**
     * 质量趋势（复合图表）：近 N 天按日统计检测次数与平均通过率
     */
    @Override
    public List<Map<String, Object>> getReportTrend(Integer days) {
        int rangeDays = days == null || days <= 0 ? 15 : Math.min(days, 90);
        LocalDateTime rangeStart = LocalDate.now().minusDays(rangeDays - 1L).atStartOfDay();
        List<QualityAuditLogDO> logs = auditLogMapper.selectList(
                new LambdaQueryWrapperX<QualityAuditLogDO>()
                        .ge(QualityAuditLogDO::getCheckTime, rangeStart));
        // 日期 → [检测次数, 通过次数]
        Map<String, long[]> byDate = new TreeMap<>();
        for (int i = 0; i < rangeDays; i++) {
            byDate.put(LocalDate.now().minusDays(rangeDays - 1L - i).toString(), new long[]{0, 0});
        }
        for (QualityAuditLogDO log : logs) {
            if (log.getCheckTime() == null) {
                continue;
            }
            long[] stats = byDate.get(log.getCheckTime().toLocalDate().toString());
            if (stats == null) {
                continue;
            }
            stats[0]++;
            if (QualityAuditLogDO.RESULT_PASS.equals(log.getResultType())) {
                stats[1]++;
            }
        }
        return byDate.entrySet().stream().map(entry -> {
            long checkCount = entry.getValue()[0];
            long passCount = entry.getValue()[1];
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("date", entry.getKey());
            item.put("checkCount", checkCount);
            item.put("passRate", checkCount <= 0 ? null
                    : BigDecimal.valueOf(passCount * 100.0 / checkCount).setScale(2, RoundingMode.HALF_UP));
            return item;
        }).toList();
    }

    /**
     * 质量报告统计：检测总次数、通过、未通过、平均通过率
     */
    @Override
    public Map<String, Object> getReportSummary() {
        Long total = auditLogMapper.selectCount(new LambdaQueryWrapperX<>());
        Long pass = auditLogMapper.selectCount(new LambdaQueryWrapperX<QualityAuditLogDO>()
                .eq(QualityAuditLogDO::getResultType, QualityAuditLogDO.RESULT_PASS));
        Long fail = auditLogMapper.selectCount(new LambdaQueryWrapperX<QualityAuditLogDO>()
                .eq(QualityAuditLogDO::getResultType, QualityAuditLogDO.RESULT_FAIL));
        BigDecimal avgPassRate = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(pass * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCount", total);
        summary.put("passCount", pass);
        summary.put("failCount", fail);
        summary.put("avgPassRate", avgPassRate);
        return summary;
    }

}
