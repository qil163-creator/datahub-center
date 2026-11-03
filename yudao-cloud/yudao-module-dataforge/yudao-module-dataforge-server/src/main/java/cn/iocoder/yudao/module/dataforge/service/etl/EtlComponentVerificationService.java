package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.validation.EtlGraphValidator;
import cn.iocoder.yudao.framework.etl.core.validation.EtlValidationResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlComponentVerifyReqVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * qData 组件级校验适配器。
 *
 * <p>组件校验只做无副作用的节点、参数和 DAG 检查，不连接数据源、不执行脚本，
 * 具体执行仍由发布后的流程运行器负责。</p>
 */
@Service
public class EtlComponentVerificationService {

    private static final Map<String, String> COMPONENT_ALIASES = Map.ofEntries(
            Map.entry("table-input", "read-db"),
            Map.entry("table_input", "read-db"),
            Map.entry("表输入组件", "read-db"),
            Map.entry("database-input", "read-db"),
            Map.entry("db-input", "read-db"),
            Map.entry("table-output", "insert-db"),
            Map.entry("table_output", "insert-db"),
            Map.entry("表输出组件", "insert-db"),
            Map.entry("database-output", "insert-db"),
            Map.entry("db-output", "insert-db"),
            Map.entry("api-input", "rest-api"),
            Map.entry("kafka-input", "read-kafka"),
            Map.entry("kafka-output", "write-kafka"),
            Map.entry("excel-input", "read-excel"),
            Map.entry("csv-input", "read-csv"),
            Map.entry("json-input", "read-json"),
            Map.entry("shell", "shell-script"),
            Map.entry("python", "python-script"),
            Map.entry("sql", "sql-script"),
            Map.entry("java", "java-script"));

    @Resource
    private EtlExecutorRegistry registry;

    /**
     * 校验单个组件或完整画布。
     *
     * @param request 请求参数
     * @return 校验结果
     */
    public EtlValidationResult verify(EtlComponentVerifyReqVO request) {
        if (request == null) {
            return EtlValidationResult.of(List.of(new EtlValidationResult.Issue(null, "request", "ERROR",
                    "REQUIRED", "组件请求不能为空")));
        }
        if (request.getNodes() != null && !request.getNodes().isEmpty()) {
            return EtlGraphValidator.validate(EtlGraphConverter.toNodeDefs(request.getNodes()),
                    EtlGraphConverter.toEdgeDefs(request.getEdges()), registry);
        }
        String componentType = StrUtil.blankToDefault(StrUtil.trim(request.getComponentType()), "");
        String toolCode = COMPONENT_ALIASES.getOrDefault(componentType.toLowerCase(Locale.ROOT),
                COMPONENT_ALIASES.getOrDefault(componentType, componentType));
        Integer toolType = request.getToolType() == null ? inferToolType(toolCode) : request.getToolType();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", StrUtil.blankToDefault(request.getNodeName(),
                StrUtil.blankToDefault(request.getNodeId(), componentType)));
        properties.put("toolCode", toolCode);
        properties.put("toolType", toolType);
        properties.put("nodeJson", normalizeParams(toolCode, request.getTaskParams()));
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", StrUtil.blankToDefault(request.getNodeId(), "component"));
        node.put("properties", properties);
        EtlValidationResult result = EtlGraphValidator.validate(EtlGraphConverter.toNodeDefs(List.of(node)),
                List.of(), registry);
        // 单组件请求没有下游画布，质量节点的端口连接只能在完整画布校验时判断。
        // 这类问题降级为提示，组件自身缺少参数仍然保持 ERROR。
        List<EtlValidationResult.Issue> issues = result.issues().stream()
                .map(issue -> isGraphContextIssue(issue)
                        ? new EtlValidationResult.Issue(issue.nodeId(), issue.field(), "WARN", issue.code(),
                        issue.message())
                        : issue)
                .toList();
        return EtlValidationResult.of(issues);
    }

    private boolean isGraphContextIssue(EtlValidationResult.Issue issue) {
        return switch (issue.code()) {
            case "GRAPH_INVALID", "OUTPUT_REQUIRED", "INVALID_PORT", "TRUSTED_QUALITY_REQUIRED", "FIELD_MISMATCH" -> true;
            default -> false;
        };
    }

    private int inferToolType(String toolCode) {
        if (SetLike.OUTPUTS.contains(toolCode)) {
            return EtlNodeDef.TOOL_TYPE_OUTPUT;
        }
        if (SetLike.INPUTS.contains(toolCode)) {
            return EtlNodeDef.TOOL_TYPE_INPUT;
        }
        if (SetLike.SCRIPTS.contains(toolCode)) {
            return EtlNodeDef.TOOL_TYPE_SCRIPT;
        }
        return EtlNodeDef.TOOL_TYPE_TRANSFORM;
    }

    /**
     * 兼容 qData 的 datasource/table 参数名，保持执行器内部字段统一。
     */
    private Map<String, Object> normalizeParams(String toolCode, Map<String, Object> source) {
        Map<String, Object> params = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        if (SetLike.DATABASE_NODES.contains(toolCode)) {
            copyIfMissing(params, "databaseId", "datasourceId");
            copyIfMissing(params, "databaseId", "dataSourceId");
            copyIfMissing(params, "tableName", "table");
            copyDatasourceId(params, "readerDatasource");
            copyDatasourceId(params, "writerDatasource");
        }
        return params;
    }

    private void copyDatasourceId(Map<String, Object> params, String field) {
        if (params.containsKey("databaseId") || !(params.get(field) instanceof Map<?, ?> datasource)) {
            return;
        }
        Object value = datasource.get("id");
        if (value == null) {
            value = datasource.get("datasourceId");
        }
        if (value == null) {
            value = datasource.get("dataSourceId");
        }
        if (value != null) {
            params.put("databaseId", value);
        }
    }

    private void copyIfMissing(Map<String, Object> params, String target, String source) {
        if (!params.containsKey(target) && params.containsKey(source)) {
            params.put(target, params.get(source));
        }
    }

    /** 使用常量集合避免在请求校验中产生新的执行器实例。 */
    private static final class SetLike {
        private static final java.util.Set<String> INPUTS = java.util.Set.of(
                "read-db", "read-kafka", "rest-api", "read-excel", "read-csv", "read-json",
                "generate-records", "random-records");
        private static final java.util.Set<String> OUTPUTS = java.util.Set.of(
                "insert-db", "managed-db-output", "write-kafka", "write-excel", "write-csv",
                "write-json", "write-text");
        private static final java.util.Set<String> SCRIPTS = java.util.Set.of(
                "shell-script", "python-script", "sql-script", "java-script", "js-script");
        private static final java.util.Set<String> DATABASE_NODES = java.util.Set.of(
                "read-db", "insert-db", "managed-db-output");
    }
}
