package cn.iocoder.yudao.module.governance.service.etl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.api.etl.dto.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.etl.InlineQualityReportDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.etl.InlineQualityReportMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleSetItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderEtlReportMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderRecheckMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderSubmissionMapper;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.CleanRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.CleanRuleMapper;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.governance.service.quality.QualityWorkOrderService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.quality.core.row.QualityRowEvaluator;
import cn.iocoder.yudao.framework.quality.core.row.QualityRowRule;
import tools.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** Governance 负责的 ETL 策略适配层；不进行逐行 RPC，也不模拟 SQL 规则。 */
@Service
public class GovernanceEtlService {

    private static final QualityRowEvaluator ROW_EVALUATOR = new QualityRowEvaluator();

    private static final Set<String> ROW_STRATEGIES = ROW_EVALUATOR.supportedStrategies();

    @Resource
    private QualityRuleMapper ruleMapper;

    @Resource
    private QualityRuleSetMapper ruleSetMapper;

    @Resource
    private QualityRuleSetItemMapper ruleSetItemMapper;

    @Resource
    private InlineQualityReportMapper reportMapper;

    @Resource
    private AdminUserApi userApi;

    @Resource
    private PermissionApi permissionApi;

    @Resource
    private QualityWorkOrderService qualityWorkOrderService;

    @Resource
    private QualityWorkOrderEtlReportMapper workOrderReportMapper;

    @Resource
    private QualityWorkOrderMapper workOrderMapper;

    @Resource
    private QualityWorkOrderSubmissionMapper submissionMapper;

    @Resource
    private QualityWorkOrderRecheckMapper recheckMapper;

    @Resource
    private CleanRuleMapper cleanRuleMapper;

    @Resource
    private CleanFunctionRegistry cleanFunctionRegistry;

    /**
     * 读取数据行质量计划。
     */
    public RowQualityPlanRespDTO getRowQualityPlan(RowQualityPlanReqDTO request) {
        authorize(request.getExecutionUserId(), "governance:quality-rule:query");
        if (request.getRuleSetId() != null) {
            authorize(request.getExecutionUserId(), "governance:quality-rule-set:query");
        }
        if ((request.getRuleSetId() == null) == (request.getRuleIds() == null || request.getRuleIds().isEmpty())) {
            throw exception(QUALITY_ETL_PLAN_INVALID);
        }
        List<Long> ruleIds;
        if (request.getRuleSetId() != null) {
            QualityRuleSetDO ruleSet = ruleSetMapper.selectById(request.getRuleSetId());
            if (ruleSet == null || !Objects.equals(ruleSet.getStatus(), QualityRuleSetDO.STATUS_ENABLE)) {
                throw exception(QUALITY_RULE_SET_NOT_EXISTS);
            }
            ruleIds = ruleSetItemMapper.selectListByRuleSetId(ruleSet.getId()).stream()
                    .map(QualityRuleSetItemDO::getRuleId).distinct().toList();
        } else {
            ruleIds = request.getRuleIds().stream().distinct().toList();
        }
        if (ruleIds.isEmpty()) {
            throw exception(QUALITY_TASK_RULE_EMPTY);
        }
        if (ruleIds.size() > 100) {
            throw exception(QUALITY_ETL_SNAPSHOT_LIMIT_EXCEEDED);
        }
        Map<Long, QualityRuleDO> rulesById = ruleMapper.selectBatchIds(ruleIds).stream()
                .collect(Collectors.toMap(QualityRuleDO::getId, rule -> rule));
        List<RowQualityRuleDTO> supported = new ArrayList<>();
        List<UnsupportedQualityRuleDTO> unsupported = new ArrayList<>();
        Map<String, String> bindings = request.getFieldBindings() == null ? Map.of() : request.getFieldBindings();
        Set<String> inputFields = request.getInputFields() == null ? null : new HashSet<>(request.getInputFields());
        for (Long id : ruleIds) {
            QualityRuleDO rule = rulesById.get(id);
            String reason = null;
            if (rule == null || !Objects.equals(rule.getStatus(), QualityRuleDO.STATUS_ENABLE)) {
                reason = "RULE_UNAVAILABLE";
            } else if (!QualityRuleDO.CHECK_LEVEL_FIELD.equals(rule.getCheckLevel())) {
                reason = "NOT_FIELD_LEVEL";
            } else if (!ROW_STRATEGIES.contains(rule.getStrategyKey())) {
                reason = "UNSUPPORTED_ROW_STRATEGY";
            }
            String field = bindings.get(String.valueOf(id));
            if (reason == null && (field == null || field.isBlank()
                    || inputFields != null && !inputFields.contains(field))) {
                reason = "FIELD_BINDING_INVALID";
            }
            if (reason == null && !Set.of("CRITICAL", "HIGH", "MID", "LOW").contains(rule.getSeverity())) {
                reason = "UNSUPPORTED_SEVERITY";
            }
            Map<String, Object> config = null;
            if (reason == null) {
                try {
                    Map<String, Object> parsed = JsonUtils.parseObject(rule.getParamSchema(), new TypeReference<>() {
                    });
                    config = parsed == null ? null : new LinkedHashMap<>(parsed);
                } catch (RuntimeException ex) {
                    reason = "RULE_CONFIG_INVALID";
                }
                if (config == null) {
                    reason = "RULE_CONFIG_INVALID";
                }
            }
            if (reason == null) {
                config.remove("column");
                try {
                    ROW_EVALUATOR.compile(List.of(new QualityRowRule(rule.getRuleCode(),
                            rule.getStrategyKey(), field, config, toRowSeverity(rule.getSeverity()))));
                } catch (RuntimeException ex) {
                    reason = "RULE_CONFIG_INVALID";
                }
            }
            if (reason != null) {
                UnsupportedQualityRuleDTO item = new UnsupportedQualityRuleDTO();
                item.setRuleId(id);
                item.setCode(rule == null ? null : rule.getRuleCode());
                item.setReason(reason);
                unsupported.add(item);
                continue;
            }
            RowQualityRuleDTO item = new RowQualityRuleDTO();
            item.setRuleId(rule.getId());
            item.setCode(rule.getRuleCode());
            item.setStrategy(rule.getStrategyKey());
            item.setField(field);
            item.setConfig(config);
            item.setSeverity(toRowSeverity(rule.getSeverity()));
            supported.add(item);
        }
        RowQualityPlanRespDTO response = new RowQualityPlanRespDTO();
        response.setRules(supported);
        response.setUnsupportedRules(unsupported);
        response.setExecutable(unsupported.isEmpty() && !supported.isEmpty());
        response.setFrozenAt(LocalDateTime.now());
        response.setPlanHash(DigestUtil.sha256Hex(canonicalJson(Map.of("rules", supported,
                "unsupported", unsupported, "ruleSetId", Objects.toString(request.getRuleSetId(), "")))));
        return response;
    }

    /** 使用持久化函数参数冻结已启用的清洗规则；客户端仅提供字段绑定。 */
    public CleanRulePlanRespDTO snapshotCleanRulePlan(CleanRulePlanReqDTO request) {
        if (request == null || request.getRules() == null || request.getRules().isEmpty()
                || request.getRules().size() > 50 || request.getRules().stream().anyMatch(Objects::isNull)) {
            throw exception(QUALITY_ETL_PLAN_INVALID);
        }
        authorize(request.getExecutionUserId(), "governance:clean-rule:query");

        List<Long> ids = request.getRules().stream().map(CleanRulePlanReqDTO.Rule::getRuleId).toList();
        if (ids.stream().anyMatch(Objects::isNull) || ids.stream().distinct().count() != ids.size()) {
            throw exception(QUALITY_ETL_PLAN_INVALID);
        }
        Map<Long, CleanRuleDO> byId = cleanRuleMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(CleanRuleDO::getId, rule -> rule));
        List<CleanRulePlanRespDTO.Rule> frozen = new ArrayList<>(ids.size());
        for (CleanRulePlanReqDTO.Rule requested : request.getRules()) {
            CleanRuleDO rule = byId.get(requested.getRuleId());
            if (rule == null || StrUtil.isBlank(rule.getCode()) || !Objects.equals(rule.getStatus(),
                    CleanRuleDO.STATUS_ENABLE)
                    || rule.getRuleLevel() == null || !Set.of(1, 2).contains(rule.getRuleLevel()) || StrUtil.isBlank(
                    rule.getStrategyKey())
                    || !cleanFunctionRegistry.contains(rule.getStrategyKey())) {
                throw exception(QUALITY_ETL_PLAN_INVALID);
            }
            var binding = cleanFunctionRegistry.getColumnBinding(rule.getStrategyKey());
            List<String> fields = cleanFields(requested, binding);
            if (binding == cn.iocoder.yudao.framework.quality.core.clean.CleanFunction.ColumnBinding.SINGLE && fields.size() != 1) {
                throw exception(QUALITY_ETL_PLAN_INVALID);
            }
            Map<String, Object> storedConfig;
            try {
                storedConfig = StrUtil.isBlank(rule.getConfigJson()) ? new LinkedHashMap<>()
                        : JsonUtils.parseObject(rule.getConfigJson(), new TypeReference<>() {
                });
                if (storedConfig == null) {
                    throw new IllegalArgumentException("清洗规则参数不是对象");
                }
                storedConfig = cleanFunctionRegistry.normalizeConfig(rule.getStrategyKey(), storedConfig);
                if (storedConfig.containsKey("columns")) {
                    throw new IllegalArgumentException("持久清洗参数不能包含字段绑定");
                }
                if (JsonUtils.toJsonString(storedConfig)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64 * 1024) {
                    throw new IllegalArgumentException("清洗规则参数超过 64 KiB");
                }
                validateCleanInputFields(fields, storedConfig, rule.getStrategyKey(), request.getInputFields());
            } catch (RuntimeException ex) {
                throw exception(QUALITY_ETL_PLAN_INVALID);
            }
            if (requested.getLegacyConfig() != null) {
                Map<String, Object> legacy;
                try {
                    legacy = cleanFunctionRegistry.normalizeConfig(rule.getStrategyKey(), requested.getLegacyConfig());
                    if (JsonUtils.toJsonString(legacy)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64 * 1024) {
                        throw new IllegalArgumentException("旧节点参数超过 64 KiB");
                    }
                } catch (RuntimeException ex) {
                    throw exception(QUALITY_ETL_PLAN_INVALID);
                }
                Object legacyColumns = legacy.remove("columns");
                if (legacyColumns == null && binding == cn.iocoder.yudao.framework.quality.core.clean.CleanFunction.ColumnBinding.ALL) {
                    legacyColumns = List.of();
                }
                if (!Objects.equals(legacyColumns, fields)) {
                    throw exception(QUALITY_ETL_PLAN_INVALID);
                }
                Map<String, Object> persistedParameters = new LinkedHashMap<>(storedConfig);
                persistedParameters.remove("columns");
                if (!Objects.equals(legacy, persistedParameters)) {
                    throw exception(QUALITY_ETL_PLAN_INVALID);
                }
            }
            Map<String, Object> config = new LinkedHashMap<>(storedConfig);
            config.put("columns", fields);
            CleanRulePlanRespDTO.Rule item = new CleanRulePlanRespDTO.Rule();
            item.setRuleId(rule.getId());
            item.setCode(rule.getCode());
            item.setName(rule.getName());
            item.setStrategyKey(rule.getStrategyKey());
            item.setConfig(config);
            frozen.add(item);
        }
        CleanRulePlanRespDTO response = new CleanRulePlanRespDTO();
        response.setRules(frozen);
        response.setFrozenAt(LocalDateTime.now());
        String canonicalPlan = canonicalJson(Map.of("rules",
                frozen.stream().map(rule -> Map.of("ruleId", rule.getRuleId(), "code", rule.getCode(),
                        "strategyKey", rule.getStrategyKey(), "config", rule.getConfig())).toList()));
        if (canonicalPlan.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64 * 1024) {
            throw exception(QUALITY_ETL_PLAN_INVALID);
        }
        response.setPlanHash(DigestUtil.sha256Hex(canonicalPlan));
        return response;
    }

    /**
     * 清理治理ETL的字段。
     */
    private static List<String> cleanFields(CleanRulePlanReqDTO.Rule rule,
            cn.iocoder.yudao.framework.quality.core.clean.CleanFunction.ColumnBinding binding) {
        boolean single = StrUtil.isNotBlank(rule.getField());
        if (binding == cn.iocoder.yudao.framework.quality.core.clean.CleanFunction.ColumnBinding.ALL) {
            if (single || rule.getFields() != null) {
                throw exception(QUALITY_ETL_PLAN_INVALID);
            }
            return List.of();
        }
        if (single == (rule.getFields() != null)) {
            throw exception(QUALITY_ETL_PLAN_INVALID);
        }
        List<String> fields = single ? List.of(rule.getField()) : rule.getFields();
        if (fields == null || fields.isEmpty() || fields.size() > 32 || fields.stream().anyMatch(
                field -> field == null || field.isBlank() || !field.equals(field.trim()) || field.length() > 128)
                || fields.stream().distinct().count() != fields.size()) {
            throw exception(QUALITY_ETL_PLAN_INVALID);
        }
        return List.copyOf(fields);
    }

    /**
     * 校验治理ETL的清洗输入字段，不满足约束时拒绝当前操作。
     */
    private void validateCleanInputFields(List<String> fields, Map<String, Object> config,
            String strategy, List<String> inputFields) {
        Set<String> required = new HashSet<>(fields);
        required.addAll(cleanFunctionRegistry.getReferencedColumns(strategy, config));
        if (inputFields != null && (!new HashSet<>(inputFields).containsAll(required)
                || inputFields.stream().anyMatch(Objects::isNull))) {
            throw new IllegalArgumentException("清洗绑定、排序或更新字段不在输入字段中");
        }
    }

    /**
     * 受理内联质量结果。
     */
    @Transactional(rollbackFor = Exception.class)
    public InlineQualityResultRespDTO acceptInlineQualityResult(InlineQualityResultReqDTO request) {
        authorize(request.getExecutionUserId(), "governance:quality-rule:query");
        long classifiedCount;
        try {
            classifiedCount = Math.addExact(Math.addExact(request.getPassCount(), request.getWarnCount()),
                    request.getRejectCount());
        } catch (ArithmeticException overflow) {
            throw exception(QUALITY_ETL_RESULT_INVALID);
        }
        if (classifiedCount != request.getTotalCount()) {
            throw exception(QUALITY_ETL_RESULT_INVALID);
        }
        if ((request.getIdentityFields() == null) != (request.getIdentityHash() == null)
                || (request.getIdentityFields() != null && (request.getIdentityFields().isEmpty()
                || request.getIdentityFields().stream().distinct().count() != request.getIdentityFields().size()))) {
            throw exception(QUALITY_ETL_RESULT_INVALID);
        }
        String issueRefs = JsonUtils.toJsonString(
                request.getIssueReferences() == null ? List.of() : request.getIssueReferences());
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("executionUserId", request.getExecutionUserId());
        content.put("runId", request.getRunId());
        content.put("nodeId", request.getNodeId());
        content.put("batchId", request.getBatchId());
        content.put("planHash", request.getPlanHash());
        content.put("total", request.getTotalCount());
        content.put("pass", request.getPassCount());
        content.put("warn", request.getWarnCount());
        content.put("reject", request.getRejectCount());
        content.put("issueReferences", request.getIssueReferences() == null ? List.of() : request.getIssueReferences());
        // 保留升级前未携带此可选凭据的请求哈希。
        if (request.getIdentityFields() != null || request.getIdentityHash() != null) {
            content.put("identityFields",
                    request.getIdentityFields() == null ? List.of() : request.getIdentityFields());
            content.put("identityHash", Objects.toString(request.getIdentityHash(), ""));
        }
        String requestHash = DigestUtil.sha256Hex(canonicalJson(content));
        InlineQualityReportDO existing = reportMapper.selectByIdempotencyKey(request.getIdempotencyKey());
        InlineQualityResultRespDTO response = new InlineQualityResultRespDTO();
        if (existing != null) {
            if (!Objects.equals(existing.getRequestHash(), requestHash)) {
                throw exception(QUALITY_RUN_KEY_CONFLICT);
            }
            response.setStatus("REPLAY");
            response.setReportId(existing.getId());
            return response;
        }
        InlineQualityReportDO report = InlineQualityReportDO.builder()
                .runId(request.getRunId()).nodeId(request.getNodeId()).batchId(request.getBatchId())
                .sourceType("ETL_INLINE").planHash(request.getPlanHash())
                .idempotencyKey(request.getIdempotencyKey()).requestHash(requestHash)
                .executionUserId(request.getExecutionUserId()).totalCount(request.getTotalCount())
                .passCount(request.getPassCount()).warnCount(request.getWarnCount())
                .rejectCount(request.getRejectCount())
                .issueReferencesJson(issueRefs)
                .identityFieldsJson(request.getIdentityFields() == null ? null : JsonUtils.toJsonString(
                        request.getIdentityFields()))
                .identityHash(request.getIdentityHash()).build();
        if (reportMapper.insert(report) != 1 || report.getId() == null || report.getId() <= 0) {
            throw new IllegalStateException("ETL quality report insert did not return a persisted report id");
        }
        response.setStatus("ACCEPTED");
        response.setReportId(report.getId());
        return response;
    }

    /**
     * 核验调用权限后读取 ETL 内联质量报告。
     */
    public InlineQualityReportDTO queryInlineQualityReport(InlineQualityReportQueryReqDTO request) {
        Long actorUserId = SecurityFrameworkUtils.getLoginUserId();
        if (actorUserId == null) {
            throw exception(QUALITY_RUN_FORBIDDEN);
        }
        authorize(actorUserId, "governance:quality-audit-log:query");
        InlineQualityReportDO report = reportMapper.selectById(request.getReportId());
        if (report == null) {
            throw exception(QUALITY_ETL_REPORT_NOT_EXISTS);
        }
        InlineQualityReportDTO response = new InlineQualityReportDTO();
        response.setReportId(report.getId());
        response.setRunId(report.getRunId());
        response.setNodeId(report.getNodeId());
        response.setBatchId(report.getBatchId());
        response.setSourceType(report.getSourceType());
        response.setPlanHash(report.getPlanHash());
        response.setTotalCount(report.getTotalCount());
        response.setPassCount(report.getPassCount());
        response.setWarnCount(report.getWarnCount());
        response.setRejectCount(report.getRejectCount());
        response.setIssueReferences(JsonUtils.parseArray(report.getIssueReferencesJson(), String.class));
        response.setIdentityFields(StrUtil.isBlank(report.getIdentityFieldsJson()) ? null
                : JsonUtils.parseArray(report.getIdentityFieldsJson(), String.class));
        response.setIdentityHash(report.getIdentityHash());
        response.setCreateTime(report.getCreateTime());
        response.setWorkOrderId(qualityWorkOrderService.getVisibleInlineReportWorkOrderId(
                report.getId(), actorUserId));
        return response;
    }

    /**
     * 创建内联质量工作工单。
     */
    public Long createInlineQualityWorkOrder(CreateInlineQualityWorkOrderReqDTO request) {
        Long actorUserId = SecurityFrameworkUtils.getLoginUserId();
        if (actorUserId == null) {
            throw exception(QUALITY_RUN_FORBIDDEN);
        }
        authorize(actorUserId, "governance:quality-audit-log:query", "governance:quality-work-order:create");
        return qualityWorkOrderService.createInlineEtlWorkOrder(actorUserId, request.getReportId(),
                request.getOrderName(), request.getDescription(), request.getDeadline());
    }

    /** 按保守原则进行有数量上限的归档授权；任何歧义或可见性校验失败都拒绝整个批次。 */
    public boolean canArchiveReports(QualityEtlArchiveAuthorizeReqDTO request) {
        if (request == null || request.getExecutionUserId() == null || request.getReportIds() == null
                || request.getReportIds().isEmpty() || request.getReportIds().size() > 32
                || request.getReportIds().stream().anyMatch(Objects::isNull)
                || request.getReportIds().stream().distinct().count() != request.getReportIds().size()) {
            return false;
        }
        Long actor = request.getExecutionUserId();
        try {
            authorize(actor, "dataforge:etl:query", "governance:quality-audit-log:query");
            for (Long reportId : request.getReportIds()) {
                InlineQualityReportDO report = reportMapper.selectById(reportId);
                if (report == null || !"ETL_INLINE".equals(report.getSourceType())) {
                    return false;
                }
                Long workOrderId = qualityWorkOrderService.getVisibleInlineReportWorkOrderId(reportId, actor);
                if (workOrderId == null) {
                    continue;
                }
                QualityWorkOrderEtlReportDO link = workOrderReportMapper.selectByWorkOrderId(workOrderId);
                QualityWorkOrderDO order = link == null ? null : workOrderMapper.selectById(link.getWorkOrderId());
                if (order == null || !"ETL_INLINE".equals(order.getSourceType())
                        || !QualityWorkOrderDO.STATUS_PROCESSED.equals(order.getOrderStatus())) {
                    return false;
                }
                List<QualityWorkOrderSubmissionDO> submissions = submissionMapper.selectListByWorkOrderId(workOrderId);
                for (QualityWorkOrderSubmissionDO submission : submissions) {
                    if (QualityWorkOrderSubmissionDO.RECHECK_QUEUED.equals(submission.getRecheckStatus())
                            || QualityWorkOrderSubmissionDO.RECHECK_RUNNING.equals(submission.getRecheckStatus())) {
                        return false;
                    }
                    List<QualityWorkOrderRecheckDO> attempts = recheckMapper.selectBySubmission(submission.getId());
                    if (attempts.stream().anyMatch(attempt -> QualityWorkOrderSubmissionDO.RECHECK_RUNNING
                            .equals(attempt.getStatus()))) {
                        return false;
                    }
                }
            }
            return true;
        } catch (RuntimeException deniedOrUnknown) {
            return false;
        }
    }

    /**
     * 核验 ETL 治理调用的执行身份与相应任务权限。
     */
    private void authorize(Long userId, String... permissions) {
        if (userId == null) {
            throw exception(QUALITY_RUN_FORBIDDEN);
        }
        userApi.validateUser(userId);
        for (String permission : permissions) {
            if (!Boolean.TRUE.equals(permissionApi.hasAnyPermissions(userId, permission).getCheckedData())) {
                throw exception(QUALITY_RUN_FORBIDDEN);
            }
        }
    }

    /**
     * 转换数据行严重级别。
     */
    private static String toRowSeverity(String severity) {
        return switch (severity == null ? "" : severity) {
            case "CRITICAL", "HIGH" -> "ERROR";
            case "MID" -> "WARN";
            default -> "INFO";
        };
    }

    /**
     * 将输入按稳定字段顺序序列化为 JSON，供内容比对或摘要使用。
     */
    private static String canonicalJson(Map<String, ?> value) {
        Map<String, Object> parsed = JsonUtils.parseObject(JsonUtils.toJsonString(value), new TypeReference<>() {
        });
        return JsonUtils.toJsonString(stableValue(parsed));
    }

    /**
     * 将参数递归整理为可稳定序列化和比对的值。
     */
    private static Object stableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), stableValue(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(GovernanceEtlService::stableValue).toList();
        }
        return value;
    }
}
