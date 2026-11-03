package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.schema.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlConnectionResolverAdapter;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlGraphConverter;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;

/** 集成画布的只读字段分析；元数据和转换复用组件，执行能力以同一原生编译器为准。 */
@Service
public class IntegrationSchemaService {
    @Resource private IntegrationFlowCompiler compiler;
    @Resource private EtlSchemaService schemas;
    @Resource private EtlConnectionResolverAdapter connections;
    @Resource private ExecutionAccessService access;

    public EtlSchemaResult resolve(IntegrationTaskDO task, String flowJson) {
        var graph = compiler.graph(flowJson);
        var nodes = EtlGraphConverter.toNodeDefs(graph.getJSONArray("nodes").toList(Map.class).stream().map(item -> (Map<String, Object>) item).toList());
        var edges = EtlGraphConverter.toEdgeDefs(graph.getJSONArray("edges").toList(Map.class).stream().map(item -> (Map<String, Object>) item).toList());
        var result = new EtlSchemaResult();
        if (nodes.isEmpty()) return result;
        if (nodes.size() > 64) { result.getErrors().add("字段分析最多支持64个节点，请拆分流程"); return result; }
        EtlDag dag;
        try { dag = EtlDag.buildLenient(nodes, edges); }
        catch (IllegalArgumentException | IllegalStateException invalid) { result.getErrors().add(invalid.getMessage()); return result; }
        var sourceFields = new LinkedHashMap<String, List<EtlFieldMeta>>();
        var targets = new HashMap<String, List<EtlFieldMeta>>();
        var failures = new HashSet<String>(); var cache = new HashMap<String, List<EtlFieldMeta>>();
        for (var node : nodes) {
            String code = node.getToolCode();
            if (code == null) continue;
            if ("read-db".equals(code) || "insert-db".equals(code)) {
                try {
                    var fields = databaseFields(node, cache);
                    if ("read-db".equals(code)) sourceFields.put(node.getNo(), selected(node, fields));
                    else targets.put(node.getNo(), fields);
                } catch (RuntimeException failure) {
                    failures.add(node.getNo());
                    result.getErrors().add("节点【" + node.getName() + "】字段元数据不可用，请核对授权、连接和字段配置；SQL仅支持单表受限 SELECT");
                    if ("read-db".equals(code)) sourceFields.put(node.getNo(), List.of());
                }
            } else if (code.startsWith("read-")) {
                sourceFields.put(node.getNo(), declared(node));
                result.getWarnings().add("节点【" + node.getName() + "】仅展示配置声明字段，未读取外部数据或验证实际载荷");
            }
        }
        var inferred = schemas.resolve(dag, connections, null, sourceFields);
        inferred.getErrors().addAll(result.getErrors()); inferred.getWarnings().addAll(result.getWarnings()); result = inferred;
        Map<String, cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.NodeFields> shapes = Map.of();
        boolean executableShape = false;
        try {
            if ("DATAX".equals(task.getEngine())) compiler.compile(task, flowJson);
            else shapes = compiler.analyzeNative(task, nodes, edges).nodes();
            executableShape = true;
        } catch (IllegalArgumentException invalid) {
            result.getWarnings().add("当前执行通道仅能提供局部字段提示：" + invalid.getMessage());
        } catch (RuntimeException unavailable) {
            result.getWarnings().add("当前受管执行绑定不可用，字段列表不代表流程可执行");
        }
        for (String id : dag.topologicalSort()) {
            var node = dag.getNode(id); var schema = result.getNodes().get(id);
            if (schema == null) continue;
            try {
            var inputs = mergeInputs(dag, result, node);
            var shape = shapes.get(id);
            if (shape != null) inputs = shapeFields(shape.inputFields(), inputs);
            schema.setInputFields(inputs);
            if ("insert-db".equals(node.getToolCode())) {
                schema.setOutputFields(sink(node, inputs, targets.getOrDefault(id, List.of()), result));
            } else if (node.getToolCode() != null && node.getToolCode().startsWith("write-")) {
                var declared = declared(node);
                for (int i = 0; i < declared.size(); i++) if (i < inputs.size()) declared.get(i).setSourceName(inputs.get(i).getSourceName());
                schema.setOutputFields(declared);
            } else {
                boolean declaredOutput = node.isRowScript() && JSONUtil.parseObj(node.getNodeJson()).getJSONArray("outputFields") != null;
                var outputs = declaredOutput && shape == null ? declared(node, "outputFields") : "column-map".equals(node.getToolCode()) && "FIELDS".equals(JSONUtil.parseObj(node.getNodeJson()).getStr("columnMappingMode"))
                        ? projectFields(node, inputs) : schema.getOutputFields();
                boolean rowColumnV2 = "row-column".equals(node.getToolCode()) && JSONUtil.parseObj(node.getNodeJson()).getInt("rowColumnVersion", 1) == 2;
                if (rowColumnV2) outputs = rowColumnFields(node, inputs);
                if (shape != null) outputs = shapeFields(shape.outputFields(), outputs);
                if (rowColumnV2 && shape != null) outputs.forEach(IntegrationSchemaService::nativeTypeConstraints);
                for (var field : outputs) {
                    String origin = origin(node, field.getName(), inputs);
                    if (origin != null) field.setSourceName(origin);
                    if (declaredOutput && shape == null) field.setSourceName(node.getName());
                    if (shape == null && !declaredOutput && !knownGeneric(node.getToolCode()) && !sourceFields.containsKey(id)) {
                        field.setDataType(null); clearConstraints(field);
                    }
                }
                schema.setOutputFields(outputs);
                if (declaredOutput && shape == null) result.getWarnings().add("节点【" + node.getName() + "】输出字段来自配置声明，未执行脚本或验证实际载荷");
            }
            if ("data-condition".equals(node.getToolCode())) {
                var ports = new LinkedHashMap<String, List<EtlFieldMeta>>();
                var portInputs = inputs;
                if (shape != null && !shape.outputPorts().isEmpty()) shape.outputPorts().forEach((port, fields) -> ports.put(port, shapeFields(fields, portInputs)));
                else {
                    var branches = JSONUtil.parseObj(node.getNodeJson()).getJSONArray("branches");
                    if (branches != null) for (Object item : branches) {
                        var rule = JSONUtil.parseObj(item); String port = rule.getStr("id"); if (port == null || port.isBlank()) continue;
                        var fields = rule.getJSONArray("fields"); var requested = fields == null ? inputs.stream().map(EtlFieldMeta::getName).toList() : fields.toList(String.class);
                        var available = indexed(inputs); var selected = new ArrayList<EtlFieldMeta>();
                        for (String name : requested) { if (!available.containsKey(name)) throw new IllegalArgumentException("分支引用未知字段"); selected.add(available.get(name).copy()); }
                        ports.put(port, selected);
                    }
                }
                ports.values().forEach(fields -> fields.forEach(field -> field.setSourceName(node.getName())));
                schema.setOutputPorts(ports);
                var summary = new LinkedHashMap<String, EtlFieldMeta>();
                ports.values().forEach(fields -> fields.forEach(field -> summary.putIfAbsent(field.getName(), field.copy())));
                schema.setOutputFields(new ArrayList<>(summary.values()));
            }
            boolean parentsComplete = dag.getDataUpstreamList(id).stream().allMatch(parent -> Boolean.TRUE.equals(resultNode(inferred, parent).getComplete()));
            schema.setComplete(executableShape && result.getErrors().isEmpty() && !failures.contains(id) && parentsComplete
                    && !schema.getOutputFields().isEmpty() && schema.getOutputFields().stream().allMatch(field -> field.getDataType() != null && !field.getDataType().isBlank()));
            } catch (RuntimeException invalid) {
                schema.setComplete(false); result.getErrors().add("节点【" + node.getName() + "】字段配置不完整，无法证明输出契约");
            }
        }
        return result;
    }

    private static EtlSchemaResult.NodeSchema resultNode(EtlSchemaResult result, String id) {
        return result.getNodes().getOrDefault(id, new EtlSchemaResult.NodeSchema());
    }

    /** 读取原生列事实；不使用旧 DTO 的 displaySize/precisionSize 默认值补事实。 */
    List<EtlFieldMeta> databaseFields(EtlNodeDef node, Map<String, List<EtlFieldMeta>> cache) {
        var config = JSONUtil.parseObj(node.getNodeJson()); Long id = config.getLong("databaseId");
        if (id == null) throw new IllegalArgumentException("未选择数据源");
        access.requireDatabase(id);
        boolean sqlMode = "read-db".equals(node.getToolCode()) && config.getInt("ifSql", 0) == 1;
        if (!sqlMode) config.set("ifSql", 0);
        String key = id + ":" + config.getStr("schemaName", "") + ":" + (sqlMode ? "sql:" : "table:") + (sqlMode ? config.getStr("sql") : config.getStr("tableName"));
        List<EtlFieldMeta> base = cache.get(key);
        if (base == null) {
            if (sqlMode) {
                var connection = connections.resolve(id);
                var plan = RestrictedSelectGuard.parseBound(connection.getProductType(), config.getStr("sql"), connection.getSchema());
                config.set("sql", plan.getNormalizedSql());
            }
            base = schemas.readDatabaseFields(config, connections);
            if (base.size() > 5000) throw new IllegalArgumentException("字段超过分析上限");
            cache.put(key, base);
        }
        return base.stream().map(field -> { var copy = field.copy(); copy.setSourceName(node.getName()); return copy; }).toList();
    }

    private static List<EtlFieldMeta> selected(EtlNodeDef node, List<EtlFieldMeta> fields) {
        var config = JSONUtil.parseObj(node.getNodeJson()); var selected = config.getJSONArray("selectedColumns");
        if (selected == null || config.getInt("ifSql", 0) == 1) return fields;
        if (selected.isEmpty()) throw new IllegalArgumentException("未选择输入字段");
        var byName = indexed(fields); var result = new ArrayList<EtlFieldMeta>(); var seen = new HashSet<String>();
        for (String name : selected.toList(String.class)) {
            if (!byName.containsKey(name) || !seen.add(name)) throw new IllegalArgumentException("输入字段不存在或重复");
            result.add(byName.get(name).copy());
        }
        return result;
    }

    private static List<EtlFieldMeta> declared(EtlNodeDef node) { return declared(node, "columns"); }

    private static List<EtlFieldMeta> declared(EtlNodeDef node, String key) {
        var columns = JSONUtil.parseObj(node.getNodeJson()).getJSONArray(key); var result = new ArrayList<EtlFieldMeta>();
        if (columns == null) return result;
        for (Object item : columns) {
            var config = JSONUtil.parseObj(item); String name = config.getStr("name"); if (name == null || name.isBlank()) continue;
            String type = config.getStr("dataType", config.getStr("type"));
            var field = new EtlFieldMeta(name, type == null || type.isBlank() ? null : type);
            // 连接器旧字段协议以 length 表示小数总位数、precision 表示小数位数；不将其混为字符长度。
            boolean decimal = type != null && type.toUpperCase(Locale.ROOT).matches("(?:DECIMAL|NUMERIC|BIGNUMBER|BIGDECIMAL)(?:\\(.*\\))?");
            if (decimal && "columns".equals(key) && config.get("scale") == null) {
                field.setPrecision(declaredInteger(config, "length")); field.setScale(declaredInteger(config, "precision"));
            } else {
                field.setLength(declaredLong(config, "length"));
                field.setPrecision(declaredInteger(config, "precision")); field.setScale(declaredInteger(config, "scale"));
            }
            if (config.get("nullable") instanceof Boolean nullable) field.setNullable(nullable);
            field.setSourceName(node.getName()); result.add(field);
        }
        return result;
    }

    /** 声明缺失或非法时保留未知，不用零值或有损数值代替。 */
    private static Long declaredLong(JSONObject config, String key) {
        Object value = config.get(key);
        if (value == null || value.toString().isBlank()) return null;
        try {
            long parsed = new java.math.BigDecimal(value.toString()).longValueExact();
            return parsed >= 0 ? parsed : null;
        } catch (RuntimeException invalid) { return null; }
    }

    private static Integer declaredInteger(JSONObject config, String key) {
        Long value = declaredLong(config, key);
        return value != null && value <= Integer.MAX_VALUE ? value.intValue() : null;
    }

    private static List<EtlFieldMeta> mergeInputs(EtlDag dag, EtlSchemaResult result, EtlNodeDef node) {
        var merged = new LinkedHashMap<String, EtlFieldMeta>();
        for (var edge : dag.getEdges()) {
            if (edge.isControl() || !node.getNo().equals(edge.getTargetNo())) continue;
            String parent = edge.getSourceNo(); var parentSchema = resultNode(result, parent);
            var fields = parentSchema.getOutputFields();
            if (!parentSchema.getOutputPorts().isEmpty()) {
                fields = parentSchema.getOutputPorts().get(edge.resolveOutputPort());
                if (fields == null) { result.getErrors().add("节点【" + node.getName() + "】引用不存在的上游输出端口"); continue; }
            }
            for (var original : fields) {
            var field = original.copy();
            if ("data-condition".equals(dag.getNode(parent).getToolCode())) field.setSourceName(dag.getNode(parent).getName());
            var prior = merged.get(field.getName());
            if (prior == null) merged.put(field.getName(), field.copy());
            else {
                if (!Objects.equals(prior.getDataType(), field.getDataType())) prior.setDataType(null);
                if (!Objects.equals(prior.getLength(), field.getLength())) prior.setLength(null);
                if (!Objects.equals(prior.getPrecision(), field.getPrecision())) prior.setPrecision(null);
                if (!Objects.equals(prior.getScale(), field.getScale())) prior.setScale(null);
                if (!Objects.equals(prior.getNullable(), field.getNullable())) prior.setNullable(null);
                prior.setSourceName(join(prior.getSourceName(), field.getSourceName()));
            }
        }
        }
        return new ArrayList<>(merged.values());
    }

    private static List<EtlFieldMeta> projectFields(EtlNodeDef node, List<EtlFieldMeta> inputs) {
        var config = JSONUtil.parseObj(node.getNodeJson()); var selections = config.getJSONArray("fieldSelections");
        var removed = config.getJSONArray("removedFields"); var source = indexed(inputs); var result = new ArrayList<EtlFieldMeta>();
        if (selections == null) return result;
        for (Object item : selections) {
            var rule = JSONUtil.parseObj(item); String name = rule.getStr("name");
            if (removed != null && removed.contains(name)) continue;
            if (!source.containsKey(name)) throw new IllegalArgumentException("选择字段不存在");
            String alias = rule.getStr("alias", name); if (alias.isBlank()) alias = name;
            var field = source.get(name).renamed(alias);
            if (!alias.equals(name)) field.setSourceName(node.getName());
            String type = rule.getStr("type", "");
            if (!type.isBlank() && !type.equalsIgnoreCase(field.getDataType()) || !rule.getStr("length", "").isBlank() || !rule.getStr("precision", "").isBlank()) {
                field.setDataType(null); clearConstraints(field);
            }
            result.add(field);
        }
        return result;
    }

    /** 原生编译不可用时只保留V2表格的字段名称；不能将声明的类型伪装成实际执行事实。 */
    private static List<EtlFieldMeta> rowColumnFields(EtlNodeDef node, List<EtlFieldMeta> inputs) {
        var config = JSONUtil.parseObj(node.getNodeJson()); var available = indexed(inputs); var result = new LinkedHashMap<String, EtlFieldMeta>();
        if ("COL2ROW".equals(config.getStr("mode", "COL2ROW"))) {
            var mappings = config.getJSONArray("unpivotMappings"); if (mappings == null) return List.of();
            var consumed = new HashSet<String>();
            mappings.forEach(item -> consumed.add(JSONUtil.parseObj(item).getStr("sourceField")));
            available.forEach((name, field) -> { if (!consumed.contains(name)) result.put(name, field.copy()); });
            String key = config.getStr("keyField");
            if (key != null && !key.isBlank()) { var field = new EtlFieldMeta(key, "STRING"); field.setNullable(false); result.put(key, field); }
            for (Object item : mappings) {
                var rule = JSONUtil.parseObj(item); String target = rule.getStr("targetField"); if (target == null || target.isBlank()) continue;
                var field = new EtlFieldMeta(target, null); field.setNullable(true); result.putIfAbsent(target, field);
            }
        } else {
            var groups = config.getJSONArray("groupFields");
            if (groups != null) for (String name : groups.toList(String.class)) if (available.containsKey(name)) result.put(name, available.get(name).copy());
            var mappings = config.getJSONArray("pivotMappings"); if (mappings == null) return new ArrayList<>(result.values());
            for (Object item : mappings) {
                String target = JSONUtil.parseObj(item).getStr("targetField"); if (target == null || target.isBlank()) continue;
                var field = new EtlFieldMeta(target, null); field.setNullable(true); result.putIfAbsent(target, field);
            }
        }
        return new ArrayList<>(result.values());
    }

    /** 长度与小数位只取正式编译器已经验证并生成的原生类型，不采用表单默认值。 */
    private static void nativeTypeConstraints(EtlFieldMeta field) {
        String type = field.getDataType(); if (type == null) return;
        var decimal = java.util.regex.Pattern.compile("DECIMAL\\(([0-9]+),([0-9]+)\\)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(type);
        var text = java.util.regex.Pattern.compile("(?:VAR)?CHAR\\(([0-9]+)\\)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(type);
        if (decimal.matches()) { field.setLength(null); field.setPrecision(Integer.valueOf(decimal.group(1))); field.setScale(Integer.valueOf(decimal.group(2))); }
        else if (text.matches()) { field.setLength(Long.valueOf(text.group(1))); field.setPrecision(null); field.setScale(null); }
    }

    private static List<EtlFieldMeta> shapeFields(Map<String, String> shape, List<EtlFieldMeta> candidates) {
        var byName = indexed(candidates); var fields = new ArrayList<EtlFieldMeta>();
        shape.forEach((name, type) -> {
            var prior = byName.get(name); var field = prior == null ? new EtlFieldMeta(name, type) : prior.copy();
            if (prior != null && (type == null || prior.getDataType() == null || !type.equalsIgnoreCase(prior.getDataType()))) clearConstraints(field);
            field.setDataType(type); fields.add(field);
        });
        return fields;
    }

    /** 输出字段表示本次写入的目标列契约，未选中的列不伪装成已写入字段。 */
    private static List<EtlFieldMeta> sink(EtlNodeDef node, List<EtlFieldMeta> inputs, List<EtlFieldMeta> targets, EtlSchemaResult result) {
        var source = indexed(inputs); var target = indexed(targets); var mappings = JSONUtil.parseObj(node.getNodeJson()).getJSONArray("fieldMappings");
        var output = new ArrayList<EtlFieldMeta>(); var seen = new HashSet<String>();
        if (mappings == null) {
            for (var input : inputs) appendSink(node, input, input.getName(), target, seen, output, result);
        } else for (Object item : mappings) {
            var mapping = JSONUtil.parseObj(item); if (!mapping.getBool("enabled", true)) continue;
            appendSink(node, source.get(mapping.getStr("source")), mapping.getStr("target"), target, seen, output, result);
        }
        if (output.isEmpty()) result.getErrors().add("节点【" + node.getName() + "】没有可证明的目标写入字段");
        return output;
    }

    private static void appendSink(EtlNodeDef node, EtlFieldMeta source, String name, Map<String, EtlFieldMeta> targets,
            Set<String> seen, List<EtlFieldMeta> output, EtlSchemaResult result) {
        if (source == null || name == null || !targets.containsKey(name) || !seen.add(name)) {
            result.getErrors().add("节点【" + node.getName() + "】字段映射引用不存在或重复的来源/目标字段"); return;
        }
        var field = targets.get(name).copy(); field.setSourceName(node.getName()); output.add(field);
    }

    private static String origin(EtlNodeDef node, String target, List<EtlFieldMeta> inputs) {
        var config = JSONUtil.parseObj(node.getNodeJson()); String code = node.getToolCode(); var byName = indexed(inputs);
        if (code == null) return null;
        if (Set.of("add-constant", "id-generator").contains(code)) {
            boolean generated = "id-generator".equals(code) ? target.equals(config.getStr("targetField"))
                    : config.getJSONArray("fields") != null && config.getJSONArray("fields").stream().anyMatch(item -> target.equals(JSONUtil.parseObj(item).getStr("name")));
            if (generated) return node.getName();
        }
        var rules = config.getJSONArray("rules");
        if (Set.of("set-field-value", "string-operation", "string-replace").contains(code) && rules != null) for (Object item : rules) {
            var rule = JSONUtil.parseObj(item); String source = rule.getStr("sourceField", rule.getStr("inputField"));
            String output = rule.getStr("targetField", rule.getStr("outputField", source)); if (output == null || output.isBlank()) output = source;
            if (target.equals(output)) return !target.equals(source) ? node.getName() : byName.containsKey(source) ? byName.get(source).getSourceName() : null;
        }
        if ("field-split".equals(code) && config.getJSONArray("outputs") != null
                && config.getJSONArray("outputs").stream().anyMatch(item -> target.equals(JSONUtil.parseObj(item).getStr("name")))) return node.getName();
        if (Set.of("field-derive", "value-map", "number-range", "split-to-rows").contains(code)
                && target.equals(config.getStr("targetField", config.getStr("sourceField")))) return node.getName();
        if ("column-map".equals(code)) {
            var selections = config.getJSONArray("fieldSelections");
            if ("FIELDS".equals(config.getStr("columnMappingMode")) && selections != null) for (Object item : selections) {
                var rule = JSONUtil.parseObj(item); String source = rule.getStr("name"), alias = rule.getStr("alias", source);
                if (alias == null || alias.isBlank()) alias = source;
                if (target.equals(alias) && !target.equals(source)) return node.getName();
            }
            if (!byName.containsKey(target)) return node.getName();
        }
        if ("row-column".equals(code) && config.getInt("rowColumnVersion", 1) == 2) {
            var mappings = config.getJSONArray("COL2ROW".equals(config.getStr("mode", "COL2ROW")) ? "unpivotMappings" : "pivotMappings");
            if (mappings != null && mappings.stream().anyMatch(item -> target.equals(JSONUtil.parseObj(item).getStr("targetField")))) return node.getName();
        }
        if ("row-column".equals(code) && (target.equals(config.getStr("keyField", "key")) || target.equals(config.getStr("valueField", "value")))) return node.getName();
        return byName.containsKey(target) ? byName.get(target).getSourceName() : node.getName();
    }

    private static boolean knownGeneric(String code) { return code != null && Set.of("read-db", "column-map", "add-constant", "string-operation", "string-replace", "set-field-value", "field-split", "data-filter", "data-condition", "sort-records").contains(code); }
    private static LinkedHashMap<String, EtlFieldMeta> indexed(List<EtlFieldMeta> fields) { var map = new LinkedHashMap<String, EtlFieldMeta>(); fields.forEach(field -> map.put(field.getName(), field)); return map; }
    private static void clearConstraints(EtlFieldMeta field) { field.setNullable(null); field.setLength(null); field.setPrecision(null); field.setScale(null); field.setTimezone(null); }
    private static String join(String left, String right) { return left == null ? right : right == null || left.equals(right) ? left : left + " | " + right; }
}
