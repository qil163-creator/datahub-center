package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** 将已支持的 ETL 表处理语义编译为 Spark/Flink 原生 SQL；不执行 JDBC、不解析平台凭据。 */
public final class EtlNativeSqlCompiler {
    public enum Engine {SPARK, FLINK}
    public record Table(List<String> path, Map<String, String> columns, ProductTypeEnum product,
                        String schema, String physicalTable, boolean continuousSource, JdbcUpsertSink jdbcUpsertSink) {
        public Table(List<String> path, Map<String, String> columns, ProductTypeEnum product, String schema, String physicalTable, boolean continuousSource) {
            this(path, columns, product, schema, physicalTable, continuousSource, null);
        }
        public Table(List<String> path, Map<String, String> columns, ProductTypeEnum product, String schema, String physicalTable) {
            this(path, columns, product, schema, physicalTable, false);
        }
    }
    /** 仅 Tables 授权解析器产生；用户节点字段不能声明受管目录、安装状态或数据库主键事实。 */
    public record JdbcUpsertSink(List<String> primaryKeys) {
        public JdbcUpsertSink {
            primaryKeys = List.copyOf(primaryKeys);
            if (primaryKeys.isEmpty() || primaryKeys.stream().distinct().count() != primaryKeys.size()) throw invalid("受管增量输出缺少确定主键");
        }
    }
    @FunctionalInterface public interface Tables {
        Table resolve(Long databaseId, String tableName, String schema);
        default Set<String> managedFunctions() { return Set.of(); }
        default Set<String> managedSecretRefs() { return Set.of(); }
    }
    /** 函数白名单由管理员受管环境提供，不能从流程节点配置自行声明安装状态。 */
    public static Tables withManagedFunctions(Tables tables, Set<String> functions) {
        Set<String> enabled = functions == null ? Set.of() : Set.copyOf(functions);
        return new Tables() {
            @Override public Table resolve(Long databaseId, String tableName, String schema) { return tables.resolve(databaseId, tableName, schema); }
            @Override public Set<String> managedFunctions() { return enabled; }
            @Override public Set<String> managedSecretRefs() { return tables.managedSecretRefs(); }
        };
    }
    /** 密钥引用也只由管理员授权绑定提供，不从流程 JSON 取得授权名单。 */
    public static Tables withManagedSecretRefs(Tables tables, Set<String> references) {
        Set<String> allowed = references == null ? Set.of() : Set.copyOf(references);
        return new Tables() {
            @Override public Table resolve(Long databaseId, String tableName, String schema) { return tables.resolve(databaseId, tableName, schema); }
            @Override public Set<String> managedFunctions() { return tables.managedFunctions(); }
            @Override public Set<String> managedSecretRefs() { return allowed; }
        };
    }
    /** 字段形状与原生 SQL 使用同一编译过程，不触发任务执行或数据库查询。 */
    public record NodeFields(Map<String, String> inputFields, Map<String, String> outputFields,
                             Map<String, Map<String, String>> outputPorts) {
        public NodeFields(Map<String, String> inputFields, Map<String, String> outputFields) { this(inputFields, outputFields, Map.of()); }
    }
    public record Analysis(String sql, Map<String, NodeFields> nodes) { }
    /** 授权后的可信分区批次 stage 扩展；普通 SQL 调用方不提供此能力，仍拒绝用户脚本。 */
    public interface ScriptStages {
        ScriptTable materialize(String nodeId, String toolCode, String nodeJson, String inputSql, Map<String, String> inputFields);
        void insert(String sql);
    }
    public record ScriptTable(String alias, Map<String, String> columns) {
        public ScriptTable {
            if (alias == null || !alias.matches("dh_native_script_[a-f0-9]{24}") || columns == null || columns.isEmpty() || columns.size() > 256) throw invalid("受管脚本 stage 的字段或视图合同无效");
            columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        }
    }
    private record Query(String sql, LinkedHashMap<String, String> columns, String outputOrder) {
        private Query(String sql, LinkedHashMap<String, String> columns) { this(sql, columns, ""); }
        private Query {
            if (sql.length() > 524288 || columns.size() > 5000) throw invalid("原生 SQL 中间计划超过大小限制，请拆分流程");
        }
    }
    private record Output(String node, String port) { }
    private EtlNativeSqlCompiler() { }

    /** 批处理多输出分别写入；Flink 多输出合并为同一个作业，避免无界流阻塞后续输出。 */
    public static String compile(Engine engine, boolean streaming, List<EtlNodeDef> nodes, List<EtlEdgeDef> edges, Tables tables) {
        return analyze(engine, streaming, nodes, edges, tables).sql();
    }

    /** 仅生成受限 SQL 与字段形状，所有访问仍由调用方提供的 Tables 解析器授权。 */
    public static Analysis analyze(Engine engine, boolean streaming, List<EtlNodeDef> nodes, List<EtlEdgeDef> edges, Tables tables) {
        return analyzeWithScriptStages(engine, streaming, nodes, edges, tables, null);
    }

    /** 用户源码仍不在本 JVM 中编译或执行；扩展只登记由可信原生 launcher 执行的 SQL 与 RPC stage。 */
    public static Analysis analyzeWithScriptStages(Engine engine, boolean streaming, List<EtlNodeDef> nodes, List<EtlEdgeDef> edges, Tables tables, ScriptStages scripts) {
        var fieldShapes = new LinkedHashMap<String, NodeFields>();
        if (nodes.isEmpty() || nodes.size() > 64) throw invalid("原生 SQL 流程需要 1 至 64 个节点");
        Map<String, EtlNodeDef> byId = new LinkedHashMap<>(); Map<String, List<EtlEdgeDef>> parents = new LinkedHashMap<>();
        for (var node : nodes) {
            if (byId.put(node.getNo(), node) != null) throw invalid("节点编号重复");
            if (node.isFailGoOn() || node.getOverTimeMinutes() != null && node.getOverTimeMinutes() > 0)
                throw invalid("原生 SQL 链暂不支持节点级失败继续或独立超时");
            parents.put(node.getNo(), new ArrayList<>());
        }
        for (var edge : edges) {
            if (edge.isControl()) throw invalid("原生 SQL 流程只支持数据边");
            if (!parents.containsKey(edge.getTargetNo()) || !parents.containsKey(edge.getSourceNo())) throw invalid("连线引用不存在的节点");
            parents.get(edge.getTargetNo()).add(edge);
        }
        for (var node : nodes) if ("id-generator".equals(node.getToolCode())) {
            var outgoing = edges.stream().filter(edge -> node.getNo().equals(edge.getSourceNo())).toList();
            if (outgoing.size() != 1 || !"insert-db".equals(byId.get(outgoing.getFirst().getTargetNo()).getToolCode()))
                throw invalid("ID 生成器当前必须直接连接唯一表输出，不能跨分支重复计算");
        }
        var queries = new HashMap<Output, Query>(); var finished = new HashSet<String>(); var statements = new ArrayList<String>();
        while (finished.size() < nodes.size()) {
            int before = finished.size();
            for (var node : nodes) {
                if (finished.contains(node.getNo()) || !parents.get(node.getNo()).stream().allMatch(edge -> finished.contains(edge.getSourceNo()))) continue;
                var config = JSONUtil.parseObj(node.getNodeJson());
                var upstream = parents.get(node.getNo());
                try {
                    boolean script = scripts != null && Set.of("java-script", "custom-token").contains(node.getToolCode());
                    if (!script && !"boundary-bridge".equals(node.getToolCode()) && !EtlNativeSqlCapabilities.componentLimitations(engine, streaming).containsKey(node.getToolCode()))
                        throw invalid("组件尚未提供原生 SQL 编译器：" + node.getToolCode());
                    if ("read-db".equals(node.getToolCode())) {
                        if (!upstream.isEmpty()) throw invalid("表输入不能有数据上游");
                        queries.put(new Output(node.getNo(), "main"), read(engine, streaming, config, tables));
                    } else {
                        var inputs = upstream.stream().map(edge -> {
                            var query = queries.get(new Output(edge.getSourceNo(), edge.resolveOutputPort()));
                            if (query == null) throw invalid("上游输出端口不存在：" + edge.resolveOutputPort());
                            return query;
                        }).toList();
                        if ("multi-join".equals(node.getToolCode())) {
                            var joinedInputs = inputs;
                            if (config.containsKey("leftNodeNo") || config.containsKey("rightNodeNo")) {
                                if (inputs.size() != 2) throw invalid("显式A/B来源仅适用于两个已连接的数据上游");
                                String left = required(config, "leftNodeNo"), right = required(config, "rightNodeNo");
                                var ids = upstream.stream().map(EtlEdgeDef::getSourceNo).toList();
                                if (left.equals(right) || !ids.contains(left) || !ids.contains(right)) throw invalid("Join A/B来源必须为不同的已连接上游");
                                joinedInputs = List.of(inputs.get(ids.indexOf(left)), inputs.get(ids.indexOf(right)));
                            }
                            queries.put(new Output(node.getNo(), "main"), join(engine, streaming, config, joinedInputs));
                        } else {
                            if (inputs.size() != 1) throw invalid("当前节点需要唯一数据上游");
                            var input = inputs.getFirst();
                            if ("insert-db".equals(node.getToolCode())) {
                                if (edges.stream().anyMatch(edge -> node.getNo().equals(edge.getSourceNo()))) throw invalid("表输出不能连接下游");
                                String statement = write(engine, streaming, config, input, tables); statements.add(statement); if (scripts != null) scripts.insert(statement);
                            } else if (script) {
                                var output = scripts.materialize(node.getNo(), node.getToolCode(), node.getNodeJson(), input.sql(), Collections.unmodifiableMap(input.columns()));
                                queries.put(new Output(node.getNo(), "main"), new Query("SELECT " + String.join(", ", output.columns().keySet().stream().map(name -> quote(engine, name)).toList())
                                        + " FROM " + quote(engine, output.alias()), new LinkedHashMap<>(output.columns())));
                            } else if ("data-condition".equals(node.getToolCode())) {
                                var branches = branches(engine, config, input);
                                for (var branch : branches.entrySet()) queries.put(new Output(node.getNo(), branch.getKey()), branch.getValue());
                                for (var edge : edges) if (node.getNo().equals(edge.getSourceNo()) && !branches.containsKey(edge.resolveOutputPort()))
                                    throw invalid("条件连线未绑定有效分支：" + edge.resolveOutputPort());
                                for (String branch : branches.keySet()) if (edges.stream().noneMatch(edge -> node.getNo().equals(edge.getSourceNo()) && branch.equals(edge.resolveOutputPort())))
                                    throw invalid("条件分支尚未连接输出：" + branch);
                            } else queries.put(new Output(node.getNo(), "main"), transform(engine, streaming, node.getToolCode(), config,
                                    "data-clean".equals(node.getToolCode()) ? cleanInput(engine, config, input) : input, tables));
                        }
                    }
                } catch (IllegalArgumentException invalid) { throw invalid("节点【" + node.getName() + "】：" + invalid.getMessage()); }
                var inputShape = new LinkedHashMap<String, String>();
                for (var edge : upstream) {
                    var source = queries.get(new Output(edge.getSourceNo(), edge.resolveOutputPort()));
                    if (source == null) continue;
                    source.columns().forEach((name, type) -> {
                        if (!inputShape.containsKey(name)) inputShape.put(name, type);
                        else if (!Objects.equals(inputShape.get(name), type)) inputShape.put(name, null);
                    });
                }
                var outputShape = new LinkedHashMap<String, String>();
                if ("insert-db".equals(node.getToolCode())) {
                    var mappings = config.getJSONArray("fieldMappings");
                    if (mappings == null) outputShape.putAll(inputShape);
                    else for (Object item : mappings) {
                        var mapping = JSONUtil.parseObj(item);
                        if (mapping.getBool("enabled", true)) outputShape.put(mapping.getStr("target"), inputShape.get(mapping.getStr("source")));
                    }
                } else {
                    var produced = queries.get(new Output(node.getNo(), "main"));
                    if (produced != null) outputShape.putAll(produced.columns());
                }
                var outputPorts = new LinkedHashMap<String, Map<String, String>>();
                if ("data-condition".equals(node.getToolCode())) for (Object item : array(config, "branches", true)) {
                    String port = required(JSONUtil.parseObj(item), "id"); var produced = queries.get(new Output(node.getNo(), port));
                    if (produced == null) continue;
                    outputPorts.put(port, Collections.unmodifiableMap(new LinkedHashMap<>(produced.columns())));
                    produced.columns().forEach((name, type) -> {
                        if (!outputShape.containsKey(name)) outputShape.put(name, type);
                        else if (!Objects.equals(outputShape.get(name), type)) outputShape.put(name, null);
                    });
                }
                fieldShapes.put(node.getNo(), new NodeFields(Collections.unmodifiableMap(inputShape), Collections.unmodifiableMap(outputShape), Collections.unmodifiableMap(outputPorts)));
                finished.add(node.getNo());
            }
            if (before == finished.size()) throw invalid("流程包含循环依赖");
        }
        if (statements.isEmpty()) throw invalid("流程缺少表输出");
        for (var node : nodes) {
            if (!"insert-db".equals(node.getToolCode()) && edges.stream().noneMatch(edge -> node.getNo().equals(edge.getSourceNo())))
                throw invalid("存在未连接到输出的节点：" + node.getName());
        }
        String inserts = String.join(";\n", statements) + ";";
        if (inserts.length() > 1_000_000) throw invalid("编译后 SQL 超过允许大小，请拆分输出任务");
        return new Analysis(engine == Engine.FLINK && statements.size() > 1 ? "EXECUTE STATEMENT SET\nBEGIN\n" + inserts + "\nEND;" : inserts,
                Collections.unmodifiableMap(fieldShapes));
    }

    private static Map<String, Query> branches(Engine engine, JSONObject config, Query input) {
        requireUnorderedIntermediate(input);
        var rules = array(config, "branches", true); var outputs = new LinkedHashMap<String, Query>();
        var cases = new StringBuilder("CASE"); int index = 0; boolean hasElse = false;
        for (Object item : rules) {
            var rule = JSONUtil.parseObj(item); String id = required(rule, "id");
            if (outputs.containsKey(id)) throw invalid("分支编号重复");
            String type = rule.getStr("type", "IF");
            if (hasElse || !Set.of("IF", "ELSE").contains(type)) throw invalid("ELSE 分支必须唯一并位于最后");
            if ("ELSE".equals(type)) { cases.append(" ELSE ").append(index); hasElse = true; }
            else {
                var declared = rule.getJSONArray("conditions");
                var filters = new JSONArray();
                for (Object condition : declared == null ? List.of(rule) : array(rule, "conditions", true)) {
                var entry = JSONUtil.parseObj(condition);
                String operator = switch (required(entry, "operator")) {
                    case "EQ" -> "EQUALS"; case "NE" -> "NOT_EQUALS"; case "GT" -> "GREATER_THAN";
                    case "GTE" -> "GREATER_EQUAL"; case "LT" -> "LESS_THAN"; case "LTE" -> "LESS_EQUAL";
                    case "NOT_NULL" -> "IS_NOT_NULL"; case "IS_NULL" -> "IS_NULL";
                    case "CONTAINS" -> "CONTAINS"; case "NOT_CONTAINS" -> "NOT_CONTAINS";
                    default -> throw invalid("分支操作符无效");
                };
                filters.add(new JSONObject().set("fieldName", required(entry, "fieldName")).set("operator", operator).set("value", entry.get("value")));
                }
                var filter = new JSONObject().set("filterConditions", filters).set("logicalOperator", rule.getStr("conditionRelation", "AND"));
                cases.append(" WHEN ").append(conditions(engine, filter, input)).append(" THEN ").append(index);
            }
            outputs.put(id, input); ++index;
        }
        String unmatched = config.getStr("unmatchedPolicy", "DROP");
        if (!Set.of("DROP", "ERROR").contains(unmatched)) throw invalid("未命中处理方式无效");
        if ("ERROR".equals(unmatched) && !hasElse) throw invalid("原生条件分发的 ERROR 策略必须配置 ELSE 分支，不能静默丢弃未命中行");
        if (!hasElse) cases.append(" ELSE -1");
        cases.append(" END"); index = 0;
        for (Object item : rules) {
            var rule = JSONUtil.parseObj(item); String id = required(rule, "id"); var fields = rule.getJSONArray("fields");
            var selected = fields == null ? new ArrayList<>(input.columns().keySet()) : fields.toList(String.class);
            if (selected.isEmpty() || new HashSet<>(selected).size() != selected.size()) throw invalid("分支至少输出一个不重复字段");
            var types = new LinkedHashMap<String, String>(); selected.forEach(name -> types.put(field(input, name), input.columns().get(name)));
            outputs.put(id, new Query("SELECT " + (fields == null ? "*" : String.join(", ", selected.stream().map(name -> quote(engine, name)).toList()))
                    + " FROM (" + input.sql() + ") dh_branch WHERE " + cases + " = " + index++, types));
        }
        return outputs;
    }

    private static Query join(Engine engine, boolean streaming, JSONObject config, List<Query> inputs) {
        inputs.forEach(EtlNativeSqlCompiler::requireUnorderedIntermediate);
        if (streaming) throw invalid("实时 Join 需要事件时间、状态 TTL 和窗口边界，不能隐式生成无界状态");
        if (inputs.size() < 2 || inputs.size() > 8) throw invalid("Join 需要 2 至 8 个数据上游");
        String kind = config.getStr("joinType", "INNER");
        if (!Set.of("INNER", "LEFT").contains(kind)) throw invalid("Join 仅支持 INNER 或 LEFT");
        var rules = array(config, "joinConditions", false);
        if (rules.isEmpty()) { String key = required(config, "keyField"); rules.add(new JSONObject().set("leftField", key).set("rightField", key)); }
        int maximum = config.getInt("maxOutputRows", 1_000_000);
        if (maximum < 1 || maximum > 10_000_000) throw invalid("Join 最大输出行数应为 1 至 10000000");
        Query current = inputs.getFirst();
        for (int index = 1; index < inputs.size(); index++) {
            var right = inputs.get(index); var rule = JSONUtil.parseObj(rules.get(Math.min(index - 1, rules.size() - 1)));
            String leftField = field(current, required(rule, "leftField")), rightField = field(right, required(rule, "rightField"));
            String leftKey = "dh_left." + quote(engine, leftField), rightKey = "dh_right." + quote(engine, rightField);
            String predicate = "((" + leftKey + " IS NULL AND " + rightKey + " IS NULL) OR CAST(" + leftKey + " AS STRING) = CAST(" + rightKey + " AS STRING))";
            if (config.containsKey("conditionRelation")) {
                if (inputs.size() != 2) throw invalid("同组多条件Join仅适用于两个数据上游");
                String relation = config.getStr("conditionRelation"); if (!Set.of("AND", "OR").contains(relation)) throw invalid("Join条件关系无效");
                var conditions = new ArrayList<String>();
                for (Object entry : rules) conditions.add(joinCondition(engine, JSONUtil.parseObj(entry), current, right));
                predicate = "(" + String.join(" " + relation + " ", conditions) + ")";
            }
            var columns = new LinkedHashMap<>(current.columns()); var projections = new ArrayList<String>();
            boolean explicit = config.containsKey("leftFields") || config.containsKey("rightFields");
            if (explicit) {
                if (inputs.size() != 2) throw invalid("独立左右输出投影仅适用于两个数据上游");
                columns.clear(); joinProjection(engine, config, "leftFields", current, "dh_left", columns, projections);
                joinProjection(engine, config, "rightFields", right, "dh_right", columns, projections);
                if (columns.isEmpty()) throw invalid("Join至少输出一个字段");
            }
            for (String name : explicit ? List.<String>of() : current.columns().keySet()) {
                String value = "dh_left." + quote(engine, name);
                if (right.columns().containsKey(name)) {
                    if (!sameFamily(current.columns().get(name), right.columns().get(name))) throw invalid("Join 同名字段类型不兼容，请先重命名：" + name);
                    value = "COALESCE(" + value + ", dh_right." + quote(engine, name) + ")";
                }
                projections.add(value + " AS " + quote(engine, name));
            }
            for (var entry : right.columns().entrySet()) if (!explicit && !columns.containsKey(entry.getKey())) {
                columns.put(entry.getKey(), entry.getValue()); projections.add("dh_right." + quote(engine, entry.getKey()) + " AS " + quote(engine, entry.getKey()));
            }
            current = new Query("SELECT " + String.join(", ", projections) + " FROM (" + current.sql() + ") dh_left " + kind
                    + " JOIN (" + right.sql() + ") dh_right ON " + predicate, columns);
            current = joinBudget(engine, current, maximum);
        }
        return current;
    }

    private static void joinProjection(Engine engine, JSONObject config, String key, Query input, String qualifier,
                                       LinkedHashMap<String, String> types, List<String> expressions) {
        var configured = config.getJSONArray(key);
        var fields = configured == null ? new JSONArray(input.columns().keySet().stream().map(name -> Map.of("name", name)).toList()) : array(config, key, false);
        for (Object item : fields) {
            var rule = JSONUtil.parseObj(item); String source = field(input, required(rule, "name"));
            String target = rule.getStr("alias", source); if (target.isBlank()) target = source;
            String type = columnType(rule, input.columns().get(source));
            if (types.putIfAbsent(target, type) != null) throw invalid("Join输出别名重复：" + target);
            String expression = qualifier + "." + quote(engine, source);
            if (!type.equalsIgnoreCase(input.columns().get(source))) expression = columnCast(engine, expression, type);
            expressions.add(expression + " AS " + quote(engine, target));
        }
    }

    private static String joinCondition(Engine engine, JSONObject rule, Query left, Query right) {
        String a = field(left, required(rule, "leftField")), b = field(right, required(rule, "rightField"));
        String x = "dh_left." + quote(engine, a), y = "dh_right." + quote(engine, b), op = rule.getStr("operator", "EQ");
        if (!sameFamily(left.columns().get(a), right.columns().get(b)) && !(numericType(left.columns().get(a)) && numericType(right.columns().get(b))))
            throw invalid("Join条件字段类型不兼容");
        if (Set.of("EQ", "NE").contains(op)) {
            String equal = "((" + x + " IS NULL AND " + y + " IS NULL) OR COALESCE(" + x + " = " + y + ", FALSE))";
            return op.equals("EQ") ? equal : "NOT " + equal;
        }
        requireNumeric(left, a); requireNumeric(right, b);
        return "(" + x + switch (op) { case "GT" -> " > "; case "GTE" -> " >= "; case "LT" -> " < "; case "LTE" -> " <= "; default -> throw invalid("Join条件运算符无效"); } + y + ")";
    }

    /** 字段集合和单层受限谓词复用DB Switch校验，转换之前先过滤输入行。 */
    private static Query cleanInput(Engine engine, JSONObject config, Query input) {
        String where = config.getStr("whereCondition", ""); if (where.isBlank()) return input;
        if (where.length() > 8000 || where.codePoints().anyMatch(Character::isISOControl) || where.contains("\"") || where.contains("`")
                || where.contains("\\") || engine == Engine.SPARK && (where.contains("''") || where.contains("${"))) throw invalid("清洗条件包含未适配的跨引擎表达式");
        RestrictedSelectGuard.restrictProjection(ProductTypeEnum.POSTGRESQL, "SELECT * FROM public.dh_clean_input WHERE " + where,
                "public", "dh_clean_input", new ArrayList<>(input.columns().keySet()), input.columns().keySet());
        return new Query("SELECT * FROM (" + input.sql() + ") dh_clean_filter WHERE " + where, new LinkedHashMap<>(input.columns()));
    }

    private static Query joinBudget(Engine engine, Query input, int maximum) {
        String marker = "dh_join_rownum"; while (input.columns().containsKey(marker)) marker += "_";
        String order = "CAST(" + quote(engine, input.columns().keySet().iterator().next()) + " AS STRING)";
        String rowNumber = quote(engine, marker);
        String failure = engine == Engine.SPARK ? "RAISE_ERROR('Join 最大输出行数超限')" : "CAST(CONCAT('Join 最大输出行数超限: ', CAST(" + rowNumber + " AS STRING)) AS BIGINT) = 0";
        return new Query("SELECT " + String.join(", ", input.columns().keySet().stream().map(name -> quote(engine, name)).toList())
                + " FROM (SELECT dh_join_input.*, ROW_NUMBER() OVER (ORDER BY " + order + ") AS " + rowNumber
                + " FROM (" + input.sql() + ") dh_join_input) dh_join_guard WHERE CASE WHEN " + rowNumber + " <= " + maximum
                + " THEN TRUE ELSE " + failure + " END", new LinkedHashMap<>(input.columns()));
    }

    private static boolean sameFamily(String left, String right) {
        return Objects.equals(left, right) || textType(left) && textType(right);
    }

    private static Query read(Engine engine, boolean streaming, JSONObject config, Tables tables) {
        EtlTableReadConfig.requireSupportedExecution(config);
        if (config.getInt("ifSql", 0) != 0) throw invalid("跨引擎表输入暂不翻译自定义数据库 SQL，请使用表和过滤组件");
        var table = table(config, tables);
        if (streaming && !table.continuousSource()) throw invalid("实时输入尚未绑定持续产出的 Catalog 源；有界 JDBC 全量表不能声明为持续采集");
        var columns = new LinkedHashMap<>(table.columns());
        var selected = config.getJSONArray("selectedColumns");
        if (selected != null) {
            var wanted = selected.toList(String.class);
            if (wanted.isEmpty() || !columns.keySet().containsAll(wanted) || wanted.stream().distinct().count() != wanted.size()) throw invalid("输入字段选择无效");
            columns.keySet().retainAll(wanted);
        }
        String where = config.getStr("whereCondition", "");
        if (!where.isBlank()) {
            if (where.codePoints().anyMatch(Character::isISOControl) || engine == Engine.SPARK && (where.contains("''") || where.contains("${")))
                throw invalid("原生 where 条件包含跨引擎不兼容的字符串，请使用过滤组件");
            if (where.contains("\"") || where.contains("`") || where.contains("\\")) throw invalid("条件请使用普通字段名和标准字符串，特殊名称请使用过滤组件");
            RestrictedSelectGuard.parseBound(table.product(), "SELECT * FROM " + table.product().quoteSchemaTableName(table.schema(), table.physicalTable()) + " WHERE " + where, table.schema());
        }
        return new Query("SELECT " + String.join(", ", columns.keySet().stream().map(name -> quote(engine, name)).toList())
                + " FROM " + path(engine, table.path()) + (where.isBlank() ? "" : " WHERE " + where), columns);
    }

    private static String write(Engine engine, boolean streaming, JSONObject config, Query input, Tables tables) {
        boolean upsert = config.getInt("dataSyncType", 1) == 2;
        if (config.getBool("targetTruncate", false) || !Set.of(1, 2).contains(config.getInt("dataSyncType", 1))
                || !Set.of("INSERT", "UPSERT").contains(config.getStr("writeMode", "INSERT").toUpperCase(Locale.ROOT))
                || !upsert && !"INSERT".equalsIgnoreCase(config.getStr("writeMode", "INSERT"))) throw invalid("当前只支持向已有原生表插入或经验证的实时主键更新，不能清空、建表或覆盖");
        if (upsert && (engine != Engine.FLINK || !streaming)) throw invalid("原生增量更新仅适用于经受管 JDBC 验证的 Flink 实时输出");
        if (!config.getStr("whereCondition", "").isBlank()) throw invalid("插入输出不能使用删除或覆盖条件");
        var target = table(config, tables); var mappings = config.getJSONArray("fieldMappings");
        var columns = new ArrayList<String>(); var projections = new ArrayList<String>();
        if (mappings == null) for (String field : input.columns().keySet()) {
            if (!target.columns().containsKey(field)) throw invalid("目标缺少字段：" + field);
            columns.add(quote(engine, field)); projections.add(quote(engine, field));
        } else for (Object value : mappings) {
            var mapping = JSONUtil.parseObj(value); if (!mapping.getBool("enabled", true)) continue;
            String from = mapping.getStr("source"), to = mapping.getStr("target");
            field(input, from); if (!target.columns().containsKey(to)) throw invalid("目标缺少映射字段：" + to);
            columns.add(quote(engine, to)); projections.add(quote(engine, from));
        }
        if (columns.isEmpty() || columns.stream().distinct().count() != columns.size()) throw invalid("请配置非空且不重复的输出映射");
        if (upsert) {
            if (target.jdbcUpsertSink() == null) throw invalid("目标尚未验证真实数据库主键与已安装 JDBC 增量输出目录");
            var declared = config.getJSONArray("upsertKeys");
            if (declared == null || declared.isEmpty() || declared.stream().anyMatch(value -> !(value instanceof String))) throw invalid("增量更新必须明确选择真实目标主键");
            var keys = declared.toList(String.class);
            if (keys.stream().distinct().count() != keys.size() || !new HashSet<>(keys).equals(new HashSet<>(target.jdbcUpsertSink().primaryKeys()))) throw invalid("增量更新主键必须与真实数据库及受管 Catalog 主键完整一致");
            if (!columns.containsAll(keys.stream().map(key -> quote(engine, key)).toList())) throw invalid("增量输出映射必须覆盖全部目标主键，不能禁用主键映射");
        } else if (config.getJSONArray("upsertKeys") != null && !config.getJSONArray("upsertKeys").isEmpty()) throw invalid("追加模式不接受增量更新主键");
        return "INSERT INTO " + path(engine, target.path()) + " (" + String.join(", ", columns) + ") SELECT "
                + String.join(", ", projections) + " FROM (" + input.sql() + ") dh_output" + input.outputOrder();
    }

    private static Query transform(Engine engine, boolean streaming, String code, JSONObject config, Query input, Tables context) {
        if (!"boundary-bridge".equals(code)) requireUnorderedIntermediate(input);
        Set<String> managedFunctions = context.managedFunctions();
        if ("calculator".equals(code)) return calculator(engine, config, input);
        var expressions = new LinkedHashMap<String, String>(); for (String name : input.columns().keySet()) expressions.put(name, quote(engine, name));
        if (cn.iocoder.yudao.framework.etl.core.executor.support.EtlFieldRules.supports(code)) {
            var plan = cn.iocoder.yudao.framework.etl.core.executor.support.EtlFieldRules.compile(code, config);
            plan.requiredFields().forEach(name -> field(input, name));
        }
        var types = new LinkedHashMap<>(input.columns()); String tail = ""; boolean distinct = false;
        switch (code) {
            case "boundary-bridge" -> EtlNativeBoundaryCompiler.validate(config, input.columns());
            case "data-encrypt", "data-decrypt" -> EtlNativeCryptoCompiler.apply(engine, "data-decrypt".equals(code), config, expressions, types, context.managedSecretRefs(), managedFunctions);
            case "column-map" -> {
                if ("FIELDS".equals(config.getStr("columnMappingMode"))) {
                    var renamed = new LinkedHashMap<String, String>(); var renamedTypes = new LinkedHashMap<String, String>();
                    var removed = array(config, "removedFields", false).toList(String.class);
                    removed.forEach(name -> field(input, name));
                    for (Object item : array(config, "fieldSelections", true)) {
                        var rule = JSONUtil.parseObj(item); String source = field(input, required(rule, "name"));
                        if (removed.contains(source)) continue;
                        String target = rule.getStr("alias", source); if (target.isBlank()) target = source;
                        String requestedType = columnType(rule, input.columns().get(source));
                        String expression = requestedType.equalsIgnoreCase(input.columns().get(source)) ? quote(engine, source)
                                : columnCast(engine, quote(engine, source), requestedType);
                        if (renamed.put(target, expression) != null) throw invalid("字段选择产生重复字段：" + target);
                        renamedTypes.put(target, requestedType);
                    }
                    if (renamed.isEmpty()) throw invalid("字段选择至少保留一个字段");
                    expressions = renamed; types = renamedTypes;
                } else {
                if (config.containsKey("fieldSelections") || config.containsKey("removedFields")) throw invalid("字段选择必须明确配置 FIELDS 模式");
                var rules = array(config, "regexColumnMapper", false); var renamed = new LinkedHashMap<String, String>(); var renamedTypes = new LinkedHashMap<String, String>();
                for (var entry : expressions.entrySet()) {
                    String name = entry.getKey(); boolean remove = false;
                    for (Object item : rules) {
                        var rule = JSONUtil.parseObj(item); var pattern = Pattern.compile(rule.getStr("fromPattern"));
                        if (!pattern.matcher(name).find()) continue;
                        String replacement = rule.getStr("toValue"); remove = replacement == null || replacement.isBlank();
                        if (!remove) name = pattern.matcher(name).replaceAll(replacement); break;
                    }
                    if (!remove) { if (renamed.put(name, entry.getValue()) != null) throw invalid("重命名产生重复字段"); renamedTypes.put(name, input.columns().get(entry.getKey())); }
                }
                expressions = renamed; types = renamedTypes;
                }
            }
            case "add-constant" -> {
                for (Object item : array(config, "fields", true)) {
                    var rule = JSONUtil.parseObj(item); String name = required(rule, "name"); String type = rule.getStr("fieldType", "String");
                    String value = rule.getBool("setEmpty", false) ? literal(engine, "") : constant(engine, rule.getStr("value"), type);
                    expressions.put(name, value); types.put(name, rule.getBool("setEmpty", false) ? "STRING" : constantType(type));
                }
            }
            case "set-field-value" -> {
                for (Object item : array(config, "rules", true)) { var rule = JSONUtil.parseObj(item); String source = required(rule, "sourceField"), target = required(rule, "targetField");
                    expressions.put(target, quote(engine, field(input, source))); types.put(target, input.columns().get(source)); }
            }
            case "string-operation", "string-replace" -> {
                // 与统一规则计划一致，每条规则读取同一原始输入行。
                for (Object item : array(config, "rules", true)) {
                    var rule = JSONUtil.parseObj(item); String source = field(input, required(rule, "inputField"));
                    String target = rule.getStr("outputField", ""); if (target.isBlank()) target = source;
                    String value = "CAST(" + quote(engine, source) + " AS STRING)";
                    if ("string-operation".equals(code)) {
                        value = trim(engine, value, rule.getStr("trim", "NONE"));
                        value = switch (rule.getStr("caseMode", "NONE")) { case "NONE" -> value; case "UPPER" -> "UPPER(" + value + ")"; case "LOWER" -> "LOWER(" + value + ")"; default -> throw invalid("暂不支持该大小写转换"); };
                        value = padding(engine, value, rule);
                    } else {
                        value = replace(engine, input, value, rule, managedFunctions);
                    }
                    expressions.put(target, value); types.put(target, "STRING");
                }
            }
            case "field-derive" -> {
                String target = required(config, "targetField"), value = derive(engine, config, input, managedFunctions);
                expressions.put(target, value); types.put(target, "STRING");
            }
            case "id-generator" -> {
                if ("SEQUENCE".equals(config.getStr("mode", "SEQUENCE"))) return sequence(engine, streaming, config, input);
                if (!"UUID".equals(config.getStr("mode"))) throw invalid("ID生成模式无效");
                String target = required(config, "targetField"); expressions.put(target, "UUID()"); types.put(target, "STRING");
            }
            case "row-column" -> {
                if (streaming && "ROW2COL".equals(config.getStr("mode", "COL2ROW"))) requireWindowFunction(engine, managedFunctions);
                return "ROW2COL".equals(config.getStr("mode", "COL2ROW")) ? pivot(engine, streaming, config, input) : unpivot(engine, config, input);
            }
            case "field-split" -> { return splitFields(engine, config, input, managedFunctions); }
            case "data-filter" -> tail = " WHERE " + conditions(engine, config, input);
            case "value-map" -> {
                String source = field(input, required(config, "sourceField")), target = config.getStr("targetField", source);
                if (target.isBlank()) target = source;
                String text = "COALESCE(CAST(" + quote(engine, source) + " AS STRING), '')"; StringBuilder value = new StringBuilder("CASE");
                var mappings = new LinkedHashMap<String, String>();
                for (Object item : array(config, "mappings", true)) { var rule = JSONUtil.parseObj(item); mappings.put(required(rule, "from"), rule.getStr("to")); }
                mappings.forEach((from, to) -> value.append(" WHEN ").append(text).append(" = ").append(literal(engine, from)).append(" THEN ").append(literal(engine, to)));
                String unmatched = config.getStr("unmatched", "KEEP");
                if ("KEEP".equals(unmatched) && !textType(input.columns().get(source))) throw invalid("保留原值的值映射要求字符串字段，不能把数值原值隐式转成字符串");
                value.append(" ELSE ").append(switch (unmatched) { case "KEEP" -> "CAST(" + quote(engine, source) + " AS STRING)"; case "SET_NULL" -> "NULL"; case "DEFAULT" -> literal(engine, config.getStr("defaultValue")); default -> throw invalid("未匹配处理方式无效"); }).append(" END");
                expressions.put(target, value.toString()); types.put(target, "STRING");
            }
            case "number-range" -> {
                String source = field(input, required(config, "sourceField")), target = config.getStr("targetField", source); if (target.isBlank()) target = source;
                requireNumeric(input, source); StringBuilder value = new StringBuilder("CASE");
                for (Object item : array(config, "ranges", true)) { var rule = JSONUtil.parseObj(item); String min = rule.getStr("min"), max = rule.getStr("max");
                    value.append(" WHEN ").append(quote(engine, source)).append(" IS NOT NULL");
                    if (min != null && !min.isBlank()) value.append(" AND ").append(quote(engine, source)).append(" >= ").append(number(min));
                    if (max != null && !max.isBlank()) value.append(" AND ").append(quote(engine, source)).append(" < ").append(number(max));
                    value.append(" THEN ").append(literal(engine, rule.getStr("value"))); }
                value.append(" ELSE ").append(literal(engine, config.getStr("defaultValue"))).append(" END"); expressions.put(target, value.toString()); types.put(target, "STRING");
            }
            case "sort-records" -> {
                if (streaming) throw invalid("实时流的全局排序需要时间和窗口边界"); var sorts = new ArrayList<String>();
                for (Object item : array(config, "sorts", true)) { var rule = JSONUtil.parseObj(item); String order = rule.getStr("order", "ASC"); if (!Set.of("ASC", "DESC").contains(order)) throw invalid("排序方向无效");
                    sorts.add(quote(engine, field(input, rule.getStr("field"))) + " " + order + ("DESC".equals(order) ? " NULLS FIRST" : " NULLS LAST")); }
                tail = " ORDER BY " + String.join(", ", sorts);
            }
            case "data-clean" -> {
                if (!"LOCAL".equalsIgnoreCase(config.getStr("executionMode", "LOCAL")) || config.getBool("enableNullHandling", false)) throw invalid("治理清洗或空值策略尚未编译为原生 SQL");
                if (config.getBool("enableFieldRemoval", false)) for (String name : config.getStr("fieldsToRemove", "").split(",")) { expressions.remove(name.strip()); types.remove(name.strip()); }
                if (config.getBool("enableFormatting", false)) for (Object item : array(config, "formatRules", true)) { var rule = JSONUtil.parseObj(item); String name = required(rule, "fieldName");
                    if (!expressions.containsKey(name)) continue;
                    String text = "CAST(" + expressions.get(name) + " AS STRING)";
                    expressions.put(name, switch (rule.getStr("type")) { case "TRIM" -> splitTrim(engine, text, "BOTH"); case "UPPER_CASE" -> "UPPER(" + text + ")"; case "LOWER_CASE" -> "LOWER(" + text + ")"; default -> throw invalid("仅支持 TRIM/UPPER_CASE/LOWER_CASE 原生格式化"); }); types.put(name, "STRING"); }
                distinct = config.getBool("enableDeduplication", false);
                if (distinct) {
                    if (streaming) {
                        requireWindowFunction(engine, managedFunctions);
                        var projected = project(engine, input, expressions, types, "", false);
                        return new Query(EtlNativeStreamingWindow.deduplicate(config, projected.sql(), projected.columns()), projected.columns());
                    }
                    String strategy = config.getStr("deduplicationStrategy", "ALL_FIELDS");
                    if (!Set.of("ALL_FIELDS", "KEY_FIELDS").contains(strategy)) throw invalid("去重策略无效");
                    if ("ALL_FIELDS".equals(strategy) && !empty(config.get("keyFields"))) throw invalid("整行去重不能同时声明按键字段");
                    if ("KEY_FIELDS".equals(strategy) || config.getBool("ignoreCase", false)
                            || !empty(config.get("ignoreCaseFields")))
                        return deduplicateFirst(engine, config, project(engine, input, expressions, types, "", false), strategy);
                }
            }
            default -> throw invalid("组件尚未提供原生 SQL 编译器：" + code);
        }
        return project(engine, input, expressions, types, tail, distinct);
    }

    /** 计算器逐条投影，后续规则可以引用前序结果，避免同层 SQL 别名解析差异。 */
    private static Query calculator(Engine engine, JSONObject config, Query input) {
        Query current = input;
        var expressions = new LinkedHashMap<String, String>(); input.columns().keySet().forEach(name -> expressions.put(name, quote(engine, name)));
        var types = new LinkedHashMap<>(input.columns()); var pending = new HashSet<String>();
        for (Object item : array(config, "rules", true)) {
            var rule = JSONUtil.parseObj(item); String target = required(rule, "targetField");
            if (List.of("fieldA", "fieldB", "fieldC").stream().anyMatch(key -> pending.contains(rule.getStr(key, "")))) {
                current = project(engine, current, expressions, types, "", false);
                expressions.clear(); current.columns().keySet().forEach(name -> expressions.put(name, quote(engine, name)));
                types = new LinkedHashMap<>(current.columns()); pending.clear();
            }
            String operation = required(rule, "calcType").toUpperCase(Locale.ROOT);
            String a = quote(engine, field(current, required(rule, "fieldA")));
            String b = Set.of("ADD", "SUB", "MUL", "DIV", "ADD3", "MIN", "MAX", "MOD", "POWER", "ADD_DAYS", "DIFF_DAYS").contains(operation)
                    ? quote(engine, field(current, required(rule, "fieldB"))) : "NULL";
            String c = "ADD3".equals(operation) ? quote(engine, field(current, required(rule, "fieldC"))) : "NULL";
            String x = decimal(a), y = decimal(b), z = decimal(c), text = "CAST(" + a + " AS STRING)";
            String value = switch (operation) {
                case "ADD" -> "(" + x + " + " + y + ")";
                case "SUB" -> "(" + x + " - " + y + ")";
                case "MUL" -> "(" + x + " * " + y + ")";
                case "DIV" -> "(" + x + " / NULLIF(" + y + ", 0))";
                case "ADD3" -> "(" + x + " + " + y + " + " + z + ")";
                case "MIN", "MAX" -> "CASE WHEN " + x + " IS NULL OR " + y + " IS NULL THEN NULL ELSE "
                        + ("MIN".equals(operation) ? "LEAST" : "GREATEST") + "(" + x + ", " + y + ") END";
                case "ABS", "CEIL", "FLOOR" -> operation + "(" + x + ")";
                case "ROUND" -> "ROUND(" + x + ", 0)";
                case "MOD" -> "MOD(" + x + ", NULLIF(" + y + ", 0))";
                case "POWER" -> "POWER(" + x + ", " + y + ")";
                case "SQRT" -> "CASE WHEN " + x + " >= 0 THEN SQRT(" + x + ") ELSE NULL END";
                case "ADD_DAYS" -> engine == Engine.FLINK ? "DATE_FORMAT(TIMESTAMPADD(DAY, TRY_CAST(" + y + " AS INT), " + timestamp(a) + "), 'yyyy-MM-dd HH:mm:ss')"
                        : "DATE_FORMAT(" + timestamp(a) + " + MAKE_INTERVAL(0, 0, 0, TRY_CAST(" + y + " AS INT)), 'yyyy-MM-dd HH:mm:ss')";
                case "DIFF_DAYS" -> "ABS(TIMESTAMPDIFF(DAY, " + timestamp(b) + ", " + timestamp(a) + "))";
                case "YEAR", "MONTH", "DAY" -> "EXTRACT(" + operation + " FROM " + timestamp(a) + ")";
                case "STR_LEN" -> "CHAR_LENGTH(REGEXP_REPLACE(" + text + ", " + literal(engine, "[\\x{10000}-\\x{10FFFF}]") + ", 'xx'))";
                case "UPPER", "LOWER" -> operation + "(" + text + ")";
                case "INIT_CAP" -> "CONCAT(UPPER(SUBSTRING(" + text + ", 1, 1)), SUBSTRING(" + text + ", 2))";
                case "TRIM" -> trimPattern(engine, text, "BOTH", "[\\u0000-\\u0020]");
                default -> throw invalid("计算类型无效：" + operation);
            };
            String resultType = rule.getStr("valueType", "STRING").toUpperCase(Locale.ROOT);
            value = switch (resultType) {
                case "STRING" -> Set.of("ADD_DAYS", "UPPER", "LOWER", "INIT_CAP", "TRIM").contains(operation) ? "CAST(" + value + " AS STRING)" : numericText(engine, value);
                case "INTEGER" -> "TRY_CAST(ROUND(TRY_CAST(" + value + " AS DECIMAL(38,18)), 0) AS BIGINT)";
                case "DOUBLE" -> "TRY_CAST(" + value + " AS DOUBLE)";
                default -> throw invalid("计算器结果类型无效：" + resultType);
            };
            expressions.put(target, value);
            types.put(target, "INTEGER".equals(resultType) ? "BIGINT" : resultType);
            pending.add(target);
        }
        return project(engine, current, expressions, types, "", false);
    }

    private static String decimal(String value) { return "TRY_CAST(TRIM(CAST(" + value + " AS STRING)) AS DECIMAL(38,18))"; }
    private static String timestamp(String value) { return "TRY_CAST(CAST(" + value + " AS STRING) AS TIMESTAMP)"; }

    /** 去掉十进制尾零，不改变日期、科学记数法或普通文本。 */
    private static String numericText(Engine engine, String value) {
        String text = "CAST(" + value + " AS STRING)";
        return "CASE WHEN " + value + " = 0 THEN '0' ELSE REGEXP_REPLACE(REGEXP_REPLACE(" + text + ", " + literal(engine, "(?<=\\.[0-9]{0,38})0+$") + ", ''), " + literal(engine, "\\.$") + ", '') END";
    }

    private static String padding(Engine engine, String value, JSONObject rule) {
        String direction = rule.getStr("padDirection", "NONE"); int length = rule.getInt("padLength", 0);
        if ("NONE".equals(direction) || length == 0) return value;
        if (length < 0 || length > 1_000_000) throw invalid("填充长度超出范围");
        String fill = literal(engine, required(rule, "padCharacter"));
        String missing = "GREATEST(0, " + length + " - CHAR_LENGTH(" + value + "))";
        String left = "LEFT".equals(direction) ? missing : "BOTH".equals(direction) ? "CAST(FLOOR(" + missing + " / 2) AS INT)" : "0";
        String right = "(" + missing + " - " + left + ")";
        return "CONCAT(LPAD('', " + left + ", " + fill + "), " + value + ", LPAD('', " + right + ", " + fill + "))";
    }

    private static String replace(Engine engine, Query input, String value, JSONObject rule, Set<String> managedFunctions) {
        String search = required(rule, "search"); boolean regex = rule.getBool("regex", false);
        boolean empty = rule.getBool("emptyReplacement", false); String replacementField = rule.getStr("replacementField", "");
        boolean literalReplacement = !regex || empty || !replacementField.isBlank();
        String replacement = empty ? literal(engine, "") : !replacementField.isBlank()
                ? "COALESCE(CAST(" + quote(engine, field(input, replacementField)) + " AS STRING), '')" : literal(engine, rule.getStr("replacement", ""));
        if (engine == Engine.FLINK && (regex || rule.getBool("wholeWord", false))) {
            if (!managedFunctions.contains("DH_REGEX_REPLACE")) throw invalid("此正则/整词替换需要管理员安装并绑定 DH_REGEX_REPLACE 受管函数");
            return "DH_REGEX_REPLACE(" + value + ", " + literal(engine, search) + ", " + regex + ", " + rule.getBool("caseSensitive", true)
                    + ", " + rule.getBool("wholeWord", false) + ", " + replacement + ", " + literalReplacement + ")";
        }
        if (engine == Engine.SPARK && rule.getBool("wholeWord", false)) {
            if (!managedFunctions.contains("DH_REGEX_REPLACE")) throw invalid("此整词替换需要管理员安装并绑定 DH_REGEX_REPLACE 受管函数");
            return "DH_REGEX_REPLACE(" + value + ", " + literal(engine, search) + ", " + regex + ", " + rule.getBool("caseSensitive", true)
                    + ", true, " + replacement + ", " + literalReplacement + ")";
        }
        if (!regex && !rule.getBool("wholeWord", false) && rule.getBool("caseSensitive", true))
            return "REPLACE(" + value + ", " + literal(engine, search) + ", " + replacement + ")";
        String pattern = regex ? search : Pattern.quote(search);
        if (rule.getBool("wholeWord", false)) {
            String word = "[\\p{IsAlphabetic}\\p{Nd}\\p{M}\\p{Pc}\\u200c\\u200d]";
            pattern = "(?<!" + word + ")(?:" + pattern + ")(?!" + word + ")";
        }
        pattern = "(?U)" + (rule.getBool("caseSensitive", true) ? "" : "(?iu)") + pattern;
        Pattern.compile(pattern);
        if (literalReplacement && engine == Engine.SPARK) replacement = quoteReplacement(engine, replacement);
        return "REGEXP_REPLACE(" + value + ", " + literal(engine, pattern) + ", " + replacement + ")";
    }

    /** 字段替换值是字面内容，不能被底层 Matcher 当成 $1 或转义序列。 */
    private static String quoteReplacement(Engine engine, String value) {
        return "REPLACE(REPLACE(" + value + ", " + literal(engine, "\\") + ", " + literal(engine, "\\\\") + "), '$', " + literal(engine, "\\$") + ")";
    }

    private static String derive(Engine engine, JSONObject config, Query input, Set<String> managedFunctions) {
        String operation = config.getStr("operateType", "CONCAT").toUpperCase(Locale.ROOT);
        var fields = array(config, "sourceFields", false).toList(String.class); fields.forEach(name -> field(input, name));
        if ("CONSTANT".equals(operation)) return literal(engine, config.getStr("constantValue", ""));
        if ("EXPRESSION".equals(operation)) return templateExpression(engine, required(config, "expression"), input);
        if (fields.isEmpty()) throw invalid("派生需要来源字段");
        var texts = fields.stream().map(name -> "COALESCE(CAST(" + quote(engine, name) + " AS STRING), '')").toList();
        String text = texts.getFirst();
        return switch (operation) {
            case "CONCAT" -> "CONCAT(" + literal(engine, config.getStr("prefix", "")) + ", " + String.join(", " + literal(engine, config.getStr("separator", "")) + ", ", texts) + ", " + literal(engine, config.getStr("suffix", "")) + ")";
            case "SUBSTRING" -> {
                int start = config.getInt("beginIndex", 1), length = config.getInt("length", 0);
                if (start < 1 || length < 0) throw invalid("截取位置和长度无效");
                yield "SUBSTRING(" + text + ", " + start + (length == 0 ? "" : ", " + length) + ")";
            }
            case "REPLACE" -> {
                var rule = new JSONObject(config); rule.set("regex", config.getBool("useRegex", false));
                yield replace(engine, input, text, rule, managedFunctions);
            }
            case "HASH" -> {
                String all = texts.size() == 1 ? text : "CONCAT(" + String.join(", ", texts) + ")";
                yield switch (config.getStr("algorithm", "MD5").toUpperCase(Locale.ROOT)) {
                    case "MD5" -> "MD5(" + all + ")"; case "SHA1" -> "SHA1(" + all + ")"; case "SHA256" -> "SHA2(" + all + ", 256)";
                    default -> throw invalid("哈希算法无效");
                };
            }
            case "REGEX_EXTRACT" -> {
                String pattern = required(config, "pattern"); int group = config.getInt("groupIndex", 1);
                if (group < 0 || group > Pattern.compile(pattern).matcher("").groupCount()) throw invalid("正则分组序号无效");
                yield "COALESCE(REGEXP_EXTRACT(" + text + ", " + literal(engine, pattern) + ", " + group + "), '')";
            }
            default -> throw invalid("派生操作类型无效：" + operation);
        };
    }

    /** 仅接受数值运算语法；其他表达式作为插值文本，不允许拼成任意 SQL。 */
    private static String templateExpression(Engine engine, String template, Query input) {
        if (template.length() > 8192) throw invalid("表达式超过长度限制");
        var parts = new ArrayList<String>(); var matcher = Pattern.compile("\\$\\{([^}]+)}").matcher(template); int offset = 0;
        while (matcher.find()) {
            parts.add(literal(engine, template.substring(offset, matcher.start())));
            parts.add(input.columns().containsKey(matcher.group(1)) ? "COALESCE(CAST(" + quote(engine, matcher.group(1)) + " AS STRING), '')" : literal(engine, matcher.group()));
            offset = matcher.end();
        }
        parts.add(literal(engine, template.substring(offset)));
        String fallback = parts.size() == 1 ? parts.getFirst() : "CONCAT(" + String.join(", ", parts) + ")";
        try { return "COALESCE(" + numericText(engine, new ArithmeticExpression(engine, template, input).parse()) + ", " + fallback + ")"; }
        catch (ExpressionSyntax ignored) { return fallback; }
    }

    private static final class ExpressionSyntax extends RuntimeException { }
    private static final class ArithmeticExpression {
        private final Engine engine; private final String text; private final Query input; private int index; private int depth;
        private ArithmeticExpression(Engine engine, String text, Query input) { this.engine = engine; this.text = text; this.input = input; }
        private String parse() { String value = sum(); space(); if (index != text.length()) throw new ExpressionSyntax(); return value; }
        private String sum() { String value = product(); while (true) { space(); if (take('+')) value = "(" + value + " + " + product() + ")"; else if (take('-')) value = "(" + value + " - " + product() + ")"; else return value; } }
        private String product() { String value = atom(); while (true) { space(); if (take('*')) value = "(" + value + " * " + atom() + ")"; else if (take('/')) value = "(" + value + " / NULLIF(" + atom() + ", 0))"; else if (take('%')) value = "MOD(" + value + ", NULLIF(" + atom() + ", 0))"; else return value; } }
        private String atom() {
            if (++depth > 64) throw invalid("表达式嵌套超过限制");
            try {
                space(); if (take('+')) return atom(); if (take('-')) return "(-" + atom() + ")";
                if (take('(')) { String value = sum(); space(); if (!take(')')) throw new ExpressionSyntax(); return value; }
                if (text.startsWith("${", index)) { int end = text.indexOf('}', index + 2); if (end < 0) throw new ExpressionSyntax(); String name = text.substring(index + 2, end); if (!input.columns().containsKey(name)) throw new ExpressionSyntax(); index = end + 1; return decimal(quote(engine, name)); }
                var matcher = Pattern.compile("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?").matcher(text); matcher.region(index, text.length());
                if (!matcher.lookingAt()) throw new ExpressionSyntax(); index = matcher.end(); return number(matcher.group());
            } finally { depth--; }
        }
        private boolean take(char token) { if (index < text.length() && text.charAt(index) == token) { index++; return true; } return false; }
        private void space() { while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++; }
    }

    private static String columnType(JSONObject rule, String fallback) {
        String declared = rule.getStr("type", ""); String raw = declared.isBlank() ? fallback : declared;
        if (raw == null) throw invalid("来源字段类型未知，请明确声明目标类型");
        if (declared.isBlank() && rule.getStr("length", "").isBlank() && rule.getStr("precision", "").isBlank()) return fallback;
        String type = raw.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        type = switch (type) { case "INTEGER", "INT4" -> "INT"; case "INT2" -> "SMALLINT"; case "LONG", "INT8" -> "BIGINT"; case "FLOAT8" -> "DOUBLE"; case "FLOAT4" -> "FLOAT"; case "BOOL" -> "BOOLEAN"; case "TEXT", "BPCHAR", "CHARACTERVARYING" -> "STRING"; case "DATE" -> "TIMESTAMP"; case "NUMBER", "BIGDECIMAL", "NUMERIC" -> "DECIMAL"; default -> type; };
        String length = rule.getStr("length", ""), scale = rule.getStr("precision", "");
        if ("DECIMAL".equals(type) || type.startsWith("DECIMAL(")) {
            if (!length.isBlank() || !scale.isBlank() || "DECIMAL".equals(type)) {
                int precision = length.isBlank() ? 38 : integer(length, 1, 38), digits = scale.isBlank() ? 0 : integer(scale, 0, precision);
                return "DECIMAL(" + precision + "," + digits + ")";
            }
            if (!type.matches("DECIMAL\\(([1-9]|[12][0-9]|3[0-8]),([0-9]|[12][0-9]|3[0-8])\\)")) throw invalid("Decimal 类型无效");
            String[] values = type.substring(8, type.length() - 1).split(","); if (Integer.parseInt(values[1]) > Integer.parseInt(values[0])) throw invalid("Decimal 小数位不能超过精度");
            return type;
        }
        if (Set.of("STRING", "VARCHAR", "CHAR").contains(type)) {
            if (!scale.isBlank()) throw invalid("字符串类型不接受小数精度");
            return length.isBlank() ? "STRING" : "VARCHAR(" + integer(length, 1, 1_000_000) + ")";
        }
        if (!Set.of("INT", "BIGINT", "SMALLINT", "TINYINT", "FLOAT", "DOUBLE", "BOOLEAN", "TIMESTAMP", "BINARY", "BYTES").contains(type)
                && !type.matches("(?:VARCHAR|CHAR)\\([1-9][0-9]{0,5}\\)|TIMESTAMP\\([0-9]\\)")) throw invalid("目标字段类型无效：" + raw);
        if (!length.isBlank() || !scale.isBlank()) throw invalid("该字段类型不接受长度或精度");
        return "BYTES".equals(type) ? "BINARY" : type;
    }
    private static String columnCast(Engine engine, String value, String type) {
        if (type.startsWith("VARCHAR(") || type.startsWith("CHAR(")) {
            String length = type.substring(type.indexOf('(') + 1, type.length() - 1);
            return "SUBSTRING(CAST(" + value + " AS STRING), 1, " + length + ")";
        }
        String target = engine == Engine.SPARK && type.startsWith("TIMESTAMP(") ? "TIMESTAMP" : type;
        return "CAST(" + value + " AS " + target + ")";
    }
    private static int integer(String value, int minimum, int maximum) { try { int parsed = new BigDecimal(value).intValueExact(); if (parsed < minimum || parsed > maximum) throw invalid("类型长度或精度超出范围"); return parsed; } catch (NumberFormatException | ArithmeticException failure) { throw invalid("类型长度或精度必须为整数"); } }
    private static String constantType(String type) { return switch (type) { case "Integer" -> "INT"; case "Long" -> "BIGINT"; case "Double" -> "DOUBLE"; case "Boolean" -> "BOOLEAN"; case "Date" -> "TIMESTAMP"; default -> "STRING"; }; }

    private static Query splitFields(Engine engine, JSONObject config, Query input, Set<String> managedFunctions) {
        String source = field(input, required(config, "sourceField")), mode = config.getStr("splitType", "DELIMITER");
        if (!Set.of("DELIMITER", "REGEX").contains(mode)) throw invalid("字段拆分类型无效");
        boolean regex = "REGEX".equals(mode); String separator = required(config, regex ? "pattern" : "delimiter"); String wrapper = config.getStr("wrapper", "");
        cn.iocoder.yudao.framework.etl.core.executor.support.EtlTokenSplit.compile(separator, regex, wrapper);
        if (regex && !managedFunctions.contains("DH_SPLIT_TOKEN")) throw invalid("正则拆分需要管理员安装并绑定 DH_SPLIT_TOKEN 受管函数");
        String text = "COALESCE(CAST(" + quote(engine, source) + " AS STRING), '')";
        var expressions = new LinkedHashMap<String, String>(); input.columns().keySet().forEach(name -> expressions.put(name, quote(engine, name)));
        var types = new LinkedHashMap<>(input.columns()); var targets = new HashSet<String>(); int index = 0;
        for (Object item : array(config, "outputs", true)) {
            var rule = JSONUtil.parseObj(item); String name = required(rule, "name"), trim = rule.getStr("trimType", "NONE").toUpperCase(Locale.ROOT);
            if (!targets.add(name) || !Set.of("NONE", "BOTH", "LEFT", "RIGHT").contains(trim)) throw invalid("拆分输出字段重复或去空格方式无效");
            String token;
            if (managedFunctions.contains("DH_SPLIT_TOKEN")) token = "DH_SPLIT_TOKEN(" + text + ", " + literal(engine, separator) + ", " + regex + ", " + literal(engine, wrapper) + ", " + index + ", " + literal(engine, trim) + ")";
            else {
                String count = "1 + (CHAR_LENGTH(" + text + ") - CHAR_LENGTH(REPLACE(" + text + ", " + literal(engine, separator) + ", ''))) / " + separator.codePointCount(0, separator.length());
                token = engine == Engine.FLINK ? "COALESCE(SPLIT_INDEX(" + text + ", " + literal(engine, separator) + ", " + index + "), '')" : "SPLIT_PART(" + text + ", " + literal(engine, separator) + ", " + (index + 1) + ")";
                if (!wrapper.isEmpty()) {
                    int length = wrapper.codePointCount(0, wrapper.length());
                    token = "CASE WHEN LEFT(" + token + ", " + length + ") = " + literal(engine, wrapper) + " THEN SUBSTRING(" + token + ", " + (length + 1) + ") ELSE " + token + " END";
                    token = "CASE WHEN RIGHT(" + token + ", " + length + ") = " + literal(engine, wrapper) + " THEN SUBSTRING(" + token + ", 1, CHAR_LENGTH(" + token + ") - " + length + ") ELSE " + token + " END";
                }
                token = "CASE WHEN " + (index + 1) + " <= " + count + " THEN " + splitTrim(engine, token, trim) + " ELSE NULL END";
            }
            var conversion = new JSONObject(rule); conversion.set("type", rule.getStr("type", rule.getStr("dataType", rule.getStr("columnType", "STRING"))));
            String type = columnType(conversion, "STRING"), format = rule.getStr("format", "");
            if (!format.isBlank()) {
                if (!type.startsWith("TIMESTAMP")) throw invalid("字段格式仅适用于日期时间类型");
                token = "TO_TIMESTAMP(" + token + ", " + literal(engine, format) + ")";
            } else if (!"STRING".equals(type)) token = columnCast(engine, token, type);
            expressions.put(name, token); types.put(name, type); index++;
        }
        if (!config.getBool("keepSource", false)) { expressions.remove(source); types.remove(source); }
        return project(engine, input, expressions, types, "", false);
    }

    private record OrderedInput(Query query, String order, String reverseOrder) { }

    /** 用户声明排序字段后仍验证实际唯一性，重复键不能被 ROW_NUMBER 隐式打散。 */
    private static OrderedInput ordered(Engine engine, JSONObject config, Query input) {
        var unique = array(config, "uniqueFields", true).toList(String.class);
        if (new HashSet<>(unique).size() != unique.size()) throw invalid("唯一排序字段不能重复");
        unique.forEach(name -> field(input, name)); var sorts = new ArrayList<String>(); var reverse = new ArrayList<String>(); var names = new HashSet<String>();
        for (Object item : array(config, "orderBy", true)) {
            var rule = JSONUtil.parseObj(item); String name = field(input, required(rule, "field")); String direction = rule.getStr("order", "ASC");
            if (!names.add(name) || !Set.of("ASC", "DESC").contains(direction)) throw invalid("排序字段重复或方向无效");
            sorts.add(quote(engine, name) + ("ASC".equals(direction) ? " ASC NULLS LAST" : " DESC NULLS FIRST"));
            reverse.add(quote(engine, name) + ("ASC".equals(direction) ? " DESC NULLS FIRST" : " ASC NULLS LAST"));
        }
        if (!names.containsAll(unique)) throw invalid("排序必须包含全部唯一字段");
        String count = freshColumn(input, "dh_unique_count"); String quoted = quote(engine, count);
        String partitions = String.join(", ", unique.stream().map(name -> quote(engine, name)).toList());
        String projected = String.join(", ", input.columns().keySet().stream().map(name -> quote(engine, name)).toList());
        String sql = "SELECT " + projected + " FROM (SELECT dh_order_input.*, COUNT(*) OVER (PARTITION BY " + partitions + ") AS " + quoted
                + " FROM (" + input.sql() + ") dh_order_input) dh_order_checked WHERE " + assertion(engine, quoted + " = 1", "唯一排序字段存在重复值", quoted);
        return new OrderedInput(new Query(sql, new LinkedHashMap<>(input.columns())), String.join(", ", sorts), String.join(", ", reverse));
    }

    /** 确定排序后按键保留首条，casefold 仅用于分组，不改写被保留行的原始值。 */
    private static Query deduplicateFirst(Engine engine, JSONObject config, Query input, String strategy) {
        var keys = "ALL_FIELDS".equals(strategy) ? new ArrayList<>(input.columns().keySet()) : array(config, "keyFields", true).toList(String.class);
        if (new HashSet<>(keys).size() != keys.size()) throw invalid("去重键字段不能重复");
        keys.forEach(name -> field(input, name));
        String columns = String.join(", ", input.columns().keySet().stream().map(name -> quote(engine, name)).toList());
        // 完全相同的行无需选择即可消除；其余同序键但不同 payload 仍必须在 ordered 中失败。
        var distinctInput = new Query("SELECT DISTINCT " + columns + " FROM (" + input.sql() + ") dh_dedup_distinct", new LinkedHashMap<>(input.columns()));
        var checked = ordered(engine, config, distinctInput);
        // 显式逐字段配置优先；缺省时继续沿用历史全局 ignoreCase 语义。
        Set<String> insensitive;
        if (config.containsKey("ignoreCaseFields")) {
            var fields = array(config, "ignoreCaseFields", false).toList(String.class);
            if (new HashSet<>(fields).size() != fields.size() || !keys.containsAll(fields)
                    || fields.stream().anyMatch(name -> !textType(input.columns().get(name))))
                throw invalid("忽略大小写字段必须为不重复的字符串去重键");
            insensitive = Set.copyOf(fields);
        } else insensitive = config.getBool("ignoreCase", false) ? Set.copyOf(keys) : Set.of();
        var groups = keys.stream().map(name -> insensitive.contains(name) && textType(input.columns().get(name))
                ? "LOWER(" + quote(engine, name) + ")" : quote(engine, name)).toList();
        String index = freshColumn(input, "dh_dedup_row");
        String sql = "SELECT " + columns + " FROM (SELECT dh_dedup_input.*, ROW_NUMBER() OVER (PARTITION BY " + String.join(", ", groups)
                + " ORDER BY " + checked.order() + ") AS " + quote(engine, index) + " FROM (" + checked.query().sql() + ") dh_dedup_input) dh_dedup_first WHERE " + quote(engine, index) + " = 1";
        return new Query(sql, new LinkedHashMap<>(input.columns()));
    }

    private static Query sequence(Engine engine, boolean streaming, JSONObject config, Query input) {
        if (streaming) throw invalid("实时序列需要持久状态和确定的窗口边界，不能使用无界全局排序");
        long start = longOption(config, "start", 1), step = longOption(config, "step", 1), maximum = longOption(config, "max", 0);
        if (step <= 0 || maximum < 0 || maximum > 0 && maximum < start) throw invalid("序列步长或最大值无效");
        String target = required(config, "targetField"); var ordered = ordered(engine, config, input);
        String index = freshColumn(input, "dh_sequence_row"); var indexedTypes = new LinkedHashMap<>(input.columns()); indexedTypes.put(index, "BIGINT");
        var indexed = new Query("SELECT dh_sequence_input.*, ROW_NUMBER() OVER (ORDER BY " + ordered.order() + ") AS " + quote(engine, index)
                + " FROM (" + ordered.query().sql() + ") dh_sequence_input", indexedTypes);
        String value = "(CAST(" + start + " AS DECIMAL(38,0)) + CAST(" + step + " AS DECIMAL(38,0)) * (CAST(" + quote(engine, index) + " AS DECIMAL(38,0)) - 1))";
        String condition = value + " >= -9223372036854775808 AND " + value + " <= 9223372036854775807" + (maximum > 0 ? " AND " + value + " <= " + maximum : "");
        var expressions = new LinkedHashMap<String, String>(); input.columns().keySet().forEach(name -> expressions.put(name, quote(engine, name)));
        var types = new LinkedHashMap<>(input.columns()); expressions.put(target, "CAST(" + value + " AS BIGINT)"); types.put(target, "BIGINT");
        return project(engine, indexed, expressions, types, " WHERE " + assertion(engine, condition, "序列超过最大值或Long范围", value), false);
    }

    /** 同组同键按显式全序取末条，聚合只折叠已经选定的一条值，不用任意 MAX 代替末值。 */
    private static Query pivot(Engine engine, boolean streaming, JSONObject config, Query input) {
        if (rowColumnVersion(config) == 2) return pivotConfigured(engine, streaming, config, input);
        if (streaming) {
            var adapted = new JSONObject(config); var mappings = new JSONArray();
            for (Object item : array(config, "mappings", true)) {
                var mapping = JSONUtil.parseObj(item);
                mappings.add(new JSONObject().set("targetField", required(mapping, "fieldName")).set("sourceField", required(config, "valueField"))
                        .set("keyValue", required(mapping, "keyValue")).set("aggregation", ""));
            }
            adapted.set("rowColumnVersion", 2).set("pivotMappings", mappings);
            return pivotConfigured(engine, true, adapted, input);
        }
        var groups = array(config, "groupFields", false).toList(String.class);
        if (new HashSet<>(groups).size() != groups.size()) throw invalid("分组字段不能重复");
        groups.forEach(name -> field(input, name)); String key = field(input, required(config, "keyField")), value = field(input, required(config, "valueField"));
        String valueType = input.columns().get(value);
        if (valueType == null || valueType.toUpperCase(Locale.ROOT).matches(".*(?:BINARY|BYTES|ARRAY|MAP|ROW|OBJECT).*")) throw invalid("行转列值字段需要可聚合的标量类型");
        var ordered = ordered(engine, config, input); var partitions = new ArrayList<>(groups); if (!partitions.contains(key)) partitions.add(key);
        String index = freshColumn(input, "dh_pivot_row"); String indexed = "SELECT dh_pivot_input.*, ROW_NUMBER() OVER (PARTITION BY "
                + String.join(", ", partitions.stream().map(name -> quote(engine, name)).toList()) + " ORDER BY " + ordered.reverseOrder() + ") AS " + quote(engine, index)
                + " FROM (" + ordered.query().sql() + ") dh_pivot_input";
        var types = new LinkedHashMap<String, String>(); var projections = new ArrayList<String>(); var mappedKeys = new HashSet<String>();
        for (String group : groups) { types.put(group, input.columns().get(group)); projections.add(quote(engine, group)); }
        for (Object item : array(config, "mappings", true)) {
            var mapping = JSONUtil.parseObj(item); String keyValue = required(mapping, "keyValue"), name = required(mapping, "fieldName");
            if (!mappedKeys.add(keyValue) || types.putIfAbsent(name, valueType) != null) throw invalid("行转列映射键值或输出字段重复");
            boolean bool = Set.of("BOOL", "BOOLEAN").contains(valueType.toUpperCase(Locale.ROOT));
            String selected = bool ? "CASE WHEN " + quote(engine, value) + " THEN 1 WHEN NOT " + quote(engine, value) + " THEN 0 ELSE NULL END" : quote(engine, value);
            String aggregate = "MAX(CASE WHEN COALESCE(CAST(" + quote(engine, key) + " AS STRING), '') = " + literal(engine, keyValue)
                    + " AND " + quote(engine, index) + " = 1 THEN " + selected + " ELSE NULL END)";
            projections.add((bool ? "(" + aggregate + " = 1)" : aggregate) + " AS " + quote(engine, name));
        }
        return new Query("SELECT " + String.join(", ", projections) + " FROM (" + indexed + ") dh_pivot_ranked"
                + (groups.isEmpty() ? "" : " GROUP BY " + String.join(", ", groups.stream().map(name -> quote(engine, name)).toList())) + " HAVING COUNT(*) > 0", types);
    }

    private static String assertion(Engine engine, String condition, String message, String value) {
        String failure = engine == Engine.SPARK ? "RAISE_ERROR(" + literal(engine, message) + ")"
                : "CAST(CONCAT(" + literal(engine, message + ": ") + ", COALESCE(CAST(" + value + " AS STRING), '')) AS BIGINT) = 0";
        return "CASE WHEN " + condition + " THEN TRUE ELSE " + failure + " END";
    }
    private static String freshColumn(Query input, String prefix) { String name = prefix; while (input.columns().containsKey(name)) name += "_"; return name; }
    private static long longOption(JSONObject config, String key, long fallback) {
        Object value = config.get(key); if (value == null) return fallback;
        if (value instanceof Double || value instanceof Float) throw invalid("序列参数必须使用无损整数字符串");
        if (value instanceof Number && new BigDecimal(value.toString()).abs().compareTo(new BigDecimal("9007199254740991")) > 0)
            throw invalid("超过JavaScript安全范围的序列参数必须使用字符串");
        if (value instanceof String && !value.toString().matches("[+-]?[0-9]+")) throw invalid("序列参数必须是十进制整数字符串");
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (NumberFormatException | ArithmeticException invalid) { throw invalid("序列参数超过Long范围或不是整数"); }
    }

    private static Query unpivot(Engine engine, JSONObject config, Query input) {
        if (rowColumnVersion(config) == 2) return unpivotConfigured(engine, config, input);
        if (!"COL2ROW".equals(config.getStr("mode", "COL2ROW"))) throw invalid("行转列的同组同键后值覆盖需要确定的排序字段，不能用任意 MAX 代替");
        var fields = array(config, "unpivotFields", true).toList(String.class);
        if (fields.stream().distinct().count() != fields.size()) throw invalid("需转换的列不能重复");
        fields.forEach(name -> field(input, name)); String valueType = input.columns().get(fields.getFirst());
        for (String name : fields) if (!Objects.equals(valueType, input.columns().get(name))) throw invalid("列转行要求同类型字段，避免引擎自动转换丢失精度");
        String key = config.getStr("keyField", "key"), value = config.getStr("valueField", "value");
        if (key.equals(value)) throw invalid("键字段和值字段不能相同");
        var types = new LinkedHashMap<>(input.columns()); fields.forEach(types::remove);
        var expressions = new LinkedHashMap<String, String>(); types.keySet().forEach(name -> expressions.put(name, "dh_base." + quote(engine, name)));
        String keyExpression = engine == Engine.FLINK ? "dh_unpivot.dh_key" : "dh_unpivot.dh_pair.dh_key";
        String valueExpression = engine == Engine.FLINK ? "dh_unpivot.dh_value" : "dh_unpivot.dh_pair.dh_value";
        expressions.put(key, keyExpression); expressions.put(value, valueExpression); types.put(key, "STRING"); types.put(value, valueType);
        String expansion;
        if (engine == Engine.FLINK) expansion = " CROSS JOIN UNNEST(ARRAY[" + String.join(", ", fields.stream()
                .map(name -> "ROW(" + literal(engine, name) + ", dh_base." + quote(engine, name) + ")").toList()) + "]) AS dh_unpivot(dh_key, dh_value)";
        else expansion = " LATERAL VIEW EXPLODE(ARRAY(" + String.join(", ", fields.stream()
                .map(name -> "NAMED_STRUCT('dh_key', " + literal(engine, name) + ", 'dh_value', dh_base." + quote(engine, name) + ")").toList()) + ")) dh_unpivot AS dh_pair";
        String where = config.getBool("skipNull", false) ? " WHERE " + valueExpression + " IS NOT NULL AND "
                + trim(engine, "CAST(" + valueExpression + " AS STRING)", "BOTH") + " <> ''" : "";
        return new Query("SELECT " + String.join(", ", expressions.entrySet().stream().map(entry -> entry.getValue() + " AS " + quote(engine, entry.getKey())).toList())
                + " FROM (" + input.sql() + ") dh_base" + expansion + where, types);
    }

    /** 显式版本隔离新表格协议，历史节点不因页面名称或新增字段而改变执行语义。 */
    private static int rowColumnVersion(JSONObject config) {
        Object declared = config.get("rowColumnVersion");
        if (declared == null) return 1;
        if (!Set.of("1", "2").contains(declared.toString())) throw invalid("行列转换协议版本无效");
        return Integer.parseInt(declared.toString());
    }

    /** 每个来源映射展开一行；未命中的其他输出列显式置空，不进行隐式混合类型转换。 */
    private static Query unpivotConfigured(Engine engine, JSONObject config, Query input) {
        String key = required(config, "keyField");
        var mappings = array(config, "unpivotMappings", true).toList(JSONObject.class);
        var consumed = new HashSet<String>(); var targetTypes = new LinkedHashMap<String, String>();
        for (var mapping : mappings) {
            String source = field(input, required(mapping, "sourceField")), target = required(mapping, "targetField");
            required(mapping, "keyValue");
            if (!consumed.add(source)) throw invalid("宽表转长表的来源字段不能重复");
            String type = input.columns().get(source), prior = targetTypes.putIfAbsent(target, type);
            if (type == null || type.toUpperCase(Locale.ROOT).matches(".*(?:BINARY|BYTES|ARRAY|MAP|ROW|OBJECT).*")) throw invalid("行列转换需要已知的标量字段类型");
            if (prior != null && !prior.equalsIgnoreCase(type)) throw invalid("写入同一输出列的来源字段必须类型一致，请先明确转换类型");
            if (key.equals(target)) throw invalid("Key字段与输出值字段不能相同");
        }
        var types = new LinkedHashMap<>(input.columns()); consumed.forEach(types::remove);
        if (types.containsKey(key) || targetTypes.keySet().stream().anyMatch(types::containsKey)) throw invalid("行列转换输出字段不能覆盖保留字段");
        types.put(key, "STRING"); types.putAll(targetTypes);
        var branches = new ArrayList<String>();
        for (var mapping : mappings) {
            String source = mapping.getStr("sourceField"), target = mapping.getStr("targetField");
            var expressions = new ArrayList<String>();
            for (String name : input.columns().keySet()) if (!consumed.contains(name)) expressions.add(quote(engine, name) + " AS " + quote(engine, name));
            expressions.add(literal(engine, mapping.getStr("keyValue")) + " AS " + quote(engine, key));
            targetTypes.forEach((name, type) -> expressions.add((name.equals(target) ? quote(engine, source) : columnCast(engine, "NULL", type)) + " AS " + quote(engine, name)));
            String where = config.getBool("skipNull", false) ? " WHERE " + quote(engine, source) + " IS NOT NULL AND " + trim(engine, "CAST(" + quote(engine, source) + " AS STRING)", "BOTH") + " <> ''" : "";
            branches.add("SELECT " + String.join(", ", expressions) + " FROM (" + input.sql() + ") dh_unpivot_input" + where);
        }
        return new Query(String.join(" UNION ALL ", branches), types);
    }

    /** 聚合参数显式执行；无聚合的列仍按用户声明的完整全序取最后一条匹配记录。 */
    private static Query pivotConfigured(Engine engine, boolean streaming, JSONObject config, Query input) {
        var groups = array(config, "groupFields", true).toList(String.class);
        if (new HashSet<>(groups).size() != groups.size()) throw invalid("分组字段不能重复");
        groups.forEach(name -> field(input, name));
        String key = field(input, required(config, "keyField"));
        if (groups.contains(key)) throw invalid("Key字段不能同时作为分组字段");
        var mappings = array(config, "pivotMappings", true).toList(JSONObject.class);
        var targets = new LinkedHashMap<String, List<JSONObject>>(); var types = new LinkedHashMap<String, String>();
        groups.forEach(name -> types.put(name, input.columns().get(name)));
        var pairs = new HashSet<String>(); boolean requiresOrder = false;
        for (var mapping : mappings) {
            String source = field(input, required(mapping, "sourceField")), target = required(mapping, "targetField");
            String keyValue = required(mapping, "keyValue");
            if (source.equals(key) || groups.contains(source)) throw invalid("数据字段不能与Key或分组字段相同");
            if (groups.contains(target) || !pairs.add(target + "\u0000" + keyValue)) throw invalid("目标字段与关键字组合重复或覆盖分组字段");
            String type = columnType(mapping, input.columns().get(source));
            if (type == null || type.toUpperCase(Locale.ROOT).matches(".*(?:BINARY|BYTES|ARRAY|MAP|ROW|OBJECT).*")) throw invalid("行列转换需要可聚合的标量字段类型");
            String aggregation = mapping.getStr("aggregation", "");
            if (!Set.of("", "Sum", "Average", "Minimum", "Maximum").contains(aggregation)) throw invalid("行列聚合方式无效");
            if (Set.of("Sum", "Average").contains(aggregation) && !numericType(type)) throw invalid("求和与平均需要数值目标类型");
            if (Set.of("Minimum", "Maximum").contains(aggregation) && "BOOLEAN".equalsIgnoreCase(type)) throw invalid("布尔字段不支持最小或最大聚合");
            if (aggregation.isBlank()) requiresOrder = true;
            String prior = types.putIfAbsent(target, type);
            if (prior != null && !prior.equalsIgnoreCase(type)) throw invalid("相同目标字段的映射类型必须一致");
            var priorRules = targets.computeIfAbsent(target, _ -> new ArrayList<>());
            if (!priorRules.isEmpty() && !priorRules.getFirst().getStr("aggregation", "").equals(aggregation)) throw invalid("相同目标字段的聚合方式必须一致");
            priorRules.add(mapping);
        }
        if (streaming) return pivotWindowed(engine, config, input, groups, key, targets, types);
        OrderedInput ordered = requiresOrder ? ordered(engine, config, input) : null;
        var rowIndexes = new LinkedHashMap<String, String>(); var indexes = new ArrayList<String>();
        if (ordered != null) for (var entry : targets.entrySet()) if (entry.getValue().getFirst().getStr("aggregation", "").isBlank()) {
            String name = freshColumn(input, "dh_pivot_index_" + rowIndexes.size()); rowIndexes.put(entry.getKey(), name);
            String matches = String.join(" OR ", entry.getValue().stream().map(rule -> pivotKeyMatch(engine, key, rule.getStr("keyValue"))).toList());
            indexes.add("ROW_NUMBER() OVER (PARTITION BY " + String.join(", ", groups.stream().map(group -> quote(engine, group)).toList())
                    + " ORDER BY CASE WHEN " + matches + " THEN 0 ELSE 1 END, " + ordered.reverseOrder() + ") AS " + quote(engine, name));
        }
        String inner = ordered == null ? input.sql() : "SELECT dh_pivot_input.*, " + String.join(", ", indexes) + " FROM (" + ordered.query().sql() + ") dh_pivot_input";
        var projections = new ArrayList<String>(); groups.forEach(name -> projections.add(quote(engine, name)));
        for (var entry : targets.entrySet()) {
            String target = entry.getKey(), type = types.get(target), aggregation = entry.getValue().getFirst().getStr("aggregation", "");
            boolean bool = "BOOLEAN".equalsIgnoreCase(type); var cases = new ArrayList<String>();
            for (var rule : entry.getValue()) {
                String value = pivotValue(engine, rule, type);
                if (bool) value = "CASE WHEN " + value + " THEN 1 WHEN NOT " + value + " THEN 0 ELSE NULL END";
                String condition = pivotKeyMatch(engine, key, rule.getStr("keyValue"));
                if (aggregation.isBlank()) condition += " AND " + quote(engine, rowIndexes.get(target)) + " = 1";
                cases.add("WHEN " + condition + " THEN " + value);
            }
            String function = switch (aggregation) { case "Sum" -> "SUM"; case "Average" -> "AVG"; case "Minimum" -> "MIN"; default -> "MAX"; };
            String aggregate = function + "(CASE " + String.join(" ", cases) + " ELSE NULL END)";
            projections.add((bool ? "(" + aggregate + " = 1)" : columnCast(engine, aggregate, type)) + " AS " + quote(engine, target));
        }
        return new Query("SELECT " + String.join(", ", projections) + " FROM (" + inner + ") dh_pivot_ranked GROUP BY "
                + String.join(", ", groups.stream().map(name -> quote(engine, name)).toList()), types);
    }

    private static String pivotKeyMatch(Engine engine, String key, String value) {
        return "COALESCE(CAST(" + quote(engine, key) + " AS STRING), '') = " + literal(engine, value);
    }
    private static String pivotValue(Engine engine, JSONObject rule, String type) {
        return pivotValueExpression(engine, rule, type, quote(engine, required(rule, "sourceField")));
    }
    private static String pivotValueExpression(Engine engine, JSONObject rule, String type, String value) {
        String nullIf = rule.getStr("nullIf", ""), format = rule.getStr("format", "");
        if (!nullIf.isBlank()) value = "CASE WHEN CAST(" + value + " AS STRING) = " + literal(engine, nullIf) + " THEN NULL ELSE " + value + " END";
        if (!format.isBlank()) {
            if (!type.startsWith("TIMESTAMP") || format.length() > 128 || format.codePoints().anyMatch(Character::isISOControl)) throw invalid("格式只接受日期时间类型的有界格式串");
            return "TO_TIMESTAMP(CAST(" + value + " AS STRING), " + literal(engine, format) + ")";
        }
        return columnCast(engine, value, type);
    }

    private static void requireWindowFunction(Engine engine, Set<String> functions) {
        if (engine != Engine.FLINK || !functions.contains(EtlNativeStreamingWindow.PICK_FUNCTION))
            throw invalid("实时状态窗口需要当前工作环境安装并授权 DH_WINDOW_PICK 受管函数");
    }

    private static Query pivotWindowed(Engine engine, JSONObject config, Query input, List<String> groups, String key,
                                      LinkedHashMap<String, List<JSONObject>> targets, LinkedHashMap<String, String> types) {
        var window = EtlNativeStreamingWindow.require(config);
        var inner = new ArrayList<String>(); var outer = new ArrayList<String>();
        groups.forEach(name -> { inner.add(quote(engine, name)); outer.add(quote(engine, name)); });
        // 预算按整个窗口组检查；不能只对每个映射过滤后的少量匹配行应用预算。
        boolean ordered = targets.values().stream().anyMatch(rules -> rules.getFirst().getStr("aggregation", "").isBlank());
        inner.add(EtlNativeStreamingWindow.pick(config, input.columns(), window, false, ordered) + " AS `__dh_window_budget`");
        var names = new ArrayList<>(input.columns().keySet()); int index = 0;
        for (var entry : targets.entrySet()) {
            String target = entry.getKey(), type = types.get(target), aggregation = entry.getValue().getFirst().getStr("aggregation", "");
            if (target.startsWith("__dh_window_")) throw invalid("输出字段不能占用实时窗口保留前缀");
            var cases = new ArrayList<String>(); String expression;
            if (aggregation.isBlank()) {
                String alias = "__dh_window_last_" + index++;
                String matches = String.join(" OR ", entry.getValue().stream().map(rule -> pivotKeyMatch(engine, key, rule.getStr("keyValue"))).toList());
                inner.add(EtlNativeStreamingWindow.pick(config, input.columns(), window, true, true) + " FILTER (WHERE " + matches + ") AS " + quote(engine, alias));
                for (var rule : entry.getValue()) {
                    String pickedKey = quote(engine, alias) + "[" + (names.indexOf(key) + 1) + "]";
                    String source = required(rule, "sourceField"), pickedValue = columnCast(engine, quote(engine, alias) + "[" + (names.indexOf(source) + 1) + "]", input.columns().get(source));
                    cases.add("WHEN COALESCE(" + pickedKey + ", '') = " + literal(engine, rule.getStr("keyValue")) + " THEN " + pivotValueExpression(engine, rule, type, pickedValue));
                }
                expression = "CASE " + String.join(" ", cases) + " ELSE NULL END";
            } else {
                for (var rule : entry.getValue()) cases.add("WHEN " + pivotKeyMatch(engine, key, rule.getStr("keyValue")) + " THEN " + pivotValue(engine, rule, type));
                String function = switch (aggregation) { case "Sum" -> "SUM"; case "Average" -> "AVG"; case "Minimum" -> "MIN"; default -> "MAX"; };
                inner.add(columnCast(engine, function + "(CASE " + String.join(" ", cases) + " ELSE NULL END)", type) + " AS " + quote(engine, target));
                expression = quote(engine, target);
            }
            outer.add(expression + " AS " + quote(engine, target));
        }
        var partitions = new ArrayList<>(List.of("window_start", "window_end")); groups.forEach(name -> partitions.add(quote(engine, name)));
        String sql = EtlNativeStreamingWindow.input(input.sql(), input.columns(), window) + "SELECT " + String.join(", ", outer)
                + " FROM (SELECT " + String.join(", ", inner) + " FROM dh_windowed GROUP BY " + String.join(", ", partitions)
                + ") dh_window_results WHERE `__dh_window_budget` IS NOT NULL";
        return new Query(sql, types);
    }

    private static Query project(Engine engine, Query input, LinkedHashMap<String, String> expressions, LinkedHashMap<String, String> types, String tail, boolean distinct) {
        if (expressions.isEmpty()) throw invalid("不能移除全部字段");
        String order = tail.startsWith(" ORDER BY ") ? tail : input.outputOrder();
        String effectiveTail = tail.isBlank() ? order : tail;
        return new Query("SELECT " + (distinct ? "DISTINCT " : "") + String.join(", ", expressions.entrySet().stream()
                .map(entry -> entry.getValue() + " AS " + quote(engine, entry.getKey())).toList()) + " FROM (" + input.sql() + ") dh_stage" + effectiveTail, types, order);
    }

    private static void requireUnorderedIntermediate(Query input) {
        if (!input.outputOrder().isBlank()) throw invalid("排序后须直接连接表输出或脚本输入，请将排序移到该边界前；其它转换不能保证保留原值顺序");
    }

    private static String conditions(Engine engine, JSONObject config, Query query) {
        var result = new ArrayList<String>(); String logical = config.getStr("logicalOperator", "AND");
        if (!Set.of("AND", "OR").contains(logical)) throw invalid("过滤逻辑无效");
        for (Object item : array(config, "filterConditions", true)) {
            var rule = JSONUtil.parseObj(item); String name = field(query, rule.getStr("fieldName")); String field = quote(engine, name), text = "CAST(" + field + " AS STRING)";
            String operator = rule.getStr("operator"), value = rule.getStr("value");
            result.add("(" + switch (operator) {
                case "IS_NULL" -> field + " IS NULL OR " + text + " = ''";
                case "IS_NOT_NULL" -> field + " IS NOT NULL AND " + text + " <> ''";
                case "EQUALS", "NOT_EQUALS" -> {
                    String comparison;
                    if (value == null) comparison = field + " IS NULL";
                    else if (numericType(query.columns().get(name)) && isNumber(value)) { comparison = "COALESCE(" + field + " = " + number(value) + ", FALSE)"; }
                    else if (textType(query.columns().get(name)) && isNumber(value)) throw invalid("字符串中的数值比较存在类型歧义，请使用明确数值字段或非数值字符串条件");
                    else comparison = "COALESCE(" + text + " = " + literal(engine, value) + ", FALSE)";
                    yield "NOT_EQUALS".equals(operator) ? "NOT (" + comparison + ")" : comparison;
                }
                case "GREATER_THAN", "GREATER_EQUAL", "LESS_THAN", "LESS_EQUAL" -> {
                    requireNumeric(query, name);
                    if (value == null) yield switch (operator) { case "GREATER_THAN" -> field + " IS NOT NULL"; case "GREATER_EQUAL" -> "TRUE"; case "LESS_THAN" -> "FALSE"; default -> field + " IS NULL"; };
                    String comparison = field + switch (operator) { case "GREATER_THAN" -> " > "; case "GREATER_EQUAL" -> " >= "; case "LESS_THAN" -> " < "; default -> " <= "; } + number(value);
                    yield operator.startsWith("LESS") ? field + " IS NULL OR " + comparison : comparison;
                }
                case "BETWEEN" -> { requireNumeric(query, name); yield field + " >= " + number(rule.getStr("minValue")) + " AND " + field + " <= " + number(rule.getStr("maxValue")); }
                case "CONTAINS", "NOT_CONTAINS" -> { String match = "POSITION(" + literal(engine, value) + " IN " + text + ") > 0"; yield "NOT_CONTAINS".equals(operator) ? "NOT COALESCE(" + match + ", FALSE)" : match; }
                case "IN", "NOT_IN" -> { if (value == null) throw invalid("过滤值不能为空"); var items = Arrays.stream(value.split(",")).map(part -> literal(engine, part)).toList();
                    String match = items.isEmpty() ? "FALSE" : text + " IN (" + String.join(", ", items) + ")"; yield "NOT_IN".equals(operator) ? "NOT COALESCE(" + match + ", FALSE)" : match; }
                default -> throw invalid("过滤操作符尚未原生适配：" + operator);
            } + ")");
        }
        return String.join(" " + logical + " ", result);
    }

    private static Table table(JSONObject config, Tables resolver) { Long id = config.getLong("databaseId"); if (id == null) throw invalid("请选择数据源"); return Objects.requireNonNull(resolver.resolve(id, required(config, "tableName"), config.getStr("schemaName"))); }
    private static String field(Query input, String name) { if (!input.columns().containsKey(name)) throw invalid("不存在的输入字段：" + name); return name; }
    private static boolean textType(String type) { return type != null && type.toUpperCase(Locale.ROOT).matches(".*(CHAR|TEXT|STRING).*"); }
    private static boolean numericType(String type) { return type != null && type.toUpperCase(Locale.ROOT).matches(".*(INT|DECIMAL|NUMERIC|DOUBLE|FLOAT|REAL|NUMBER|LONG).*"); }
    private static void requireNumeric(Query query, String name) { if (!numericType(query.columns().get(name))) throw invalid("数值操作要求明确的数值字段：" + name); }
    private static String quote(Engine engine, String name) { if (name == null || name.isBlank() || name.length() > 256 || name.codePoints().anyMatch(Character::isISOControl) || engine == Engine.SPARK && name.contains("${")) throw invalid("标识符无效或包含引擎变量语法"); String quote = "`"; return quote + name.replace(quote, quote + quote) + quote; }
    private static String path(Engine engine, List<String> names) { if (names == null || names.isEmpty() || names.size() > 3) throw invalid("受管引擎表命名空间无效"); return String.join(".", names.stream().map(name -> quote(engine, name)).toList()); }
    /** Spark 把相邻单引号字面量合并，特殊字符用 UTF-8 二进制解码保留原值。 */
    private static String literal(Engine engine, String value) {
        if (value == null) return "NULL";
        if (value.length() > 8192) throw invalid("原生字符串超过 8192 字符");
        boolean binary = value.indexOf('\\') >= 0 || value.codePoints().anyMatch(Character::isISOControl)
                || engine == Engine.SPARK && (value.indexOf('\'') >= 0 || value.contains("${"));
        if (binary) {
            String hex = java.util.HexFormat.of().formatHex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return engine == Engine.SPARK ? "DECODE(UNHEX('" + hex + "'), 'UTF-8')" : "DECODE(X'" + hex + "', 'UTF-8')";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static boolean isNumber(String value) { try { new BigDecimal(value); return true; } catch (RuntimeException invalid) { return false; } }
    private static String number(String value) { try { var number = new BigDecimal(value); if (number.precision() > 38 || Math.abs(number.scale()) > 18) throw invalid("数值精度超出原生范围"); return number.toPlainString(); } catch (NumberFormatException | NullPointerException invalid) { throw invalid("数值配置无效"); } }
    private static String constant(Engine engine, String value, String type) {
        if (value == null || value.isBlank()) return "NULL";
        try {
            return switch (type) {
                case "Integer" -> "CAST(" + Integer.valueOf(value.trim()) + " AS INT)";
                case "Long" -> "CAST(" + Long.valueOf(value.trim()) + " AS BIGINT)";
                case "Double" -> { double parsed = Double.parseDouble(value.trim()); if (!Double.isFinite(parsed)) throw invalid("浮点常量必须为有限值"); yield "CAST(" + number(value.trim()) + " AS DOUBLE)"; }
                case "Boolean" -> Boolean.parseBoolean(value.trim()) ? "TRUE" : "FALSE";
                case "String" -> literal(engine, value);
                case "Date" -> {
                    try { yield "TIMESTAMP " + literal(engine, cn.hutool.core.date.DateUtil.formatDateTime(cn.hutool.core.date.DateUtil.parse(value.trim()))); }
                    catch (RuntimeException malformed) { throw invalid("日期常量无效"); }
                }
                default -> throw invalid("常量类型尚未原生适配：" + type);
            };
        } catch (NumberFormatException malformed) { throw invalid("常量与声明的数值类型或范围不符"); }
    }
    private static String splitTrim(Engine engine, String value, String mode) {
        String whitespace = "BOTH".equals(mode) ? "[\u0000-\u0020]" : "[\t-\r\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff\u202a\u0000\u3164\u2800\u200c\u180e]";
        return trimPattern(engine, value, mode, whitespace);
    }
    private static String trim(Engine engine, String value, String mode) {
        // 与 EtlFieldRules 的 Unicode 空白集合相同，避免 SQL TRIM 只处理普通空格。
        String whitespace = "[\t-\r\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]";
        return trimPattern(engine, value, mode, whitespace);
    }
    private static String trimPattern(Engine engine, String value, String mode, String whitespace) {
        String pattern = switch (mode) { case "NONE" -> null; case "BOTH" -> "^" + whitespace + "+|" + whitespace + "+$";
            case "LEFT" -> "^" + whitespace + "+"; case "RIGHT" -> whitespace + "+$"; default -> throw invalid("去空格方式无效"); };
        return pattern == null ? value : "REGEXP_REPLACE(" + value + ", " + literal(engine, pattern) + ", '')";
    }
    private static JSONArray array(JSONObject config, String key, boolean required) { var array = config.getJSONArray(key); if (array == null) array = new JSONArray(); if (array.size() > 200 || required && array.isEmpty()) throw invalid("配置 " + key + " 需要 1 至 200 项"); return array; }
    private static String required(JSONObject config, String key) { String value = config.getStr(key); if (value == null || value.isBlank()) throw invalid("缺少配置：" + key); return value; }
    private static boolean empty(Object value) { return value == null || value instanceof String string && string.isBlank() || value instanceof Collection<?> collection && collection.isEmpty(); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
