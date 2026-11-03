package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog.*;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance.QualityTaskInstanceRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_TASK_INSTANCE_NOT_EXISTS;

/** 质量报告元信息按平台权限可见；源数据由独立受管读取接口校验当前用户。 */
@Service
public class QualityReportService {

    @Resource
    private QualityTaskInstanceMapper instances;

    @Resource
    private QualityTaskMapper tasks;

    @Resource
    private QualityAuditLogMapper audits;

    @Resource
    private QualityIssueDataMapper issues;

    private static final List<String> DIMENSIONS = List.of("ACCURACY", "NORMALIZATION", "TIMELINESS", "CONSISTENCY",
            "COMPLETENESS");

    /**
     * 按筛选条件分页查询质量报告。
     */
    public PageResult<QualityTaskInstanceRespVO> page(QualityReportPageReqVO request) {
        var query = new LambdaQueryWrapperX<QualityTaskInstanceDO>()
                .eqIfPresent(QualityTaskInstanceDO::getInstanceStatus, request.getInstanceStatus())
                .eqIfPresent(QualityTaskInstanceDO::getTaskId, request.getTaskId())
                .orderByDesc(QualityTaskInstanceDO::getId);
        if (StrUtil.isNotBlank(request.getTaskName())) {
            query.apply(
                    "COALESCE(NULLIF(definition_snapshot, '')::jsonb -> 'task' ->> 'taskName', instance_name) LIKE {0}",
                    "%" + request.getTaskName() + "%");
        }
        var page = instances.selectPage(request, query);
        return new PageResult<>(page.getList().stream().map(this::instance).toList(), page.getTotal());
    }

    /**
     * 根据质量实例与审计记录组装质量报告详情。
     */
    public QualityReportRespVO report(Long id, Integer days) {
        var selected = instances.selectById(id);
        if (selected == null) {
            throw exception(QUALITY_TASK_INSTANCE_NOT_EXISTS);
        }
        int range = days == null || !List.of(7, 15, 30).contains(days) ? 7 : days;
        List<QualityAuditLogDO> logs = audits.selectListByInstanceId(id);
        var snapshot = snapshot(selected);
        Map<Long, List<Long>> issueIds = issues.selectList(new LambdaQueryWrapperX<QualityIssueDataDO>()
                        .eq(QualityIssueDataDO::getInstanceId, id)
                        .eq(QualityIssueDataDO::getStatScope, QualityIssueDataDO.SCOPE_ALL))
                .stream().filter(value -> value.getAuditLogId() != null)
                .collect(Collectors.groupingBy(QualityIssueDataDO::getAuditLogId,
                        Collectors.mapping(QualityIssueDataDO::getId, Collectors.toList())));
        List<QualityTaskInstanceDO> earlier = instances.selectList(new LambdaQueryWrapperX<QualityTaskInstanceDO>()
                .eq(QualityTaskInstanceDO::getTaskId, selected.getTaskId()).lt(QualityTaskInstanceDO::getId, id)
                .orderByDesc(QualityTaskInstanceDO::getId).last("LIMIT 1"));
        Map<String, long[]> prior = earlier.isEmpty() ? Map.of()
                : stats(audits.selectListByInstanceId(earlier.getFirst().getId()), snapshot(earlier.getFirst()));
        Map<String, long[]> totals = stats(logs, snapshot);
        List<QualityReportRespVO.Dimension> dimensions = DIMENSIONS.stream().map(dimension -> {
            long[] values = totals.getOrDefault(dimension, new long[4]);
            long[] previous = prior.getOrDefault(dimension, new long[4]);
            return new QualityReportRespVO.Dimension(dimension, Math.toIntExact(values[2]), values[1], values[0],
                    values[3] > 0 ? null : percent(values[1], values[0]),
                    values[3] > 0 ? null : percent(values[0] - values[1], values[0]),
                    previous[3] > 0 ? null : percent(previous[1], previous[0]));
        }).toList();
        Map<Long, QualityCheckObjectDO> objects = snapshot == null || snapshot.getObjects() == null ? Map.of()
                : snapshot.getObjects().stream()
                .collect(Collectors.toMap(QualityCheckObjectDO::getId, Function.identity(), (a, b) -> a));
        List<QualityReportRespVO.Rule> rules = logs.stream().map(log -> {
            var rule = frozenRule(snapshot, log);
            var object = objects.get(log.getCheckObjectId());
            List<String> fields = StrUtil.isBlank(log.getCheckField()) ? List.of() : Arrays.asList(
                    log.getCheckField().split(","));
            return new QualityReportRespVO.Rule(log.getId(), log.getId(), log.getRuleId(), log.getEvaluationId(),
                    log.getRuleName(), rule == null ? log.getRuleType() : rule.getStrategyKey(),
                    QualityTaskEvaluationService.ruleTypeName(rule == null ? log.getRuleType() : rule.getStrategyKey()),
                    rule == null ? null : rule.getSeverity(), rule == null ? null : rule.getExample(),
                    rule == null ? null : rule.getRemark(), log.getCheckTime(),
                    rule == null ? null : rule.getQualityDim(), log.getCheckObjectId(),
                    object == null ? null : object.getDatasourceConnName(), log.getTableName(), log.getCheckField(),
                    fields,
                    log.getTotalCount(), log.getFailCount(), QualityAuditLogDO.RESULT_ERROR.equals(log.getResultType())
                    ? null : percent(value(log.getFailCount()), value(log.getTotalCount())),
                    log.getResultType(), issueIds.getOrDefault(log.getId(), List.of()));
        }).toList();
        return new QualityReportRespVO(instance(selected), dimensions, trend(selected, range), rules);
    }

    /**
     * 汇总质量报告的检查与问题数量。
     */
    private Map<String, long[]> stats(List<QualityAuditLogDO> logs, QualityTaskExecutor.GateDefinition snapshot) {
        Map<String, long[]> result = new LinkedHashMap<>();
        for (var log : logs) {
            var rule = frozenRule(snapshot, log);
            if (rule == null || rule.getQualityDim() == null) {
                continue;
            }
            long[] entry = result.computeIfAbsent(rule.getQualityDim(), ignored -> new long[4]);
            entry[2]++;
            if (List.of(QualityAuditLogDO.RESULT_PASS, QualityAuditLogDO.RESULT_FAIL).contains(log.getResultType())) {
                entry[0] += value(log.getTotalCount());
                entry[1] += value(log.getFailCount());
            } else {
                entry[3]++;
            }
        }
        return result;
    }

    /**
     * 汇总指定周期内的质量运行趋势。
     */
    private List<QualityReportRespVO.Trend> trend(QualityTaskInstanceDO selected, int days) {
        LocalDate anchor = time(selected).toLocalDate();
        LocalDate first = anchor.minusDays(days - 1L);
        List<QualityTaskInstanceDO> runs = instances.selectList(new LambdaQueryWrapperX<QualityTaskInstanceDO>()
                .eq(QualityTaskInstanceDO::getTaskId, selected.getTaskId())
                .le(QualityTaskInstanceDO::getId, selected.getId())
                .ge(QualityTaskInstanceDO::getStartTime, first.atStartOfDay())
                .lt(QualityTaskInstanceDO::getStartTime, anchor.plusDays(1).atStartOfDay())
                .orderByDesc(QualityTaskInstanceDO::getId));
        Map<LocalDate, QualityTaskInstanceDO> latest = new HashMap<>();
        runs.forEach(run -> latest.putIfAbsent(time(run).toLocalDate(), run));
        List<QualityReportRespVO.Trend> result = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate day = first.plusDays(i);
            var run = latest.get(day);
            result.add(new QualityReportRespVO.Trend(day.toString(), run == null || run.getScore() == null
                    || value(run.getTotalCount()) == 0 || !QualityTaskInstanceDO.STATUS_SUCCESS.equals(
                    run.getInstanceStatus())
                    ? null : run.getScore().toPlainString()));
        }
        return result;
    }

    /**
     * 读取指定质量运行实例。
     */
    private QualityTaskInstanceRespVO instance(QualityTaskInstanceDO row) {
        var result = BeanUtils.toBean(row, QualityTaskInstanceRespVO.class);
        var snapshot = snapshot(row);
        result.setTaskName(snapshot == null || snapshot.getTask() == null ? row.getInstanceName() : snapshot.getTask()
                .getTaskName());
        return result;
    }

    /**
     * 解析质量报告冻结快照。
     */
    private static QualityTaskExecutor.GateDefinition snapshot(QualityTaskInstanceDO instance) {
        if (StrUtil.isBlank(instance.getDefinitionSnapshot())) {
            return null;
        }
        try {
            return JsonUtils.parseObject(instance.getDefinitionSnapshot(), QualityTaskExecutor.GateDefinition.class);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /**
     * 从质量运行快照中读取实际执行的规则定义。
     */
    private static QualityRuleDO frozenRule(QualityTaskExecutor.GateDefinition snapshot, QualityAuditLogDO log) {
        if (snapshot == null || snapshot.getRules() == null) {
            return null;
        }
        return snapshot.getRules().stream().filter(rule -> log.getEvaluationId() != null
                        ? Objects.equals(rule.getExecutionEvaluationId(), log.getEvaluationId())
                        : log.getRuleId() != null && Objects.equals(rule.getId(), log.getRuleId()))
                .filter(rule -> rule.getExecutionObjectId() == null || Objects.equals(rule.getExecutionObjectId(),
                        log.getCheckObjectId()))
                .findFirst().orElse(null);
    }

    /**
     * 转换质量报告时间值。
     */
    private static LocalDateTime time(QualityTaskInstanceDO value) {
        return value.getStartTime() != null ? value.getStartTime()
                : value.getCreateTime() != null ? value.getCreateTime() : LocalDateTime.now();
    }

    /**
     * 读取质量报告中的指定值。
     */
    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    /**
     * 按约定比例计算质量报告百分比。
     */
    private static String percent(long value, long total) {
        return total <= 0 ? null : BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP).toPlainString();
    }
}
