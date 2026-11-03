package cn.iocoder.yudao.framework.etl.core.validation;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlFieldRules;
import cn.iocoder.yudao.framework.etl.core.model.*;

import java.util.*;

/** 无副作用的结构和配置校验，可用于发布前和运行前。 */
public final class EtlGraphValidator {

    /**
     * 初始化 {@code EtlGraphValidator}。
     */
    private EtlGraphValidator() {
    }

    /**
     * 检查节点、连线和配置约束并收集结构校验问题。
     */
    public static EtlValidationResult validate(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges,
            EtlExecutorRegistry registry) {
        List<EtlValidationResult.Issue> issues = new ArrayList<>();
        if (nodes == null || nodes.isEmpty() || nodes.size() > 32) {
            error(issues, null, "nodes", "NODE_LIMIT", "流程必须包含 1 至 32 个节点");
            return EtlValidationResult.of(issues);
        }
        if (edges == null) {
            edges = List.of();
        }
        if (edges.size() > 128) {
            error(issues, null, "edges", "EDGE_LIMIT", "连线不能超过 128 条");
        }
        try {
            EtlDag.build(nodes, edges);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            error(issues, null, "edges", "GRAPH_INVALID", ex.getMessage());
        }
        Set<String> edgeKeys = new HashSet<>();
        for (EtlEdgeDef edge : edges) {
            if (!edgeKeys.add(edge.getSourceNo() + "->" + edge.getTargetNo())) {
                error(issues, edge.getTargetNo(), "edges", "DUPLICATE_EDGE",
                        "相同节点之间只允许一条连线，请通过独立输入节点合并分支");
            }
        }
        for (EtlNodeDef node : nodes) {
            if (node.getToolCode() == null || !registry.supports(node.getToolCode())) {
                error(issues, node.getNo(), "toolCode", "UNSUPPORTED_TOOL", "组件尚未实现");
                continue;
            }
            try {
                JSONObject config = JSONUtil.parseObj(StrUtil.blankToDefault(node.getNodeJson(), "{}"));
                List<String> required = switch (node.getToolCode()) {
                    case "quality-check" -> List.of("ruleSetId");
                    case "read-json", "read-csv", "read-gzip-csv", "read-excel" -> List.of("fileUrl");
                    case "write-json", "write-text" -> List.of("filePath", "fileName");
                    case "insert-db" -> List.of("databaseId", "tableName");
                    case "managed-db-output" ->
                            List.of("databaseId", "tableName", "qualityNodeNo", "qualityTaskId", "fields",
                                    "businessKeys");
                    case "read-db" -> List.of("databaseId", config.getInt("ifSql", 0) == 1 ? "sql" : "tableName");
                    case "rest-api" -> List.of("apiConfig");
                    default -> List.of();
                };
                for (String field : required) {
                    if (StrUtil.isBlank(config.getStr(field))) {
                        error(issues, node.getNo(), field, "REQUIRED", "请配置 " + field);
                    }
                }
                if (EtlFieldRules.supports(node.getToolCode())) {
                    if (!Objects.equals(node.getToolType(), EtlNodeDef.TOOL_TYPE_TRANSFORM)) {
                        error(issues, node.getNo(), "toolType", "TOOL_TYPE", "字段规则组件必须为转换节点");
                    }
                    EtlFieldRules.compile(node.getToolCode(), config);
                }
                if ("rest-api".equals(node.getToolCode())) {
                    if (!Objects.equals(node.getToolType(), EtlNodeDef.TOOL_TYPE_INPUT)) {
                        error(issues, node.getNo(), "toolType", "TOOL_TYPE", "API 接口必须为输入节点");
                    }
                    JSONObject api = config.getJSONObject("apiConfig");
                    if (api != null) {
                        for (String field : List.of("requestUrl", "requestMethod")) {
                            if (StrUtil.isBlank(api.getStr(field))) {
                                error(issues, node.getNo(), "apiConfig." + field, "REQUIRED", "请配置接口 " + field);
                            }
                        }
                        if (api.getInt("ifPage", 0) == 1
                                && (StrUtil.isBlank(api.getStr("pageKey")) || StrUtil.isBlank(
                                api.getStr("pageSizeKey")))) {
                            error(issues, node.getNo(), "apiConfig", "REQUIRED", "请配置分页参数名称");
                        }
                        if (api.getJSONArray("dataColumns") == null || api.getJSONArray("dataColumns").isEmpty()) {
                            error(issues, node.getNo(), "apiConfig.dataColumns", "REQUIRED",
                                    "请测试接口并配置返回字段");
                        }
                    }
                    if (config.getInt("fetchSize", 500) <= 0) {
                        error(issues, node.getNo(), "fetchSize", "RANGE", "每页条数必须大于零");
                    }
                }
                if (Set.of("generate-records", "random-records").contains(node.getToolCode())) {
                    if (!Objects.equals(node.getToolType(), EtlNodeDef.TOOL_TYPE_INPUT)) {
                        error(issues, node.getNo(), "toolType", "TOOL_TYPE", "记录生成组件必须为输入节点");
                    }
                    int count = config.getInt("count", 1);
                    if (count < 0 || count > 1_000_000) {
                        error(issues, node.getNo(), "count", "RANGE", "记录数须在 0 至 1000000 之间");
                    }
                    var fields = config.getJSONArray("fields");
                    if (fields == null || fields.isEmpty()) {
                        error(issues, node.getNo(), "fields", "REQUIRED", "请配置生成字段");
                    } else {
                        Set<String> names = new HashSet<>();
                        for (Object value : fields) {
                            String name = JSONUtil.parseObj(value).getStr("name");
                            if (StrUtil.isBlank(name) || !names.add(name)) {
                                error(issues, node.getNo(), "fields", "DUPLICATE_FIELD", "字段名称不能为空或重复");
                            }
                        }
                    }
                }
                if ("quality-check".equals(node.getToolCode())
                        && !Objects.equals(node.getToolType(), EtlNodeDef.TOOL_TYPE_TRANSFORM)) {
                    error(issues, node.getNo(), "toolType", "TOOL_TYPE", "治理节点必须为转换节点");
                }
                if ("quality-check".equals(node.getToolCode())) {
                    String disposition = config.getStr("disposition", "ROUTE");
                    if (!Set.of("ROUTE", "KEEP_TAGGED").contains(disposition)) {
                        error(issues, node.getNo(), "disposition", "POLICY", "仅支持分流或带标识保留");
                    }
                    var ports = "KEEP_TAGGED".equals(disposition) ? Set.of("main") : Set.of("pass", "warn", "reject");
                    for (String port : ports) {
                        boolean connected = edges.stream()
                                .anyMatch(e -> node.getNo().equals(e.getSourceNo()) && !e.isControl()
                                        && port.equals(e.resolveOutputPort()) && nodes.stream()
                                        .anyMatch(n -> Objects.equals(n.getNo(), e.getTargetNo())
                                                && !Objects.equals(n.getToolType(), EtlNodeDef.TOOL_TYPE_SCRIPT)));
                        if (!connected) {
                            error(issues, node.getNo(), "ports." + port, "OUTPUT_REQUIRED",
                                    "质量端口 " + port + " 必须连接数据下游");
                        }
                    }
                    for (EtlEdgeDef edge : edges) {
                        if (node.getNo().equals(edge.getSourceNo()) && !ports.contains(edge.resolveOutputPort())) {
                            error(issues, node.getNo(), "ports", "INVALID_PORT", "质量连线端口与当前策略不一致");
                        }
                    }
                    if (config.getJSONArray("fieldBindings") == null || config.getJSONArray("fieldBindings")
                            .isEmpty()) {
                        error(issues, node.getNo(), "fieldBindings", "REQUIRED", "请选择规则字段绑定");
                    }
                }
                if ("managed-db-output".equals(node.getToolCode())) {
                    if (!Objects.equals(node.getToolType(), EtlNodeDef.TOOL_TYPE_OUTPUT)) {
                        error(issues, node.getNo(), "toolType", "TOOL_TYPE", "受管数据库输出必须为输出节点");
                    }
                    if (!Set.of("STRICT", "KEEP_TAGGED", "BATCH_BLOCK")
                            .contains(config.getStr("disposition", "STRICT"))) {
                        error(issues, node.getNo(), "disposition", "POLICY", "不支持的目标准入策略");
                    }
                    var upstream = edges.stream().filter(e -> !e.isControl() && node.getNo().equals(e.getTargetNo()))
                            .toList();
                    EtlNodeDef quality = nodes.stream()
                            .filter(n -> Objects.equals(n.getNo(), config.getStr("qualityNodeNo")))
                            .findFirst().orElse(null);
                    if (quality == null || !"quality-check".equals(quality.getToolCode()) || upstream.size() != 1
                            || !quality.getNo().equals(upstream.getFirst().getSourceNo()) || !"main".equals(
                            upstream.getFirst().resolveOutputPort())
                            || !"KEEP_TAGGED".equals(JSONUtil.parseObj(quality.getNodeJson()).getStr("disposition"))) {
                        error(issues, node.getNo(), "qualityNodeNo", "TRUSTED_QUALITY_REQUIRED",
                                "受管输出必须直接连接全量标记模式的质量检查节点");
                    } else if (!config.getStr("statusColumn", "_quality_status")
                            .equals(JSONUtil.parseObj(quality.getNodeJson())
                                    .getStr("statusField", "_quality_status"))) {
                        error(issues, node.getNo(), "statusColumn", "FIELD_MISMATCH", "质量字段必须与上游检测节点一致");
                    }
                    var fields = config.getJSONArray("fields");
                    if (fields == null || fields.isEmpty() || fields.size() > 254) {
                        error(issues, node.getNo(), "fields", "REQUIRED", "请配置 1 至 254 个输出字段（包括质量状态）");
                    } else if (new HashSet<>(fields.toList(String.class)).size() != fields.size()
                            || !fields.contains(config.getStr("statusColumn", "_quality_status"))
                            || fields.contains(config.getStr("batchColumn", "_etl_batch_key")) || fields.contains(
                            config.getStr("rowColumn", "_etl_row_no"))) {
                        error(issues, node.getNo(), "fields", "FIELD_COLLISION",
                                "输出字段需唯一并含质量状态，批次和行号字段由系统生成");
                    }
                    var keys = config.getJSONArray("businessKeys");
                    if (keys == null || keys.isEmpty() || keys.size() > 16 || fields == null || !fields.containsAll(
                            keys)
                            || new HashSet<>(keys.toList(String.class)).size() != keys.size()
                            || keys.contains(config.getStr("statusColumn", "_quality_status"))) {
                        error(issues, node.getNo(), "businessKeys", "IDENTITY_REQUIRED",
                                "请选择 1 至 16 个输出业务键，不含质量状态字段");
                    }
                }
            } catch (EtlFieldRules.ConfigException ex) {
                error(issues, node.getNo(), ex.field(), "FIELD_RULE_INVALID", ex.getMessage());
            } catch (RuntimeException ex) {
                error(issues, node.getNo(), "nodeJson", "CONFIG_INVALID", "组件参数不是有效的配置对象或字段类型不正确");
            }
        }
        return EtlValidationResult.of(issues);
    }

    /**
     * 返回错误。
     */
    private static void error(List<EtlValidationResult.Issue> issues, String node, String field, String code,
            String message) {
        issues.add(new EtlValidationResult.Issue(node, field, "ERROR", code, message));
    }
}
