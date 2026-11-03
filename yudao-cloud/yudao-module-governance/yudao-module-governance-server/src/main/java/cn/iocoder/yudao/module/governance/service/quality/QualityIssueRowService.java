package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 问题处置属于治理领域；回源读取沿用提供方当前用户的行列权限与脱敏。 */
@Service
public class QualityIssueRowService {

    static final int READ_LIMIT = 1000;

    @Resource
    private QualityAuditLogMapper auditLogs;

    @Resource
    private QualityIssueDataMapper issues;

    @Resource
    private QualityTaskInstanceMapper instances;

    @Resource
    private QualityIssueRowStateMapper states;

    @Resource
    private QualityTaskEvaluationService evaluationService;

    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 按筛选条件分页查询质量问题数据行。
     */
    public QualityIssueRowPageRespVO page(QualityIssueRowQueryReqVO request) {
        var observation = observe(request.getAuditLogId());
        var response = new QualityIssueRowPageRespVO();
        response.setIssueId(observation.issueId());
        response.setColumns(observation.columns());
        response.setTruncated(observation.truncated());
        var filters = request.getFilters() == null ? Map.<String, String>of() : request.getFilters();
        if (!observation.columns().containsAll(filters.keySet())) {
            throw exception(QUALITY_ISSUE_ACTION_INVALID);
        }
        var filtered = observation.rows().stream().filter(row -> filters.entrySet().stream()
                .allMatch(filter -> filter.getValue() == null || filter.getValue().isBlank()
                        || Objects.toString(row.getValues().get(filter.getKey()), "")
                        .contains(filter.getValue().trim()))).toList();
        response.setTotal((long) filtered.size());
        int size = Math.min(100, Math.max(1, request.getPageSize()));
        long offset = (Math.max(1L, request.getPageNo()) - 1) * size;
        response.setList(offset >= filtered.size() ? List.of()
                : filtered.subList((int) offset, (int) Math.min(filtered.size(), offset + size)));
        return response;
    }

    /**
     * 受理质量问题数据行行级处理操作并保存结果。
     */
    public void act(QualityIssueRowActionReqVO request) {
        if (!Set.of("IGNORE", "REMARK").contains(Objects.toString(request.getAction(), ""))
                || request.getRows() == null || request.getRows().isEmpty() || request.getRows().size() > 100
                || ("REMARK".equals(request.getAction()) && (request.getRemark() == null
                || request.getRemark().isBlank() || request.getRemark().length() > 500))) {
            throw exception(QUALITY_ISSUE_ACTION_INVALID);
        }
        // 先回源核验授权及可见行；持有平台事务时不调用 Feign。
        var observation = observe(request.getAuditLogId());
        var visible = observation.rows().stream().filter(row -> Boolean.TRUE.equals(row.getProcessable()))
                .collect(Collectors.toMap(QualityIssueRowPageRespVO.Row::getRowKey, Function.identity()));
        Set<String> distinct = new HashSet<>();
        for (var row : request.getRows()) {
            if (row == null || row.getRevision() == null || row.getRevision() < 0
                    || !distinct.add(row.getRowKey()) || !visible.containsKey(row.getRowKey())
                    || !Objects.equals(row.getRevision(), visible.get(row.getRowKey()).getRevision())) {
                throw exception(QUALITY_ISSUE_ROW_STALE);
            }
        }
        String remark = request.getRemark() == null ? "" : request.getRemark().trim();
        String correlation = UUID.randomUUID().toString();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (var row : request.getRows()) {
                states.ensureState(observation.issueId(), row.getRowKey(), observation.actor().toString());
                if (states.compareAndSet(observation.issueId(), row.getRowKey(), row.getRevision(),
                        request.getAction(), remark, observation.actor().toString()) != 1) {
                    throw exception(QUALITY_ISSUE_ROW_STALE);
                }
                // 与状态同事务提交，备注只记录摘要，不把源数据或备注明文扩散到日志。
                states.recordAction(observation.issueId(), row.getRowKey(), observation.actor(),
                        request.getAction(), visible.get(row.getRowKey()).getState(), row.getRevision(),
                        DigestUtil.sha256Hex(remark), correlation);
            }
        });
    }

    /**
     * 采集质量问题数据行当前数据或提交范围的观察证据。
     */
    private Observation observe(Long auditLogId) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || actor <= 0) {
            throw exception(QUALITY_ISSUE_ACCESS_FORBIDDEN);
        }
        var audit = auditLogId == null ? null : auditLogs.selectById(auditLogId);
        if (audit == null) {
            throw exception(QUALITY_ISSUE_NOT_EXISTS);
        }
        List<String> fields = audit.getCheckField() == null || "整表".equals(audit.getCheckField()) ? List.of()
                : Arrays.stream(audit.getCheckField().split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
        var matches = issues.selectList(new LambdaQueryWrapperX<QualityIssueDataDO>()
                .eq(QualityIssueDataDO::getAuditLogId, auditLogId)
                .eq(QualityIssueDataDO::getStatScope, QualityIssueDataDO.SCOPE_ALL));
        if (matches.isEmpty()) {
            return new Observation(actor, null, fields, List.of(), false);
        }
        if (matches.size() != 1) {
            throw exception(QUALITY_ISSUE_ACCESS_FORBIDDEN);
        }
        var issue = matches.getFirst();
        var instance = instances.selectById(audit.getInstanceId());
        if (instance == null || !Objects.equals(instance.getTaskId(), audit.getTaskId())
                || !Objects.equals(issue.getInstanceId(), instance.getId())
                || !Objects.equals(issue.getObjectId(), audit.getCheckObjectId())
                || !Objects.equals(issue.getTaskId(), audit.getTaskId())) {
            throw exception(QUALITY_ISSUE_ACCESS_FORBIDDEN);
        }
        QualityTaskExecutor.GateDefinition definition;
        try {
            definition = JsonUtils.parseObject(instance.getDefinitionSnapshot(),
                    QualityTaskExecutor.GateDefinition.class);
        } catch (RuntimeException invalid) {
            throw exception(QUALITY_ISSUE_ACCESS_FORBIDDEN);
        }
        if (definition == null || definition.getObjects() == null) {
            throw exception(QUALITY_ISSUE_ACCESS_FORBIDDEN);
        }
        var objects = definition.getObjects().stream()
                .filter(object -> Objects.equals(object.getId(), issue.getObjectId())).toList();
        if (objects.size() != 1 || objects.getFirst().getDatasourceConnId() == null) {
            throw exception(QUALITY_ISSUE_ACCESS_FORBIDDEN);
        }
        // 从冻结规则重新生成 SQL，不执行历史定位 SQL；问题处置仍按当前用户与冻结定义隔离。
        var result = evaluationService.readFrozenRows(definition, audit, actor, READ_LIMIT);
        if (result == null || result.rows() == null) {
            throw exception(QUALITY_ISSUE_ACCESS_FORBIDDEN);
        }
        boolean truncated = result.truncated() || result.rows().size() > READ_LIMIT;
        String definitionKey = DigestUtil.sha256Hex(instance.getDefinitionSnapshot());
        List<QualityIssueRowPageRespVO.Row> rows = new ArrayList<>();
        for (var source : result.rows().stream().limit(READ_LIMIT).toList()) {
            var row = new QualityIssueRowPageRespVO.Row();
            Map<String, String> values = new TreeMap<>();
            source.forEach((key, value) -> values.put(key, value == null ? null : String.valueOf(value)));
            row.setValues(values);
            // 不同用户不共享处置，源内容及冻结定义变化不继承旧结论。
            row.setRowKey(DigestUtil.sha256Hex(JsonUtils.toJsonString(List.of(issue.getId(), actor,
                    auditLogId, definitionKey, values))));
            row.setState("UNPROCESSED");
            row.setRevision(0);
            row.setRemark("");
            rows.add(row);
        }
        var frequencies = rows.stream()
                .collect(Collectors.groupingBy(QualityIssueRowPageRespVO.Row::getRowKey, Collectors.counting()));
        var persisted = states.selectStates(issue.getId(), frequencies.keySet()).stream()
                .collect(Collectors.toMap(QualityIssueRowStateDO::getRowKey, Function.identity()));
        for (var row : rows) {
            // 截断窗口无法排除窗口外的重复投影；只有完整结果中的唯一投影允许处置。
            row.setProcessable(!truncated && frequencies.get(row.getRowKey()) == 1);
            var saved = persisted.get(row.getRowKey());
            if (saved != null) {
                row.setState(saved.getState());
                row.setRevision(saved.getRevision());
                row.setRemark(saved.getRemark());
            }
        }
        List<String> columns = new ArrayList<>();
        if (result.columns() != null) {
            columns.addAll(result.columns());
        }
        if (columns.isEmpty() && !rows.isEmpty()) {
            columns.addAll(rows.getFirst().getValues().keySet());
        }
        // 只展示当前用户真实拿到的列，防止通过历史字段泄漏被禁止的列名。
        return new Observation(actor, issue.getId(), List.copyOf(columns), rows, truncated);
    }

    private record Observation(Long actor, Long issueId, List<String> columns,
                               List<QualityIssueRowPageRespVO.Row> rows, boolean truncated) {

    }
}
