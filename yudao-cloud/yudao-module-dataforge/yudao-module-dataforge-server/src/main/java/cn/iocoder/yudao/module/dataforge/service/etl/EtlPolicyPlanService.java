package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext;
import cn.iocoder.yudao.framework.quality.core.row.*;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.governance.api.etl.GovernanceEtlApi;
import cn.iocoder.yudao.module.governance.api.etl.dto.*;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.*;

/** 领域薄适配：授权获取治理快照，通用求值委托质量组件。 */
@Service
public class EtlPolicyPlanService {

    @Resource
    private GovernanceEtlApi governanceApi;

    @Resource
    private ExecutionAccessService executionAccess;

    @Resource
    private EtlQualityEvidenceService evidenceService;

    @Resource
    private CleanFunctionRegistry cleanFunctionRegistry;

    @Resource
    private EtlGovernanceCleanExecutor governanceCleanExecutor;

    @Data
    public static class Plan {

        private RowQualityPlanRespDTO quality;

        private CleanRulePlanRespDTO clean;
    }

    /**
     * 固定ETL策略计划执行快照。
     *
     * @param nodes 节点
     * @param actor 操作人编号
     * @param params 业务参数映射
     * @return 处理后的键值映射
     */
    public Map<String, Plan> freeze(List<EtlNodeDef> nodes, Long actor, Map<String, String> params) {
        Map<String, Plan> plans = new LinkedHashMap<>();
        EtlVariableContext variables = new EtlVariableContext(params);
        for (EtlNodeDef node : nodes) {
            if (!Set.of("quality-check", "data-clean").contains(node.getToolCode())) {
                continue;
            }
            var config = JSONUtil.parseObj(variables.resolveJson(StrUtil.blankToDefault(node.getNodeJson(), "{}")));
            Plan plan = new Plan();
            if ("data-clean".equals(node.getToolCode())) {
                if (!"GOVERNANCE".equalsIgnoreCase(config.getStr("executionMode", "LOCAL"))) {
                    continue;
                }
                var rules = config.getJSONArray("governanceRules");
                if (rules == null || rules.isEmpty() || rules.size() > 50) {
                    throw new IllegalArgumentException("治理清洗节点必须选择 1 至 50 条启用规则");
                }
                CleanRulePlanReqDTO req = new CleanRulePlanReqDTO();
                req.setExecutionUserId(actor);
                List<CleanRulePlanReqDTO.Rule> requestedRules = new ArrayList<>();
                for (Object raw : rules) {
                    var item = JSONUtil.parseObj(raw);
                    Long ruleId = item.getLong("ruleId");
                    var functionConfig = item.getJSONObject("config");
                    String field = item.getStr("field");
                    var fields = item.getJSONArray("fields");
                    // 旧节点将参数与字段一并存储；该配置仅作为
                    // 一致性断言发送，新节点仅包含 ruleId 与 field。
                    if (StrUtil.isBlank(field) && fields == null && functionConfig != null) {
                        var columns = functionConfig.getJSONArray("columns");
                        if (columns != null && !columns.isEmpty()) {
                            fields = columns;
                        }
                    }
                    if (ruleId == null) {
                        throw new IllegalArgumentException("治理清洗规则配置不完整");
                    }
                    CleanRulePlanReqDTO.Rule requestRule = new CleanRulePlanReqDTO.Rule();
                    requestRule.setRuleId(ruleId);
                    requestRule.setField(field);
                    if (fields != null) {
                        requestRule.setFields(fields.toList(String.class));
                    }
                    if (functionConfig != null) {
                        requestRule.setLegacyConfig(JsonUtils.parseObject(functionConfig.toString(),
                                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                                }));
                    }
                    requestedRules.add(requestRule);
                }
                req.setRules(requestedRules);
                plan.setClean(governanceApi.snapshotCleanRulePlan(req).getCheckedData());
                validateCleanPlan(plan.getClean());
            } else {
                RowQualityPlanReqDTO req = new RowQualityPlanReqDTO();
                req.setExecutionUserId(actor);
                req.setRuleSetId(config.getLong("ruleSetId"));
                Map<String, String> bindings = new LinkedHashMap<>();
                var fields = config.getJSONArray("fieldBindings");
                if (fields != null) {
                    for (Object item : fields) {
                        var binding = JSONUtil.parseObj(item);
                        Long rule = binding.getLong("ruleId");
                        String field = binding.getStr("field");
                        if (rule == null || StrUtil.isBlank(field) || bindings.put(String.valueOf(rule),
                                field) != null) {
                            throw new IllegalArgumentException("质量规则字段绑定为空或重复");
                        }
                    }
                }
                req.setFieldBindings(bindings);
                plan.setQuality(governanceApi.getRowQualityPlan(req).getCheckedData());
                compile(plan.getQuality());
            }
            plans.put(node.getNo(), plan);
        }
        return plans;
    }

    /**
     * 解码ETL策略计划。
     *
     * @param value 待处理的值
     * @return 处理后的键值映射
     */
    public Map<String, Plan> decode(Object value) {
        if (value == null) {
            return Map.of();
        }
        return JsonUtils.parseObject(JsonUtils.toJsonString(value),
                new tools.jackson.core.type.TypeReference<Map<String, Plan>>() {
                });
    }

    /**
     * 校验ETL策略计划所需的访问权限。
     *
     * @param plans 计划
     * @param actor 操作人编号
     */
    public void authorize(Map<String, Plan> plans, Long actor) {
        if (plans.values().stream().anyMatch(p -> p.getQuality() != null)) {
            executionAccess.requireUserPermission(actor, "governance:quality-rule:query");
            executionAccess.requireUserPermission(actor, "governance:quality-rule-set:query");
        }
        for (Plan plan : plans.values()) {
            if (plan.getClean() != null) {
                CleanRulePlanReqDTO req = new CleanRulePlanReqDTO();
                req.setExecutionUserId(actor);
                req.setRules(plan.getClean().getRules().stream().map(frozen -> {
                    CleanRulePlanReqDTO.Rule rule = new CleanRulePlanReqDTO.Rule();
                    rule.setRuleId(frozen.getRuleId());
                    var columns = frozen.getConfig() == null ? null : frozen.getConfig().get("columns");
                    if (!(columns instanceof List<?> values) || values.isEmpty()
                            || values.stream().anyMatch(value -> !(value instanceof String))) {
                        throw new IllegalStateException("冻结的治理清洗规则缺少字段绑定");
                    }
                    if (values.size() == 1) {
                        rule.setField((String) values.getFirst());
                    } else {
                        rule.setFields(values.stream().map(String.class::cast).toList());
                    }
                    rule.setLegacyConfig(frozen.getConfig());
                    return rule;
                }).toList());
                CleanRulePlanRespDTO current = governanceApi.snapshotCleanRulePlan(req).getCheckedData();
                if (current == null || !Objects.equals(current.getPlanHash(), plan.getClean().getPlanHash())) {
                    throw new IllegalStateException("治理清洗规则或参数已变化，冻结计划不可执行");
                }
            }
        }
    }

    /**
     * 创建端口。
     *
     * @param plans 计划
     * @param record 运行记录
     * @return ETL行策略端口
     */
    public EtlRowPolicyPort createPort(Map<String, Plan> plans, EtlRecordDO record) {
        Map<String, EtlPopulationIdentity> identities = managedIdentities(record);
        Map<String, QualityRowEvaluator.CompiledPlan> checks = new HashMap<>();
        Map<String, List<CleanRulePlanRespDTO.Rule>> cleanRules = new HashMap<>();
        Map<String, Long> batches = new HashMap<>();
        for (var entry : plans.entrySet()) {
            Plan plan = entry.getValue();
            if (plan.getClean() != null) {
                validateCleanPlan(plan.getClean());
                cleanRules.put(entry.getKey(), plan.getClean().getRules());
            }
            if (plan.getQuality() != null) {
                checks.put(entry.getKey(), compile(plan.getQuality()));
                if (record != null) {
                    batches.put(entry.getKey(),
                            evidenceService.begin(record, entry.getKey(), plan.getQuality().getPlanHash()));
                }
            }
        }
        return new EtlRowPolicyPort() {
            private final Map<String, Long> evidenceBytes = new HashMap<>();

            /**
             * 清洗ETL策略计划数据。
             *
             * @param nodeNo 节点序号
             * @param row 当前行数据
             * @return 处理后的键值映射
             */
            public Map<String, Object> clean(String nodeNo, Map<String, Object> row) {
                List<CleanRulePlanRespDTO.Rule> rules = cleanRules.get(nodeNo);
                return governanceCleanExecutor.apply(rules, row);
            }

            /**
             * 判断需要清洗行集合条件是否成立。
             *
             * @param nodeNo 节点序号
             * @return 处理是否成功
             */
            public boolean requiresCleanRowSet(String nodeNo) {
                var rules = cleanRules.get(nodeNo);
                if (rules == null) {
                    throw new IllegalStateException("缺少冻结治理清洗计划");
                }
                return governanceCleanExecutor.requiresRowSet(rules);
            }

            /**
             * 清洗行。
             *
             * @param nodeNo 节点序号
             * @param rows 待处理的行数据
             * @return 清洗行结果集合
             */
            public List<Map<String, Object>> cleanRows(String nodeNo, List<Map<String, Object>> rows) {
                return governanceCleanExecutor.applyRows(cleanRules.get(nodeNo), rows);
            }

            /**
             * 清洗行。
             *
             * @param nodeNo 节点序号
             * @param rows 待处理的行数据
             * @param control 控制
             * @return 清洗行结果集合
             */
            public List<Map<String, Object>> cleanRows(String nodeNo, List<Map<String, Object>> rows,
                    EtlRowPolicyPort.CleanRowSetControl control) {
                if (control == null) {
                    throw new IllegalArgumentException("治理集合清洗必须提供运行时控制");
                }
                return governanceCleanExecutor.applyRows(cleanRules.get(nodeNo), rows, control);
            }

            /**
             * 评估ETL策略计划。
             *
             * @param nodeNo 节点序号
             * @param row 当前行数据
             * @return 评估ETL策略计划结果集合
             */
            public List<Violation> evaluate(String nodeNo, Map<String, Object> row) {
                var plan = checks.get(nodeNo);
                if (plan == null) {
                    throw new IllegalStateException("缺少冻结质量计划");
                }
                var identity = identities.get(nodeNo);
                if (identity != null) {
                    identity.add(row);
                }
                return plan.evaluate(row).stream()
                        .map(v -> new Violation(v.ruleCode(), v.field(), v.severity(), v.errorCode(), v.message()))
                        .toList();
            }

            /**
             * 记录问题。
             *
             * @param nodeNo 节点序号
             * @param sequence 序列
             * @param row 当前行数据
             * @param violations 违规
             */
            public void recordIssue(String nodeNo, long sequence, Map<String, Object> row, List<Violation> violations) {
                recordIssue(nodeNo, sequence, row, violations, null);
            }

            /**
             * 记录问题。
             *
             * @param nodeNo 节点序号
             * @param sequence 序列
             * @param row 当前行数据
             * @param violations 违规
             * @param origin 原始
             */
            public void recordIssue(String nodeNo, long sequence, Map<String, Object> row, List<Violation> violations,
                    cn.iocoder.yudao.framework.etl.core.runtime.EtlRowOrigin origin) {
                if (!checks.containsKey(nodeNo)) {
                    throw new IllegalStateException("缺少冻结质量计划");
                }
                if (record == null) {
                    return; // 合成数据预览不会登记真实治理结果。
                }
                String json = JsonUtils.toJsonString(row);
                String proof = origin == null ? null : evidenceService.sourceProof(record, batches.get(nodeNo), origin,
                        row);
                long rowBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        + JsonUtils.toJsonString(violations).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        + (proof == null ? 0 : proof.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                synchronized (evidenceBytes) {
                    long bytes = evidenceBytes.getOrDefault(nodeNo, 0L) + rowBytes;
                    if (bytes > 8L * 1024 * 1024 || rowBytes > 128L * 1024) {
                        throw new IllegalStateException("单条隔离证据超过 128 KiB 或本节点累计超过 8 MiB，请分批处理");
                    }
                    evidenceBytes.put(nodeNo, bytes);
                }
                if (proof == null) {
                    evidenceService.recordIssue(record, batches.get(nodeNo), sequence, json, violations);
                } else {
                    evidenceService.recordIssue(record, batches.get(nodeNo), sequence, json, violations, proof);
                }
            }

            /**
             * 完成ETL策略计划处理。
             *
             * @param nodeNo 节点序号
             * @param counts 数量
             */
            public void complete(String nodeNo, Counts counts) {
                if (!checks.containsKey(nodeNo)) {
                    throw new IllegalStateException("缺少冻结质量计划");
                }
                if (record != null) {
                    var identity = identities.get(nodeNo);
                    if (identity == null) {
                        evidenceService.complete(record, batches.get(nodeNo), counts);
                    } else {
                        evidenceService.complete(record, batches.get(nodeNo), counts, identity.fields(),
                                identity.hash());
                    }
                }
            }
        };
    }

    /**
     * 获取ETL策略计划的受管身份。
     *
     * @param record 运行记录
     * @return 处理后的键值映射
     */
    private Map<String, EtlPopulationIdentity> managedIdentities(EtlRecordDO record) {
        if (record == null || StrUtil.isBlank(record.getConfigJson())) {
            return Map.of();
        }
        var snapshot = JSONUtil.parseObj(record.getConfigJson());
        Map<String, String> params = new HashMap<>();
        var parameters = snapshot.getJSONArray("parameters");
        if (parameters != null) {
            for (Object raw : parameters) {
                var parameter = JSONUtil.parseObj(raw);
                if (StrUtil.isNotBlank(parameter.getStr("name"))) {
                    params.put(parameter.getStr("name"), parameter.getStr("defaultValue", ""));
                }
            }
        }
        var nodes = snapshot.getJSONArray("nodes");
        if (nodes == null) {
            return Map.of();
        }
        var definitions = EtlGraphConverter.toNodeDefs(JsonUtils.parseObject(nodes.toString(),
                new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                }));
        var variables = new EtlVariableContext(params);
        Map<String, EtlPopulationIdentity> result = new HashMap<>();
        for (var node : definitions) {
            if ("managed-db-output".equals(node.getToolCode())) {
                var config = JSONUtil.parseObj(variables.resolveJson(node.getNodeJson()));
                var fields = config.getJSONArray("businessKeys");
                if (fields == null) {
                    throw new IllegalArgumentException("受管输出必须定义稳定业务键");
                }
                var identity = new EtlPopulationIdentity(fields.toList(String.class));
                var previous = result.putIfAbsent(config.getStr("qualityNodeNo"), identity);
                if (previous != null && !previous.fields().equals(identity.fields())) {
                    throw new IllegalArgumentException("共享质量节点的目标必须使用相同业务键身份");
                }
            }
        }
        return result;
    }

    /**
     * 获取ETL策略计划的编译。
     *
     * @param plan 计划
     * @return 已编译计划
     */
    private QualityRowEvaluator.CompiledPlan compile(RowQualityPlanRespDTO plan) {
        if (plan == null || !plan.isExecutable() || StrUtil.isBlank(plan.getPlanHash())
                || plan.getRules() == null || plan.getRules().isEmpty()) {
            throw new IllegalArgumentException("规则集未提供有效的可执行行级计划");
        }
        if (plan.getUnsupportedRules() == null || !plan.getUnsupportedRules().isEmpty()) {
            throw new IllegalArgumentException("规则集包含不支持逐行执行的规则或能力信息缺失，请使用批次检测");
        }
        return new QualityRowEvaluator().compile(plan.getRules().stream().map(r ->
                        new QualityRowRule(r.getCode(), r.getStrategy(), r.getField(), r.getConfig(), r.getSeverity()))
                .toList());
    }

    /**
     * 校验清洗计划。
     *
     * @param plan 计划
     */
    private void validateCleanPlan(CleanRulePlanRespDTO plan) {
        if (plan == null || StrUtil.isBlank(plan.getPlanHash()) || plan.getRules() == null
                || plan.getRules().isEmpty() || plan.getRules().size() > 50) {
            throw new IllegalArgumentException("规则快照缺失或无效");
        }
        for (var rule : plan.getRules()) {
            if (rule == null || rule.getRuleId() == null || StrUtil.isBlank(rule.getCode())
                    || !cleanFunctionRegistry.contains(rule.getStrategyKey())
                    || rule.getConfig() == null) {
                throw new IllegalArgumentException("治理清洗计划包含不支持规则");
            }
        }
    }
}
