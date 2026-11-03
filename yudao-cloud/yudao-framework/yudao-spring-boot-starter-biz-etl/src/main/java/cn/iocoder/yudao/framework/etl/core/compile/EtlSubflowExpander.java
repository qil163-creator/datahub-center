package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldContract;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.variable.EtlParameterDefinition;
import cn.iocoder.yudao.framework.etl.core.variable.EtlParameterValidator;

import java.util.*;
import java.util.regex.*;

/** 有界且无副作用的图组合。版本存储和授权由解析器负责。 */
public final class EtlSubflowExpander {

    public static final String CALL = "sub-pipeline", INPUT = "boundary-input", OUTPUT = "boundary-output", BRIDGE = "boundary-bridge";

    private static final Set<String> PURE = Set.of(INPUT, OUTPUT, CALL, "column-map", "data-filter", "data-clean",
            "data-merge",
            "data-desensitize", "data-condition", "add-constant", "row-column", "sort-records", "field-split",
            "calculator",
            "value-map", "number-range", "split-to-rows", "flatten-row", "sort-merge", "multi-join",
            "cartesian-product");

    private static final Pattern PARAM = Pattern.compile("\\$\\{\\s*params\\.([^}]+)}");

    public record Definition(String key, String contentHash, List<EtlNodeDef> nodes, List<EtlEdgeDef> edges,
                             Set<String> parameters, List<EtlParameterDefinition> parameterDefinitions) {

        /**
         * 使用给定参数初始化 {@code Definition}。
         */
        public Definition(String key, String contentHash, List<EtlNodeDef> nodes, List<EtlEdgeDef> edges,
                Set<String> parameters) {
            this(key, contentHash, nodes, edges, parameters, List.of());
        }

        /**
         * 初始化 {@code Definition}。
         */
        public Definition {
            parameterDefinitions = parameterDefinitions == null ? List.of() : List.copyOf(parameterDefinitions);
        }
    }

    public record Dependency(String key, String contentHash) {

    }

    public record Origin(String callPath, String originalNodeNo, String definitionKey, String designNodeNo) {

    }

    public record Result(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges, List<Dependency> dependencies,
                         Map<String, Origin> sourceMap) {

    }

    public interface Resolver {

        /**
         * 根据当前配置和输入类型解析匹配的实现或定义。
         */
        Definition resolve(String pipelineId, String versionId);
    }

    private record Level(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges, Map<String, String> inputs,
                         Map<String, String> outputs) {

    }

    private final Resolver resolver;

    private final boolean allowUnresolvedBindings;

    private final List<Dependency> dependencies = new ArrayList<>();

    private final Map<String, Origin> origins = new LinkedHashMap<>();

    private int expandedCount;

    /**
     * 校验构造参数并初始化 {@code EtlSubflowExpander}。
     */
    private EtlSubflowExpander(Resolver resolver, boolean allowUnresolvedBindings) {
        this.resolver = Objects.requireNonNull(resolver);
        this.allowUnresolvedBindings = allowUnresolvedBindings;
    }

    /**
     * 根据绑定上下文展开当前 ETL 子流程。
     */
    public static Result expand(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges, Resolver resolver) {
        return expand(nodes, edges, resolver, true);
    }

    /** 执行调用方先绑定父级参数值，并拒绝未经证明的子级参数类型。 */
    public static Result expand(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges, Resolver resolver,
            boolean allowUnresolvedBindings) {
        var compiler = new EtlSubflowExpander(resolver, allowUnresolvedBindings);
        boolean definition = isDefinition(nodes);
        if (definition) {
            validateDefinition(nodes, edges);
        }
        var level = compiler.level(nodes, edges, "", "", "", new LinkedHashSet<>(), null, 0);
        EtlDag.build(level.nodes(), level.edges());
        return new Result(List.copyOf(level.nodes()), List.copyOf(level.edges()), List.copyOf(compiler.dependencies),
                Map.copyOf(compiler.origins));
    }

    /**
     * 判断定义。
     */
    public static boolean isDefinition(List<EtlNodeDef> nodes) {
        return nodes.stream().anyMatch(n -> INPUT.equals(n.getToolCode()) || OUTPUT.equals(n.getToolCode()));
    }

    /**
     * 校验定义。
     */
    public static void validateDefinition(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges) {
        if (nodes == null || nodes.isEmpty() || nodes.size() > 32 || edges == null || edges.size() > 128) {
            fail("子流程定义超过图大小限制");
        }
        EtlDag.buildLenient(nodes, edges).topologicalSort();
        if (edges.stream().map(e -> e.getSourceNo() + "\u0000" + e.getTargetNo()).distinct().count() != edges.size()) {
            fail("同一对设计节点之间只允许一条连线");
        }
        Set<String> inputs = new HashSet<>(), outputs = new HashSet<>();
        for (var node : nodes) {
            if (!PURE.contains(node.getToolCode()) || node.isFailGoOn()) {
                fail("子流程只允许无副作用转换，禁止忽略失败");
            }
            var config = JSONUtil.parseObj(node.getNodeJson());
            int expectedType = INPUT.equals(node.getToolCode()) ? 1 : OUTPUT.equals(node.getToolCode()) ? 3 : 2;
            if (!Objects.equals(node.getToolType(), expectedType)) {
                fail("子流程节点类型与组件不一致");
            }
            if ("cartesian-product".equals(node.getToolCode()) && config.getBool("prefixFields", true)
                    || "data-merge".equals(node.getToolCode()) && config.getBool("addSourceTag", false)) {
                fail("子流程不能使用节点编号生成字段前缀或来源标签");
            }
            if (INPUT.equals(node.getToolCode()) || OUTPUT.equals(node.getToolCode())) {
                String port = config.getStr("portName", "");
                if (!port.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")) {
                    fail("边界端口名称须为 1–32 位字母、数字、下划线或短横线");
                }
                if (!(INPUT.equals(node.getToolCode()) ? inputs : outputs).add(port)) {
                    fail("子流程边界端口重名");
                }
                var fields = config.getJSONArray("fields");
                if (fields == null || fields.isEmpty() || fields.size() > 256) {
                    fail("边界需要 1–256 个字段契约");
                }
                EtlFieldContract.parseFields(fields);
                if (INPUT.equals(node.getToolCode()) && edges.stream()
                        .anyMatch(e -> node.getNo().equals(e.getTargetNo()))
                        || OUTPUT.equals(node.getToolCode()) && edges.stream()
                        .anyMatch(e -> node.getNo().equals(e.getSourceNo()))) {
                    fail("子流程输入不能有上游，输出不能有下游");
                }
            }
        }
        if (inputs.isEmpty() || outputs.isEmpty()) {
            fail("子流程至少需要一个输入边界和一个输出边界");
        }
        if (inputs.stream().anyMatch(outputs::contains)) {
            fail("子流程输入与输出端口不能使用相同名称");
        }
        if (edges.stream().anyMatch(EtlEdgeDef::isControl)) {
            fail("子流程仅允许数据连线");
        }
        Set<String> reached = new HashSet<>(), consumed = new HashSet<>();
        nodes.forEach(n -> {
            if (INPUT.equals(n.getToolCode())) {
                reached.add(n.getNo());
            }
            if (OUTPUT.equals(n.getToolCode())) {
                consumed.add(n.getNo());
            }
        });
        boolean changed;
        do {
            changed = false;
            for (var edge : edges) {
                if (reached.contains(edge.getSourceNo())) {
                    changed |= reached.add(edge.getTargetNo());
                }
                if (consumed.contains(edge.getTargetNo())) {
                    changed |= consumed.add(edge.getSourceNo());
                }
            }
        } while (changed);
        if (nodes.stream().anyMatch(n -> !reached.contains(n.getNo()) || !consumed.contains(n.getNo()))) {
            fail("子流程每个节点必须位于输入至输出的路径上");
        }
    }

    /**
     * 返回当前级别。
     */
    private Level level(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges, String path, String definitionKey,
            String designNodeNo,
            Set<String> stack, Map<String, String> bindings, int depth) {
        if (depth > 3 || nodes.size() > 32 || edges.size() > 128) {
            fail("子流程最多嵌套 3 层，展开最多 32 个节点、128 条边");
        }
        EtlDag.buildLenient(nodes, edges).topologicalSort();
        if (edges.stream().map(e -> e.getSourceNo() + "\u0000" + e.getTargetNo()).distinct().count() != edges.size()) {
            fail("同一对设计节点之间只允许一条连线");
        }
        List<EtlNodeDef> expanded = new ArrayList<>();
        List<EtlEdgeDef> links = new ArrayList<>();
        Map<String, String> identifiers = new LinkedHashMap<>();
        Map<String, Level> calls = new LinkedHashMap<>();
        Map<String, String> inputs = new LinkedHashMap<>(), outputs = new LinkedHashMap<>();
        for (var original : nodes) {
            if (original.getNo().length() > 128) {
                fail("节点编号不能超过 128 字符");
            }
            var config = JSONUtil.parseObj(bind(JSONUtil.parseObj(original.getNodeJson()), bindings));
            if (CALL.equals(original.getToolCode())) {
                String pipeline = config.getStr("pipelineId", ""), version = config.getStr("versionId", "");
                if (!pipeline.matches("[1-9][0-9]*") || !version.matches("[1-9][0-9]*")) {
                    fail("子流程必须引用已发布的确定版本");
                }
                Definition child = Objects.requireNonNull(resolver.resolve(pipeline, version),
                        "subflow definition required");
                if (!stack.add(child.key())) {
                    fail("子流程版本存在递归引用");
                }
                validateDefinition(child.nodes(), child.edges());
                Map<String, String> params = new LinkedHashMap<>();
                var supplied = config.getJSONObject("parameters");
                if (supplied != null) {
                    supplied.forEach((key, value) -> {
                        if (!(value instanceof String)) {
                            fail("子流程参数值必须为字符串");
                        }
                        params.put(key, (String) value);
                    });
                }
                if (!params.keySet().equals(child.parameters())) {
                    fail("子流程必须显式绑定全部参数，且不能包含未声明参数");
                }
                if (!child.parameterDefinitions().isEmpty()) {
                    EtlParameterValidator.validateDefinitions(child.parameterDefinitions(), false);
                    if (!child.parameterDefinitions().stream().map(EtlParameterDefinition::name)
                            .collect(java.util.stream.Collectors.toSet()).equals(child.parameters())) {
                        fail("子流程参数类型契约与参数名称不一致");
                    }
                    for (var parameter : child.parameterDefinitions()) {
                        String value = params.get(parameter.name());
                        if (allowUnresolvedBindings && value.contains("${")) {
                            continue;
                        }
                        params.put(parameter.name(),
                                EtlParameterValidator.resolve(List.of(parameter), Map.of(parameter.name(), value),
                                                EtlParameterValidator.DefaultsPolicy.REQUIRE_ALL_EXPLICIT)
                                        .get(parameter.name()));
                    }
                }
                dependencies.add(new Dependency(child.key(), child.contentHash()));
                var nested = level(child.nodes(), child.edges(),
                        path.isEmpty() ? original.getNo() : path + "/" + original.getNo(), child.key(),
                        depth == 0 ? original.getNo() : designNodeNo, stack, params, depth + 1);
                stack.remove(child.key());
                calls.put(original.getNo(), nested);
                expanded.addAll(nested.nodes());
                links.addAll(nested.edges());
                for (String port : nested.inputs().keySet()) {
                    if (edges.stream()
                            .filter(e -> original.getNo().equals(e.getTargetNo()) && port.equals(e.getInputPort()))
                            .count() != 1) {
                        fail("子流程每个输入端口必须且只能连接一次：" + port);
                    }
                }
                for (String port : nested.outputs().keySet()) {
                    if (edges.stream().noneMatch(
                            e -> original.getNo().equals(e.getSourceNo()) && port.equals(e.resolveOutputPort()))) {
                        fail("子流程输出端口必须连接下游：" + port);
                    }
                }
            } else {
                String id = path.isEmpty() ? original.getNo() : "sf-" + DigestUtil.sha256Hex(
                        path + "\u0000" + original.getNo()).substring(0, 48);
                identifiers.put(original.getNo(), id);
                EtlNodeDef copy = new EtlNodeDef();
                copy.setNo(id);
                copy.setName(path.isEmpty() ? original.getName() : truncate(path + " / " + original.getName(), 200));
                copy.setToolCode(original.getToolCode());
                copy.setToolType(original.getToolType());
                copy.setFailGoOn(original.getFailGoOn());
                copy.setOverTimeMinutes(original.getOverTimeMinutes());
                copy.setNodeJson(config.toString());
                if (INPUT.equals(original.getToolCode()) || OUTPUT.equals(original.getToolCode())) {
                    (INPUT.equals(original.getToolCode()) ? inputs : outputs).put(config.getStr("portName"), id);
                    if (!path.isEmpty()) {
                        copy.setToolCode(BRIDGE);
                        copy.setToolType(EtlNodeDef.TOOL_TYPE_TRANSFORM);
                    }
                }
                if (++expandedCount > 32) {
                    fail("子流程展开后节点数超过 32");
                }
                expanded.add(copy);
                origins.put(id, new Origin(path, original.getNo(), definitionKey,
                        path.isEmpty() ? original.getNo() : designNodeNo));
            }
        }
        for (var edge : edges) {
            var source = calls.get(edge.getSourceNo());
            var target = calls.get(edge.getTargetNo());
            if ((source != null || target != null) && edge.isControl()) {
                fail("子流程调用不接受控制连线");
            }
            String from = source == null ? identifiers.get(edge.getSourceNo()) : source.outputs()
                    .get(edge.resolveOutputPort());
            String to = target == null ? identifiers.get(edge.getTargetNo()) : target.inputs().get(edge.getInputPort());
            if (from == null || to == null) {
                fail("子流程调用端口不存在");
            }
            EtlEdgeDef copy = new EtlEdgeDef();
            copy.setSourceNo(from);
            copy.setTargetNo(to);
            copy.setEdgeType(edge.getEdgeType());
            copy.setOutputPort(source == null ? edge.resolveOutputPort() : "main");
            copy.setInputPort(target == null ? edge.getInputPort() : "in");
            links.add(copy);
        }
        if (links.size() > 128) {
            fail("子流程展开后连线数超过 128");
        }
        return new Level(expanded, links, inputs, outputs);
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    private static Object bind(Object value, Map<String, String> bindings) {
        if (bindings == null) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), bind(item, bindings)));
            return result;
        }
        if (value instanceof Collection<?> list) {
            return list.stream().map(item -> bind(item, bindings)).toList();
        }
        if (!(value instanceof String text)) {
            return value;
        }
        Matcher matcher = PARAM.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String replacement = bindings.get(name);
            if (replacement == null) {
                fail("未绑定子流程参数：" + name);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 按指定长度截断文本。
     */
    private static String truncate(String text, int length) {
        return text.length() > length ? text.substring(0, length) : text;
    }

    /**
     * 抛出表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }
}
