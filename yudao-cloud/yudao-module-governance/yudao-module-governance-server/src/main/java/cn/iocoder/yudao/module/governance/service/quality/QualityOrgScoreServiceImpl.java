package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.orgscore.QualityOrgScorePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.orgscore.QualityOrgScoreRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_RULE_SET_NOT_EXISTS;

/**
 * 数据治理-数据质量-机构评分 Service 实现
 *
 * <p>评分口径：周期内该规则集下检测任务的规则执行结果，按机构（问题数据的 org_id，
 * 无机构维度时归入「全部机构」）聚合——规则在该机构下无未通过记录即视为达标，
 * 得分率 = 达标规则数 / 总规则数 × 100，按得分率排名
 *
 * @author Li
 */
@Service
@Validated
public class QualityOrgScoreServiceImpl implements QualityOrgScoreService {

    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 无机构维度时的合计行 */
    private static final long ORG_ALL = 0L;

    @Resource
    private QualityOrgScoreMapper orgScoreMapper;

    @Resource
    private QualityRuleSetMapper ruleSetMapper;

    @Resource
    private QualityRuleSetItemMapper ruleSetItemMapper;

    @Resource
    private QualityTaskMapper taskMapper;

    @Resource
    private QualityAuditLogMapper auditLogMapper;

    @Resource
    private QualityIssueDataMapper issueDataMapper;

    /**
     * 获得机构评分分页。
     */
    @Override
    public PageResult<QualityOrgScoreRespVO> getOrgScorePage(QualityOrgScorePageReqVO pageReqVO) {
        PageResult<QualityOrgScoreDO> pageResult = orgScoreMapper.selectPage(pageReqVO);
        PageResult<QualityOrgScoreRespVO> result = BeanUtils.toBean(pageResult, QualityOrgScoreRespVO.class);
        List<Long> ruleSetIds = result.getList().stream()
                .map(QualityOrgScoreRespVO::getRuleSetId).filter(Objects::nonNull).distinct().toList();
        if (!ruleSetIds.isEmpty()) {
            Map<Long, String> nameMap = ruleSetMapper.selectBatchIds(ruleSetIds).stream()
                    .collect(Collectors.toMap(QualityRuleSetDO::getId, QualityRuleSetDO::getSetName));
            result.getList().forEach(vo -> vo.setRuleSetName(nameMap.get(vo.getRuleSetId())));
        }
        return result;
    }

    /**
     * 计算评分：依赖所选日期范围内该规则集下检测任务的已执行结果记录，
     *  按机构聚合达标率并排名（先删后插，同规则集同周期可重复计算）
     *
     *  @return 生成的评分记录数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int calculateScore(Long ruleSetId, String periodStart, String periodEnd) {
        QualityRuleSetDO ruleSet = ruleSetMapper.selectById(ruleSetId);
        if (ruleSet == null) {
            throw exception(QUALITY_RULE_SET_NOT_EXISTS);
        }
        List<Long> ruleIds = ruleSetItemMapper.selectListByRuleSetId(ruleSetId).stream()
                .map(QualityRuleSetItemDO::getRuleId).distinct().toList();
        long totalRuleCount = ruleIds.size();
        // 周期（YYYYMM）→ 时间范围
        LocalDateTime rangeStart = LocalDate.parse(periodStart + "01",
                DateTimeFormatter.ofPattern("yyyyMMdd")).atStartOfDay();
        LocalDateTime rangeEnd = LocalDate.parse(periodEnd + "01",
                DateTimeFormatter.ofPattern("yyyyMMdd")).plusMonths(1).atStartOfDay();
        // 该规则集下任务在周期内的检测结果
        List<Long> taskIds = taskMapper.selectList(new LambdaQueryWrapperX<QualityTaskDO>()
                        .eq(QualityTaskDO::getRuleSetId, ruleSetId)).stream()
                .map(QualityTaskDO::getId).toList();
        if (taskIds.isEmpty() || totalRuleCount == 0) {
            return 0;
        }
        List<QualityAuditLogDO> auditLogs = auditLogMapper.selectList(
                new LambdaQueryWrapperX<QualityAuditLogDO>()
                        .in(QualityAuditLogDO::getTaskId, taskIds)
                        .ge(QualityAuditLogDO::getCheckTime, rangeStart)
                        .lt(QualityAuditLogDO::getCheckTime, rangeEnd));
        if (auditLogs.isEmpty()) {
            return 0;
        }
        // 机构维度来自问题数据留存；未按机构拆分的检测结果归入「全部机构」合计行
        List<QualityIssueDataDO> issues = issueDataMapper.selectList(
                new LambdaQueryWrapperX<QualityIssueDataDO>()
                        .in(QualityIssueDataDO::getTaskId, taskIds)
                        .ge(QualityIssueDataDO::getCheckTime, rangeStart)
                        .lt(QualityIssueDataDO::getCheckTime, rangeEnd));
        // 机构键：优先数字 orgId，否则用机构编码（region_code 留存的分组字段原值）
        Map<String, Set<Long>> orgFailedRules = new LinkedHashMap<>();
        // 全部合计口径：规则在周期内有 FAIL 记录即为未达标
        Set<Long> allFailedRules = auditLogs.stream()
                .filter(log -> QualityAuditLogDO.RESULT_FAIL.equals(log.getResultType()))
                .map(QualityAuditLogDO::getRuleId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        orgFailedRules.put("ALL", allFailedRules);
        for (QualityIssueDataDO issue : issues) {
            if (issue.getRuleId() == null
                    || !QualityIssueDataDO.SCOPE_ORG.equals(issue.getStatScope())) {
                continue;
            }
            String orgKey = issue.getOrgId() != null
                    ? String.valueOf(issue.getOrgId())
                    : StrUtil.trimToNull(issue.getRegionCode());
            if (orgKey == null) {
                continue;
            }
            orgFailedRules.computeIfAbsent(orgKey, k -> new HashSet<>()).add(issue.getRuleId());
        }
        // 先删后插，支持重复计算
        orgScoreMapper.deleteByRuleSetAndPeriod(ruleSetId, periodStart, periodEnd);
        List<QualityOrgScoreDO> scores = new ArrayList<>();
        for (Map.Entry<String, Set<Long>> entry : orgFailedRules.entrySet()) {
            boolean isAll = "ALL".equals(entry.getKey());
            Set<Long> executed = auditLogs.stream()
                    .filter(log -> QualityAuditLogDO.RESULT_PASS.equals(log.getResultType())
                            || QualityAuditLogDO.RESULT_FAIL.equals(log.getResultType()))
                    .map(QualityAuditLogDO::getRuleId)
                    .filter(ruleIds::contains)
                    .collect(Collectors.toSet());
            long failCount = entry.getValue().stream().filter(ruleIds::contains).count();
            long executedCount = executed.size();
            if (executedCount == 0) {
                continue;
            }
            long passCount = executedCount - failCount;
            BigDecimal scoreRate = BigDecimal.valueOf(passCount * 100.0 / executedCount)
                    .setScale(2, RoundingMode.HALF_UP);
            scores.add(QualityOrgScoreDO.builder()
                    .ruleSetId(ruleSetId)
                    .periodStart(periodStart).periodEnd(periodEnd)
                    .orgId(isAll ? ORG_ALL
                            : (entry.getKey().matches("\\d+") ? Long.valueOf(entry.getKey()) : ORG_ALL))
                    .orgName(isAll ? "全部机构" : "机构-" + entry.getKey())
                    .orgCode(isAll ? "ALL" : entry.getKey())
                    .scoreRate(scoreRate)
                    .passRuleCount(passCount)
                    .totalRuleCount(executedCount)
                    .build());
        }
        // 按得分率排名
        scores.sort(Comparator.comparing(QualityOrgScoreDO::getScoreRate).reversed());
        for (int i = 0; i < scores.size(); i++) {
            scores.get(i).setRegionRank(i + 1);
        }
        orgScoreMapper.insertBatch(scores);
        return scores.size();
    }

}
