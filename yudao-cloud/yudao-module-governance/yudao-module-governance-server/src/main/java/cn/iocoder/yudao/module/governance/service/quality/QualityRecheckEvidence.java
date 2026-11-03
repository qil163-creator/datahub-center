package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import tools.jackson.core.type.TypeReference;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

/** 整改验收的领域可比性策略，不重写质量引擎或 SQL 解析器。缺证据不能通过。 */
final class QualityRecheckEvidence {

    static final int DECISION_SCHEMA_VERSION = 3;

    /**
     * 限制质量复检证据工具类的直接实例化。
     */
    private QualityRecheckEvidence() {
    }

    record Comparison(boolean comparable, String reason, Map<String, Object> evidence, String resultType) {

    }

    record Decision(int schemaVersion, Long workOrderId, Long submissionId, Long instanceId,
                    String status, boolean comparable, List<Map<String, Object>> checks, DecisionBinding binding) {

        /** 历史信封仍可读取，但缺少轮次/范围绑定不能用于新节点关闭工单。 */
        Decision(int schemaVersion, Long workOrderId, Long submissionId, Long instanceId,
                String status, boolean comparable, List<Map<String, Object>> checks) {
            this(schemaVersion, workOrderId, submissionId, instanceId, status, comparable, checks, null);
        }
    }

    record DecisionBinding(Long attemptId, Integer attemptNo, String requestKeyHash, Long requestedBy,
                           Long taskId, Integer submissionRevision, String submissionHash, List<Long> issueIds) {

    }

    /**
     * 汇总冻结规则、读取范围和本次复检证据，判定复检结果是否可用于工单决策。
     */
    static Decision decision(QualityWorkOrderDO order, QualityWorkOrderSubmissionDO submission,
            QualityWorkOrderRecheckDO attempt, String status, boolean comparable,
            List<Map<String, Object>> checks) {
        return new Decision(DECISION_SCHEMA_VERSION, order.getId(), submission.getId(), attempt.getInstanceId(),
                status, comparable, checks, new DecisionBinding(attempt.getId(), attempt.getAttemptNo(),
                hash(attempt.getRequestKey()), attempt.getRequestedBy(), order.getTaskId(), submission.getRevision(),
                submissionHash(submission), checkedIssueIds(checks)));
    }

    /**
     * 检查复检证据是否满足指定提交记录和目标范围的核验条件。
     */
    static boolean qualifies(QualityWorkOrderRecheckDO attempt, QualityWorkOrderDO order,
            QualityWorkOrderSubmissionDO submission) {
        return qualifies(attempt, order, submission, ConfiguredSourceGenerations.empty());
    }

    /**
     * 检查复检证据是否满足指定提交记录和目标范围的核验条件。
     */
    static boolean qualifies(QualityWorkOrderRecheckDO attempt, QualityWorkOrderDO order,
            QualityWorkOrderSubmissionDO submission, ConfiguredSourceGenerations generations) {
        return qualifies(attempt, order, submission, generations, submittedIssueIds(submission));
    }

    /** 确认时重新读取当前工单的完整问题集合，不能仅信任信封中自报的范围。 */
    static boolean qualifies(QualityWorkOrderRecheckDO attempt, QualityWorkOrderDO order,
            QualityWorkOrderSubmissionDO submission, ConfiguredSourceGenerations generations,
            List<Long> currentIssueIds) {
        if (attempt == null || order == null || submission == null
                || !QualityWorkOrderSubmissionDO.RECHECK_PASS.equals(attempt.getStatus())
                || !Integer.valueOf(1).equals(attempt.getComparableFlag())) {
            return false;
        }
        Decision decision = JsonUtils.parseObjectQuietly(attempt.getEvidenceJson(), Decision.class);
        return decision != null && decision.schemaVersion() == DECISION_SCHEMA_VERSION && decision.comparable()
                && QualityWorkOrderSubmissionDO.RECHECK_PASS.equals(decision.status())
                && Objects.equals(attempt.getWorkOrderId(), order.getId())
                && Objects.equals(order.getCurrentSubmissionId(), submission.getId())
                && Objects.equals(submission.getWorkOrderId(), order.getId())
                && Objects.equals(attempt.getSubmissionId(), submission.getId())
                && Objects.equals(attempt.getInstanceId(), submission.getInstanceId())
                && Objects.equals(decision.workOrderId(), order.getId())
                && Objects.equals(decision.submissionId(), submission.getId())
                && Objects.equals(decision.instanceId(), submission.getInstanceId())
                && validBinding(decision, attempt, order, submission, currentIssueIds)
                && decision.checks().stream().allMatch(check -> validCheck(check, attempt, generations));
    }

    /**
     * 判断质量复检证据的绑定是否有效。
     */
    private static boolean validBinding(Decision decision, QualityWorkOrderRecheckDO attempt,
            QualityWorkOrderDO order, QualityWorkOrderSubmissionDO submission,
            List<Long> currentIssueIds) {
        var binding = decision.binding();
        List<Long> expected = exactIssueIds(currentIssueIds);
        List<Long> actual = checkedIssueIds(decision.checks());
        return binding != null && positive(attempt.getId()) && positive(attempt.getRequestedBy())
                && positive(order.getTaskId()) && positive(attempt.getInstanceId())
                && attempt.getAttemptNo() != null && attempt.getAttemptNo() > 0
                && submission.getRevision() != null && submission.getRevision() > 0
                && StrUtil.isNotBlank(attempt.getRequestKey()) && sha256(submission.getPayloadHash())
                && submission.getSubmittedAt() != null
                && Objects.equals(binding.attemptId(), attempt.getId())
                && Objects.equals(binding.attemptNo(), attempt.getAttemptNo())
                && Objects.equals(binding.requestKeyHash(), hash(attempt.getRequestKey()))
                && Objects.equals(binding.requestedBy(), attempt.getRequestedBy())
                && Objects.equals(binding.taskId(), order.getTaskId())
                && Objects.equals(binding.submissionRevision(), submission.getRevision())
                && Objects.equals(binding.submissionHash(), submissionHash(submission))
                && expected != null && expected.equals(actual) && expected.equals(binding.issueIds());
    }

    /** 当前问题身份也须复验；相同 issueId 不能替换成另一规则、对象、基线或机构范围。 */
    static boolean qualifiesCurrentIssues(QualityWorkOrderRecheckDO attempt, QualityWorkOrderDO order,
            QualityWorkOrderSubmissionDO submission, ConfiguredSourceGenerations generations,
            List<QualityIssueDataDO> issues) {
        if (issues == null || issues.stream().anyMatch(Objects::isNull)
                || !qualifies(attempt, order, submission, generations,
                issues.stream().map(QualityIssueDataDO::getId).toList())) {
            return false;
        }
        var decision = JsonUtils.parseObjectQuietly(attempt.getEvidenceJson(), Decision.class);
        Map<Long, QualityIssueDataDO> current = new HashMap<>();
        issues.forEach(issue -> current.put(issue.getId(), issue));
        return decision.checks().stream().allMatch(check -> Objects.equals(check.get("issueBindingHash"),
                issueBindingHash(current.get(id(check.get("issueId"))))));
    }

    /** 确认只接受原持久决定依据的同一组实际检查，不用新的 PASS 静默替换旧证据。 */
    static boolean matchesCurrentChecks(QualityWorkOrderRecheckDO attempt, String status, boolean comparable,
            List<Map<String, Object>> checks) {
        if (attempt == null || !comparable || !QualityWorkOrderSubmissionDO.RECHECK_PASS.equals(status)) {
            return false;
        }
        var decision = JsonUtils.parseObjectQuietly(attempt.getEvidenceJson(), Decision.class);
        if (decision == null || decision.schemaVersion() != DECISION_SCHEMA_VERSION || !decision.comparable()
                || !QualityWorkOrderSubmissionDO.RECHECK_PASS.equals(decision.status())) {
            return false;
        }
        // 与持久化/真实 Long 序列化契约对齐；不转浮点，也不依赖数据库返回的关联顺序或 JSON 键顺序。
        List<Map<String, Object>> normalized = JsonUtils.parseObjectQuietly(JsonUtils.toJsonString(checks),
                new TypeReference<List<Map<String, Object>>>() {
                });
        Map<Long, Map<String, Object>> current = indexedChecks(normalized);
        return current != null && current.equals(indexedChecks(decision.checks()));
    }

    /**
     * 核验复检问题标识集合后按问题编号建立检查结果索引。
     */
    private static Map<Long, Map<String, Object>> indexedChecks(List<Map<String, Object>> checks) {
        if (checkedIssueIds(checks) == null) {
            return null;
        }
        Map<Long, Map<String, Object>> indexed = new HashMap<>();
        checks.forEach(check -> indexed.put(id(check.get("issueId")), check));
        return indexed;
    }

    /**
     * 对问题关联的任务、规则、对象、范围及行键生成稳定摘要。
     */
    private static String issueBindingHash(QualityIssueDataDO issue) {
        if (issue == null) {
            return null;
        }
        return hash(JsonUtils.toJsonString(Arrays.asList(issue.getId(), issue.getTaskId(), issue.getRuleId(),
                issue.getObjectId(), issue.getInstanceId(), issue.getAuditLogId(), issue.getTableName(),
                issue.getCheckField(),
                issue.getStatScope(), issue.getOrgId(), issue.getRegionCode(), issue.getPkValue(),
                issue.getIssueCount())));
    }

    /**
     * 对工单提交内容、证据与问题范围生成稳定摘要。
     */
    private static String submissionHash(QualityWorkOrderSubmissionDO submission) {
        return hash(JsonUtils.toJsonString(Arrays.asList(submission.getWorkOrderId(), submission.getRevision(),
                submission.getSubmissionType(), submission.getPayloadHash(), submission.getContent(),
                submission.getEvidenceRef(), submission.getRepairOrderId(), submission.getIssueIds(),
                submission.getSubmittedBy(),
                submission.getSubmittedAt() == null ? null : submission.getSubmittedAt().toString())));
    }

    /**
     * 判断质量复检证据的检查是否有效。
     */
    private static boolean validCheck(Map<String, Object> check, QualityWorkOrderRecheckDO attempt,
            ConfiguredSourceGenerations generations) {
        return check != null && "COMPARABLE".equals(check.get("reasonCode"))
                && QualityAuditLogDO.RESULT_PASS.equals(check.get("resultType"))
                && Objects.equals(id(check.get("instanceId")), attempt.getInstanceId())
                && positive(id(check.get("baselineInstanceId")))
                && !Objects.equals(id(check.get("baselineInstanceId")), attempt.getInstanceId())
                && positive(id(check.get("objectId"))) && positive(id(check.get("ruleId")))
                && positive(id(check.get("auditId"))) && positive(id(check.get("baselineAuditId")))
                && (QualityIssueDataDO.SCOPE_ALL.equals(check.get("statScope"))
                || QualityIssueDataDO.SCOPE_ORG.equals(check.get("statScope")) && sha256(check.get("scopeKeyHash")))
                && sha256(check.get("relationFingerprint")) && sha256(check.get("readHash"))
                && sha256(check.get("baselineReadHash")) && sha256(check.get("definitionHash"))
                && sha256(check.get("baselineDefinitionHash")) && sha256(check.get("issueBindingHash"))
                && currentManagedSource(check, generations);
    }

    /**
     * 解析提交记录中的问题编号，要求有效、非空且无重复。
     */
    private static List<Long> submittedIssueIds(QualityWorkOrderSubmissionDO submission) {
        if (submission == null) {
            return null;
        }
        List<?> values = JsonUtils.parseObjectQuietly(submission.getIssueIds(), List.class);
        return values == null ? null : exactIssueIds(values.stream().map(QualityRecheckEvidence::id).toList());
    }

    /**
     * 提取复检结果中的问题编号并核验编号集合。
     */
    private static List<Long> checkedIssueIds(List<Map<String, Object>> checks) {
        return checks == null ? null : exactIssueIds(checks.stream()
                .map(check -> check == null ? null : id(check.get("issueId"))).toList());
    }

    /**
     * 核验问题编号非空、为正数且无重复，并返回排序后的编号。
     */
    private static List<Long> exactIssueIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(value -> !positive(value))) {
            return null;
        }
        List<Long> sorted = ids.stream().distinct().sorted().toList();
        return sorted.size() == ids.size() ? sorted : null;
    }

    /** JSON 的大 Long 可为十进制字符串；不允许浮点、指数或有损数值转换。 */
    private static Long id(Object value) {
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof Integer number) {
            return number.longValue();
        }
        if (value instanceof String text && text.matches("[1-9][0-9]{0,18}")) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 判断输入是否为有效的正数。
     */
    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    /** 本节点部署配置校验，不是全节点共识；轮换须先停止全节点调度、运行、回调和确认，
     * 源端隔离或轮换后全节点更新配置并验收才能开放，漏更新节点不得放流量。 */
    private static boolean currentManagedSource(Map<String, Object> check, ConfiguredSourceGenerations generations) {
        // 缺产品的历史 decision 无法区分旧 MariaDB proof，不能降级为非 managed。
        if (!(check.get("productType") instanceof String product) || product.isBlank()) {
            return false;
        }
        if (!"MARIADB".equals(product)) {
            return !check.containsKey("managedSourceTarget") && !check.containsKey("managedSourceGeneration");
        }
        return sha256(check.get("managedSourceTarget")) && sha256(check.get("managedSourceGeneration"))
                && generations != null && generations.isCurrent((String) check.get("managedSourceTarget"),
                (String) check.get("managedSourceGeneration"));
    }

    /**
     * 判断输入是否为六十四位小写十六进制摘要。
     */
    private static boolean sha256(Object value) {
        return value instanceof String digest && digest.matches("[0-9a-f]{64}");
    }

    /**
     * 比对问题基线与当前复检的任务、规则、对象、审计及读取范围证据。
     */
    static Comparison compare(QualityWorkOrderDO order, QualityIssueDataDO issue,
            QualityTaskInstanceDO baseline, QualityAuditLogDO baselineAudit,
            QualityTaskInstanceDO current, QualityAuditLogDO currentAudit,
            LocalDateTime submittedAt) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("issueId", issue == null ? null : issue.getId());
        facts.put("issueBindingHash", issueBindingHash(issue));
        facts.put("objectId", issue == null ? null : issue.getObjectId());
        facts.put("ruleId", issue == null ? null : issue.getRuleId());
        facts.put("baselineInstanceId", baseline == null ? null : baseline.getId());
        facts.put("baselineAuditId", baselineAudit == null ? null : baselineAudit.getId());
        facts.put("instanceId", current.getId());
        facts.put("auditId", currentAudit == null ? null : currentAudit.getId());
        if (issue == null || baseline == null || baselineAudit == null || currentAudit == null
                || Objects.equals(baseline.getId(), current.getId())
                || !QualityTaskInstanceDO.STATUS_SUCCESS.equals(baseline.getInstanceStatus())
                || !Objects.equals(issue.getInstanceId(), baseline.getId())
                || !Objects.equals(issue.getAuditLogId(), baselineAudit.getId())
                || !Objects.equals(baselineAudit.getInstanceId(), baseline.getId())
                || !Objects.equals(currentAudit.getInstanceId(), current.getId())
                || order.getTaskId() == null || !Objects.equals(order.getTaskId(), issue.getTaskId())
                || !Objects.equals(issue.getTaskId(), baseline.getTaskId())
                || !Objects.equals(issue.getTaskId(), current.getTaskId())
                || !Objects.equals(issue.getTaskId(), baselineAudit.getTaskId())
                || !Objects.equals(issue.getTaskId(), currentAudit.getTaskId())) {
            return reject(facts, "BASELINE_MISSING", "原问题与检测实例/审计记录的关联证据不完整");
        }
        facts.put("baselineDefinitionHash", hash(baseline.getDefinitionSnapshot()));
        facts.put("definitionHash", hash(current.getDefinitionSnapshot()));
        try {
            var before = parse(baseline.getDefinitionSnapshot(), QualityTaskExecutor.GateDefinition.class);
            var after = parse(current.getDefinitionSnapshot(), QualityTaskExecutor.GateDefinition.class);
            if (before == null || after == null || before.getTask() == null || after.getTask() == null
                    || !Objects.equals(before.getTask().getId(), issue.getTaskId())
                    || !Objects.equals(after.getTask().getId(), issue.getTaskId())) {
                return reject(facts, "DEFINITION_MISSING", "缺少原检测或复检的冻结定义");
            }
            var oldRule = unique(before.getRules(), QualityRuleDO::getId, issue.getRuleId());
            var newRule = unique(after.getRules(), QualityRuleDO::getId, issue.getRuleId());
            var oldObject = unique(before.getObjects(), QualityCheckObjectDO::getId, issue.getObjectId());
            var newObject = unique(after.getObjects(), QualityCheckObjectDO::getId, issue.getObjectId());
            if (oldRule == null || newRule == null || oldObject == null || newObject == null) {
                return reject(facts, "DEFINITION_MISSING", "冻结定义中缺少唯一的原规则或检查对象");
            }
            if (!Objects.equals(ruleMeaning(oldRule), ruleMeaning(newRule))
                    || !Objects.equals(parameters(oldRule), parameters(newRule))
                    || !Integer.valueOf(0).equals(oldRule.getStatus()) || !Integer.valueOf(0)
                    .equals(newRule.getStatus())) {
                return reject(facts, "RULE_CHANGED", "规则版本、阈值、字段或过滤条件已变化，不能替代原检测口径");
            }
            if (oldObject.getDatasourceConnId() == null || StrUtil.isBlank(oldObject.getTableName())
                    || !Objects.equals(objectMeaning(oldObject), objectMeaning(newObject))) {
                return reject(facts, "OBJECT_CHANGED", "检查对象的数据源、表或模式绑定已变化");
            }
            if (!Objects.equals(before.getTask().getOrgField(), after.getTask().getOrgField())
                    || !Objects.equals(before.getTask().getGroupField(), after.getTask().getGroupField())
                    || (!QualityIssueDataDO.SCOPE_ALL.equals(issue.getStatScope())
                    && !QualityIssueDataDO.SCOPE_ORG.equals(issue.getStatScope()))
                    || (QualityIssueDataDO.SCOPE_ALL.equals(issue.getStatScope())
                    && (issue.getOrgId() != null || StrUtil.isNotBlank(issue.getRegionCode())))) {
                return reject(facts, "SCOPE_UNPROVEN", "缺少与原机构/地区/分组范围一致的全量覆盖证据");
            }
            if (!auditMatches(issue, baselineAudit) || !auditMatches(issue, currentAudit)
                    || !Objects.equals(baselineAudit.getGroupName(), currentAudit.getGroupName())
                    || (QualityRuleDO.CHECK_LEVEL_FIELD.equals(oldRule.getCheckLevel()) && StrUtil.isBlank(
                    issue.getCheckField()))
                    || StrUtil.isBlank(baselineAudit.getResultSql())
                    || !Objects.equals(baselineAudit.getResultSql(), currentAudit.getResultSql())) {
                return reject(facts, "EXECUTED_SCOPE_CHANGED", "实际执行的字段、表或统计 SQL 不一致");
            }
            QualityReadEvidence oldRead = parse(baselineAudit.getReadEvidence(), QualityReadEvidence.class);
            QualityReadEvidence newRead = parse(currentAudit.getReadEvidence(), QualityReadEvidence.class);
            int readVersion = parameters(newRule).containsKey("pkColumns")
                    ? QualityReadEvidence.COMPOSITE_SCHEMA_VERSION : QualityReadEvidence.RELATION_SCHEMA_VERSION;
            if (!validRead(oldRead, baselineAudit, baseline, oldObject, readVersion)
                    || !validRead(newRead, currentAudit, current, newObject, readVersion)) {
                return reject(facts, "READ_EVIDENCE_MISSING", "缺少可核验的读取绑定、时点或全量覆盖凭证");
            }
            facts.put("baselineReadHash", hash(baselineAudit.getReadEvidence()));
            facts.put("readHash", hash(currentAudit.getReadEvidence()));
            facts.put("readMode", newRead.getReadMode());
            facts.put("readStartedAt", newRead.getStartedAt());
            facts.put("readFinishedAt", newRead.getFinishedAt());
            facts.put("productType", newRead.getProductType());
            if (newRead.getManagedSourceTarget() != null || newRead.getManagedSourceGeneration() != null) {
                facts.put("managedSourceTarget", newRead.getManagedSourceTarget());
                facts.put("managedSourceGeneration", newRead.getManagedSourceGeneration());
            }
            if (!Objects.equals(binding(oldRead), binding(newRead))) {
                return reject(facts, "DATASOURCE_REVISION_CHANGED", "数据源配置修订或配置模式已变化，需重新确认基线");
            }
            if (!Objects.equals(oldRead.getRelationFingerprint(), newRead.getRelationFingerprint())) {
                return reject(facts, "RELATION_CHANGED", "实际读取的数据库关系已变化，不能复用原检测基线");
            }
            // 原生标识与键支持能力不能证明每条规则在具体数据库产品上的语义。
            if (("MARIADB".equals(newRead.getProductType()) || "CLICKHOUSE".equals(
                    newRead.getProductType()) || "OPENGAUSS".equals(newRead.getProductType()) || "STARROCKS".equals(
                    newRead.getProductType()) || "TIDB".equals(newRead.getProductType()) || "OCEANBASE".equals(
                    newRead.getProductType()) || "TDENGINE".equals(newRead.getProductType()) || "GREENPLUM".equals(
                    newRead.getProductType()) || "HIGHGO".equals(newRead.getProductType()) || "DM".equals(
                    newRead.getProductType()) || "KINGBASE".equals(newRead.getProductType()) || "OSCAR".equals(
                    newRead.getProductType()) || "UXDB".equals(newRead.getProductType()) || "ORACLE".equals(
                    newRead.getProductType()))
                    && !"NOT_NULL_VALIDATION".equals(newRule.getStrategyKey())) {
                return reject(facts, "RULE_DIALECT_UNVERIFIED", "该数据库上的规则语义尚未完成实库验证，不能自动验收");
            }
            facts.put("relationFingerprint", newRead.getRelationFingerprint());
            if (submittedAt == null || storedTime(newRead.startedTime()).isBefore(storedTime(submittedAt))
                    || baseline.getEndTime() == null || baseline.getEndTime().isAfter(submittedAt)) {
                return reject(facts, "READ_TIME_INVALID", "读取时点与本次整改提交不匹配");
            }
            if (!"SOURCE_DIRECT".equals(newRead.getReadMode()) || !"SOURCE_DIRECT".equals(oldRead.getReadMode())
                    || !"THIRD_PARTY".equals(newObject.getDatasourceType()) || !Integer.valueOf(1)
                    .equals(newRead.getUsageType())) {
                return reject(facts, "DATA_ARRIVAL_UNPROVEN", "汇聚副本缺少实际同步批次/水位证据，等待更新或确认");
            }
            QualityKeyDefinition keys = QualityKeyDefinition.from(parameters(newRule));
            boolean requiresKeys = keys.configured() || StrUtil.isNotBlank(issue.getPkValue());
            if ("MARIADB".equals(newRead.getProductType()) && !requiresKeys) {
                return reject(facts, "ROW_COVERAGE_UNPROVEN", "MariaDB 自动验收仅限已验证的完整整数主键覆盖路径");
            }
            if (QualityIssueDataDO.SCOPE_ORG.equals(issue.getStatScope())) {
                return compareOrg(facts, issue, before.getTask(), oldRead, newRead,
                        baseline, current, baselineAudit, currentAudit, requiresKeys, keys);
            }
            if (requiresKeys) {
                if (!validKeyScope(oldRead, baselineAudit, keys) || !validKeyScope(newRead, currentAudit, keys)) {
                    return reject(facts, "ROW_COVERAGE_UNPROVEN", "缺少与实际计数同次读取的完整主键覆盖凭证");
                }
                var oldKeys = oldRead.getKeyScope();
                var newKeys = newRead.getKeyScope();
                if (!sameKeys(oldKeys, newKeys)) {
                    return reject(facts, "ROW_POPULATION_CHANGED", "主键集合或类型变化，不能以相同总数推断原记录被覆盖");
                }
                facts.put("keyPopulationHash", newKeys.getKeyHash());
                facts.put("keyPopulationCount", newKeys.getKeyCount());
            }
            if (!usableCounts(baselineAudit) || !usableCounts(currentAudit)
                    || baselineAudit.getFailCount() <= 0 || issue.getIssueCount() == null
                    || !Objects.equals(issue.getIssueCount(), baselineAudit.getFailCount())
                    || currentAudit.getTotalCount() < baselineAudit.getTotalCount()) {
                return reject(facts, "COVERAGE_UNPROVEN", "无样本、统计异常或检测范围缩减，不能以数量推定原问题已覆盖");
            }
            facts.put("baselineTotal", baselineAudit.getTotalCount());
            facts.put("total", currentAudit.getTotalCount());
            facts.put("fail", currentAudit.getFailCount());
            facts.put("reasonCode", "COMPARABLE");
            facts.put("statScope", QualityIssueDataDO.SCOPE_ALL);
            facts.put("resultType", currentAudit.getResultType());
            return new Comparison(true, null, facts, currentAudit.getResultType());
        } catch (IllegalArgumentException | java.time.DateTimeException ex) {
            return reject(facts, "EVIDENCE_INVALID", "冻结定义或读取凭证无法解析，需重新检测");
        }
    }

    /**
     * 判断质量复检证据的键范围是否有效。
     */
    private static boolean validKeyScope(QualityReadEvidence read, QualityAuditLogDO audit, QualityKeyDefinition keys) {
        var scope = read.getKeyScope();
        return scope != null && AuditKeySnapshot.supportsEncoding(read.getProductType(), scope.getAlgorithm(),
                scope.getJdbcType())
                && scope.getGroupKeyHash() == null
                && validKeyScope(scope, read.getSqlHash(), audit.getTotalCount(), audit.getFailCount(), keys);
    }

    /**
     * 判断质量复检证据的键范围是否有效。
     */
    private static boolean validKeyScope(QualityReadEvidence.KeyScope scope, String sqlHash,
            Long total, Long fail, QualityKeyDefinition keys) {
        return keys.matches(scope)
                && scope != null && Integer.valueOf(1).equals(scope.getSchemaVersion())
                && Boolean.TRUE.equals(scope.getComplete()) && StrUtil.isBlank(scope.getReasonCode())
                && AuditKeySnapshot.supportsEncoding(scope.getAlgorithm(), scope.getJdbcType())
                && scope.getKeyHash() != null && scope.getKeyHash().matches("[0-9a-f]{64}")
                && Objects.equals(scope.getSqlHash(), sqlHash)
                && scope.getKeyCount() != null && scope.getKeyCount() > 0
                && scope.getKeyCount() <= AuditKeySnapshot.MAX_KEYS
                && Objects.equals(scope.getKeyCount(), scope.getTotal())
                && Objects.equals(scope.getTotal(), total) && Objects.equals(scope.getFail(), fail);
    }

    /**
     * 比对复检与提交证据中的规范化行键集合。
     */
    private static boolean sameKeys(QualityReadEvidence.KeyScope before, QualityReadEvidence.KeyScope after) {
        return Objects.equals(before.getJdbcType(), after.getJdbcType())
                && Objects.equals(before.getAlgorithm(), after.getAlgorithm())
                && Objects.equals(before.getColumn(), after.getColumn())
                && Objects.equals(before.getColumns(), after.getColumns())
                && Objects.equals(before.getKeyCount(), after.getKeyCount())
                && Objects.equals(before.getKeyHash(), after.getKeyHash());
    }

    /**
     * 比对组织。
     */
    private static Comparison compareOrg(Map<String, Object> facts, QualityIssueDataDO issue, QualityTaskDO task,
            QualityReadEvidence before, QualityReadEvidence after,
            QualityTaskInstanceDO baseline, QualityTaskInstanceDO current,
            QualityAuditLogDO baselineAudit, QualityAuditLogDO currentAudit,
            boolean requiresKeys, QualityKeyDefinition keys) {
        String field = StrUtil.trimToEmpty(task.getOrgField());
        String key = issue.getRegionCode();
        boolean projected = "MYSQL".equals(after.getProductType()) || "MARIADB".equals(
                after.getProductType()) || "DORIS".equals(after.getProductType())
                || "CLICKHOUSE".equals(after.getProductType()) || "OPENGAUSS".equals(
                after.getProductType()) || "STARROCKS".equals(after.getProductType()) || "TIDB".equals(
                after.getProductType()) || "OCEANBASE".equals(after.getProductType()) || "TDENGINE".equals(
                after.getProductType()) || "GREENPLUM".equals(after.getProductType()) || "HIGHGO".equals(
                after.getProductType()) || "DM".equals(after.getProductType()) || "KINGBASE".equals(
                after.getProductType()) || "OSCAR".equals(after.getProductType()) || "UXDB".equals(
                after.getProductType()) || "ORACLE".equals(after.getProductType());
        if ((!"POSTGRESQL".equals(after.getProductType()) && !projected)
                || (projected && !requiresKeys) || !field.matches("[A-Za-z_][A-Za-z0-9_]*")
                || StrUtil.isBlank(key) || key.length() > 64
                || (issue.getOrgId() != null && (!key.matches("[0-9]+")
                || !new java.math.BigInteger(key).equals(java.math.BigInteger.valueOf(issue.getOrgId()))))) {
            return reject(facts, "SCOPE_UNPROVEN", "缺少可验证的机构字段/原始分组身份，或目标数据库尚未验证");
        }
        var oldScope = before.getOrgScope();
        var newScope = after.getOrgScope();
        if (!validOrgScope(oldScope, before, baseline, field) || !validOrgScope(newScope, after, current, field)
                || !Objects.equals(oldScope.getSqlHash(), newScope.getSqlHash())
                || !Objects.equals(oldScope.getGroupEncoding(), newScope.getGroupEncoding())
                || !Objects.equals(oldScope.getGroupJdbcType(), newScope.getGroupJdbcType())
                || !usableCounts(baselineAudit) || !usableCounts(currentAudit)) {
            return reject(facts, "ORG_EVIDENCE_INCOMPLETE", "机构读取证据缺失、截断、执行失败或统计 SQL 已变化");
        }
        String keyHash = hash(key);
        var oldCount = oldScope.getGroups().stream().filter(group -> keyHash.equals(group.keyHash())).findFirst()
                .orElse(null);
        var newCount = newScope.getGroups().stream().filter(group -> keyHash.equals(group.keyHash())).findFirst()
                .orElse(null);
        if (oldCount == null || newCount == null || oldCount.fail() <= 0
                || !Objects.equals(issue.getIssueCount(), oldCount.fail()) || newCount.total() < oldCount.total()) {
            return reject(facts, "ORG_COVERAGE_UNPROVEN", "目标机构缺失、无样本、问题数量不匹配或范围缩减");
        }
        if (requiresKeys) {
            var oldKeys = oldCount.keyScope();
            var newKeys = newCount.keyScope();
            if (StrUtil.isNotBlank(oldScope.getKeyCaptureReason()) || StrUtil.isNotBlank(newScope.getKeyCaptureReason())
                    || !validKeyScope(oldKeys, oldScope.getSqlHash(), oldCount.total(), oldCount.fail(), keys)
                    || !validKeyScope(newKeys, newScope.getSqlHash(), newCount.total(), newCount.fail(), keys)
                    || !AuditKeySnapshot.supportsEncoding(before.getProductType(), oldKeys.getAlgorithm(),
                    oldKeys.getJdbcType())
                    || !AuditKeySnapshot.supportsEncoding(after.getProductType(), newKeys.getAlgorithm(),
                    newKeys.getJdbcType())
                    || !Objects.equals(keyHash, oldKeys.getGroupKeyHash()) || !Objects.equals(keyHash,
                    newKeys.getGroupKeyHash())) {
                return reject(facts, "ROW_COVERAGE_UNPROVEN", "缺少与目标机构计数、身份绑定的完整主键覆盖凭证");
            }
            if (!sameKeys(oldKeys, newKeys)) {
                return reject(facts, "ROW_POPULATION_CHANGED",
                        "目标机构的主键集合或类型变化，不能借用全表或其他机构的结果");
            }
            facts.put("keyPopulationHash", newKeys.getKeyHash());
            facts.put("keyPopulationCount", newKeys.getKeyCount());
        }
        facts.put("statScope", QualityIssueDataDO.SCOPE_ORG);
        facts.put("scopeKeyHash", keyHash);
        facts.put("scopeSqlHash", newScope.getSqlHash());
        facts.put("scopeStartedAt", newScope.getStartedAt());
        facts.put("scopeFinishedAt", newScope.getFinishedAt());
        facts.put("baselineTotal", oldCount.total());
        facts.put("total", newCount.total());
        facts.put("fail", newCount.fail());
        facts.put("resultType", newCount.resultType());
        facts.put("reasonCode", "COMPARABLE");
        return new Comparison(true, null, facts, newCount.resultType());
    }

    /**
     * 判断质量复检证据的组织范围是否有效。
     */
    private static boolean validOrgScope(QualityReadEvidence.OrgScope scope, QualityReadEvidence read,
            QualityTaskInstanceDO instance, String field) {
        if (scope == null || !validOrgEncoding(scope, read.getProductType())
                || !Boolean.TRUE.equals(scope.getComplete()) || !Objects.equals(scope.getField(), field)
                || scope.getSqlHash() == null || !scope.getSqlHash().matches("[0-9a-f]{64}")
                || scope.getStartedAt() == null || scope.getFinishedAt() == null
                || StrUtil.isNotBlank(scope.getReasonCode())
                || scope.getGroups() == null || scope.getGroups().size() > QualityReadEvidence.MAX_ORG_GROUPS) {
            return false;
        }
        LocalDateTime start = LocalDateTime.parse(scope.getStartedAt());
        LocalDateTime end = LocalDateTime.parse(scope.getFinishedAt());
        if (start.isBefore(read.finishedTime()) || end.isBefore(start)
                || storedTime(end).isAfter(storedTime(instance.getEndTime()))) {
            return false;
        }
        Set<String> keys = new HashSet<>();
        return scope.getGroups().stream().allMatch(group -> group != null
                && group.keyHash() != null && group.keyHash().matches("[0-9a-f]{64}") && keys.add(group.keyHash())
                && usableCounts(group.resultType(), group.total(), group.fail()));
    }

    /**
     * 判断质量复检证据的组织编码是否有效。
     */
    private static boolean validOrgEncoding(QualityReadEvidence.OrgScope scope, String product) {
        if (AuditKeySnapshot.supportsTextGroupEncoding(product, scope.getGroupEncoding())) {
            return Integer.valueOf(2).equals(scope.getSchemaVersion())
                    && AuditKeySnapshot.supportsTextGroupEncoding(product, scope.getGroupEncoding(),
                    scope.getGroupJdbcType());
        }
        if ("POSTGRESQL".equals(product)) {
            return Integer.valueOf(1).equals(scope.getSchemaVersion())
                    && scope.getGroupEncoding() == null && scope.getGroupJdbcType() == null;
        }
        if ("MARIADB".equals(product) || "DORIS".equals(product) || "CLICKHOUSE".equals(product) || "OPENGAUSS".equals(
                product) || "STARROCKS".equals(product) || "TIDB".equals(product) || "OCEANBASE".equals(
                product) || "TDENGINE".equals(product) || "GREENPLUM".equals(product) || "HIGHGO".equals(
                product) || "DM".equals(product) || "KINGBASE".equals(product) || "OSCAR".equals(
                product) || "UXDB".equals(product) || "ORACLE".equals(product)) {
            String encoding = switch (product) {
                case "MARIADB" -> AuditKeySnapshot.MARIADB_INTEGER_GROUP_ENCODING;
                case "DORIS" -> AuditKeySnapshot.DORIS_INTEGER_GROUP_ENCODING;
                case "CLICKHOUSE" -> AuditKeySnapshot.CLICKHOUSE_INTEGER_GROUP_ENCODING;
                case "STARROCKS" -> AuditKeySnapshot.STARROCKS_INTEGER_GROUP_ENCODING;
                case "TIDB" -> AuditKeySnapshot.TIDB_INTEGER_GROUP_ENCODING;
                case "OCEANBASE" -> AuditKeySnapshot.OCEANBASE_INTEGER_GROUP_ENCODING;
                case "TDENGINE" -> AuditKeySnapshot.TDENGINE_INTEGER_GROUP_ENCODING;
                case "GREENPLUM" -> AuditKeySnapshot.GREENPLUM_INTEGER_GROUP_ENCODING;
                case "HIGHGO" -> AuditKeySnapshot.HIGHGO_INTEGER_GROUP_ENCODING;
                case "DM" -> AuditKeySnapshot.DM_INTEGER_GROUP_ENCODING;
                case "KINGBASE" -> AuditKeySnapshot.KINGBASE_INTEGER_GROUP_ENCODING;
                case "OSCAR" -> AuditKeySnapshot.OSCAR_INTEGER_GROUP_ENCODING;
                case "UXDB" -> AuditKeySnapshot.UXDB_INTEGER_GROUP_ENCODING;
                case "ORACLE" -> AuditKeySnapshot.ORACLE_INTEGER_GROUP_ENCODING;
                default -> AuditKeySnapshot.OPENGAUSS_INTEGER_GROUP_ENCODING;
            };
            return Integer.valueOf(2).equals(scope.getSchemaVersion())
                    && encoding.equals(scope.getGroupEncoding())
                    && AuditKeySnapshot.supportsEncoding(product, AuditKeySnapshot.ALGORITHM, scope.getGroupJdbcType());
        }
        return "MYSQL".equals(product) && Integer.valueOf(2).equals(scope.getSchemaVersion())
                && AuditKeySnapshot.MYSQL_INTEGER_GROUP_ENCODING.equals(scope.getGroupEncoding())
                && AuditKeySnapshot.supportsEncoding(product, AuditKeySnapshot.ALGORITHM, scope.getGroupJdbcType());
    }

    /**
     * 比对质量问题与审计记录的规则、对象、物理表和字段是否一致。
     */
    private static boolean auditMatches(QualityIssueDataDO issue, QualityAuditLogDO audit) {
        return Objects.equals(issue.getRuleId(), audit.getRuleId()) && Objects.equals(issue.getObjectId(),
                audit.getCheckObjectId())
                && Objects.equals(issue.getTableName(), audit.getTableName()) && Objects.equals(issue.getCheckField(),
                audit.getCheckField());
    }

    /**
     * 检查通过或失败结论与总数、失败数是否形成有效且一致的统计证据。
     */
    private static boolean usableCounts(QualityAuditLogDO audit) {
        return usableCounts(audit.getResultType(), audit.getTotalCount(), audit.getFailCount());
    }

    /**
     * 检查通过或失败结论与总数、失败数是否形成有效且一致的统计证据。
     */
    private static boolean usableCounts(String result, Long total, Long fail) {
        return (QualityAuditLogDO.RESULT_PASS.equals(result) || QualityAuditLogDO.RESULT_FAIL.equals(result))
                && total != null && total > 0 && fail != null && fail >= 0 && fail <= total
                && (!QualityAuditLogDO.RESULT_FAIL.equals(result) || fail > 0);
    }

    /**
     * 判断质量复检证据的读取是否有效。
     */
    private static boolean validRead(QualityReadEvidence read, QualityAuditLogDO audit, QualityTaskInstanceDO run,
            QualityCheckObjectDO object, int version) {
        return read != null && Integer.valueOf(version).equals(read.getSchemaVersion())
                && validManagedRead(read)
                && Objects.equals(read.getDatabaseId(), object.getDatasourceConnId())
                && read.getRelationFingerprint() != null && read.getRelationFingerprint().matches("[0-9a-f]{64}")
                && StrUtil.isNotBlank(read.getConfigurationRevision()) && StrUtil.isNotBlank(read.getProductType())
                && read.getDatabaseSchema() != null && "FULL_PREDICATE".equals(read.getCoverage())
                && Objects.equals(read.getSqlHash(), hash(audit.getResultSql()))
                && run.getStartTime() != null && run.getEndTime() != null && audit.getCheckTime() != null
                && read.startedTime() != null && read.finishedTime() != null
                && !storedTime(audit.getCheckTime()).isBefore(storedTime(run.getStartTime()))
                && !storedTime(audit.getCheckTime()).isAfter(storedTime(read.startedTime()))
                && !storedTime(read.startedTime()).isBefore(storedTime(run.getStartTime()))
                && !read.finishedTime().isBefore(read.startedTime())
                && !storedTime(read.finishedTime()).isAfter(storedTime(run.getEndTime()));
    }

    /** 平台审计/实例/提交列为 PostgreSQL timestamp(6)；只对齐存储舍入，不增加业务时钟窗口。 */
    private static LocalDateTime storedTime(LocalDateTime value) {
        return value.plusNanos(500).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    /**
     * 解析并核验质量复检证据对象的精确绑定。
     */
    private static List<Object> binding(QualityReadEvidence read) {
        return Arrays.asList(read.getDatabaseId(), read.getConfigurationRevision(), read.getProductType(),
                read.getDatabaseSchema(), read.getUsageType(), read.getManagedSourceTarget(),
                read.getManagedSourceGeneration());
    }

    /**
     * 判断质量复检证据的受管理读取是否有效。
     */
    private static boolean validManagedRead(QualityReadEvidence read) {
        if (!"MARIADB".equals(read.getProductType())) {
            return read.getManagedSourceTarget() == null && read.getManagedSourceGeneration() == null;
        }
        return sha256(read.getManagedSourceTarget()) && sha256(read.getManagedSourceGeneration())
                && read.getConfigurationRevision() != null
                && read.getConfigurationRevision().matches("source-v1:[0-9a-f]{64}");
    }

    /**
     * 提取质量复检证据冻结规则的语义内容。
     */
    private static List<Object> ruleMeaning(QualityRuleDO rule) {
        return Arrays.asList(rule.getRuleType(), rule.getCheckLevel(), rule.getStrategyKey(), rule.getRuleContent(),
                rule.getQualityDim(), rule.getStandardElementId(), rule.getConstraintHash());
    }

    /**
     * 读取质量复检证据冻结规则参数。
     */
    private static Map<?, ?> parameters(QualityRuleDO rule) {
        return StrUtil.isBlank(rule.getParamSchema()) ? Map.of() : parse(rule.getParamSchema(), Map.class);
    }

    /**
     * 提取质量复检证据冻结对象的语义内容。
     */
    private static List<Object> objectMeaning(QualityCheckObjectDO object) {
        return Arrays.asList(object.getDatasourceConnId(), object.getDatasourceType(), object.getDatasourceInstance(),
                object.getFormatName(), object.getTableName(), object.getTableId());
    }

    /**
     * 校验质量复检证据集合中不存在重复标识。
     */
    private static <T> T unique(List<T> values, Function<T, Long> identity, Long id) {
        if (values == null || id == null) {
            return null;
        }
        List<T> matches = values.stream().filter(Objects::nonNull)
                .filter(value -> Objects.equals(identity.apply(value), id)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    /**
     * 按指定类型解析质量证据 JSON，空白返回空值，非空无效内容抛出异常。
     */
    private static <T> T parse(String value, Class<T> type) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        T parsed = JsonUtils.parseObjectQuietly(value, type);
        if (parsed == null) {
            throw new IllegalArgumentException("Invalid quality evidence");
        }
        return parsed;
    }

    /**
     * 计算质量复检证据内容摘要。
     */
    private static String hash(String value) {
        return value == null ? null : DigestUtil.sha256Hex(value);
    }

    /**
     * 驳回质量复检证据并记录原因。
     */
    private static Comparison reject(Map<String, Object> facts, String code, String reason) {
        facts.put("reasonCode", code);
        return new Comparison(false, reason, facts, null);
    }
}
