package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.script.*;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldContract;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 原生 SQL 与受管批次 stage 的组合规划；只有隔离 broker 接收源码。 */
public final class EtlNativeScriptCompiler {
    private EtlNativeScriptCompiler() { }
    public record Definition(String node, String resource, String source, EtlScriptRunner.Language language,
            List<EtlNativeScriptProgram.Field> inputFields, List<EtlNativeScriptProgram.Field> outputFields,
            boolean partitionBatch, int maximumRows, long flushMillis) { }
    @FunctionalInterface public interface Bindings { EtlScriptBrokerClient.Binding bind(Definition definition); }
    public record Plan(EtlNativeSqlCompiler.Analysis analysis, EtlNativeScriptProgram program) { }

    /** 仅初始化 token 节点可作为接口的控制上游；不得把任意 tokenKey 全局授权。 */
    public static Set<String> contextKeys(String node, List<EtlNodeDef> nodes, List<EtlEdgeDef> edges) {
        var byId = new HashMap<String, EtlNodeDef>(); nodes.forEach(value -> byId.put(value.getNo(), value));
        var result = new HashSet<String>();
        for (var edge : edges) if (node.equals(edge.getTargetNo())) {
            var upstream = byId.get(edge.getSourceNo());
            if (upstream != null && "custom-token".equals(upstream.getToolCode())) {
                if (edges.stream().anyMatch(value -> upstream.getNo().equals(value.getTargetNo()))) throw invalid("初始化认证脚本不能拥有数据上游");
                String key = JSONUtil.parseObj(upstream.getNodeJson()).getStr("tokenKey", "");
                if (!key.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || !result.add(key)) throw invalid("认证变量编号缺失或重复");
            }
        }
        return Set.copyOf(result);
    }

    public static Plan compile(EtlNativeSqlCompiler.Engine engine, boolean streaming, int parallelism,
            List<EtlNodeDef> nodes, List<EtlEdgeDef> edges, EtlNativeSqlCompiler.Tables tables,
            String initializationSql, Bindings bindings) {
        var tokens = nodes.stream().filter(node -> "custom-token".equals(node.getToolCode()) && edges.stream().noneMatch(edge -> node.getNo().equals(edge.getTargetNo()))).toList();
        var tokenIds = new HashSet<String>(); var keys = new HashSet<String>(); var initializers = new ArrayList<EtlNativeScriptProgram.Initializer>();
        for (var token : tokens) {
            var config = JSONUtil.parseObj(token.getNodeJson()); String key = config.getStr("tokenKey", "");
            if (!key.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || !keys.add(key)) throw invalid("认证变量编号缺失或重复");
            var outgoing = edges.stream().filter(edge -> token.getNo().equals(edge.getSourceNo())).toList();
            if (outgoing.isEmpty() || outgoing.stream().anyMatch(edge -> nodes.stream().noneMatch(target -> target.getNo().equals(edge.getTargetNo()) && "read-db".equals(target.getToolCode())))) throw invalid("初始化认证脚本必须直接控制已准备的数据源启动");
            // Token只在应用启动阶段执行一次，采用空输入整批合同，不继承实时数据行脚本的窗口策略。
            var definition = definition(token.getNo(), "custom-token", config, Map.of(), engine, false);
            initializers.add(new EtlNativeScriptProgram.Initializer(token.getNo(), key, bindings.bind(definition))); tokenIds.add(token.getNo());
        }
        var stages = new ArrayList<EtlNativeScriptProgram.Stage>(); var inserts = new ArrayList<String>();
        var analysis = EtlNativeSqlCompiler.analyzeWithScriptStages(engine, streaming, nodes.stream().filter(node -> !tokenIds.contains(node.getNo())).toList(),
                edges.stream().filter(edge -> !tokenIds.contains(edge.getSourceNo())).toList(), tables, new EtlNativeSqlCompiler.ScriptStages() {
                    @Override public EtlNativeSqlCompiler.ScriptTable materialize(String id, String code, String json, String sql, Map<String,String> fields) {
                        var definition = definition(id, code, JSONUtil.parseObj(json), fields, engine, streaming);
                        if (definition.language == EtlScriptRunner.Language.QDATA_TOKEN) throw invalid("有数据上游的 token 脚本尚无逐批认证作用域；请使用初始化认证节点");
                        String view = "dh_native_script_" + sha(id + ":" + sql).substring(0,24);
                        stages.add(new EtlNativeScriptProgram.Stage(id, sql, view, definition.inputFields, definition.outputFields, bindings.bind(definition), definition.partitionBatch, definition.maximumRows, definition.flushMillis));
                        var output = new LinkedHashMap<String,String>(); definition.outputFields.forEach(field -> output.put(field.name(), field.sqlType()));
                        return new EtlNativeSqlCompiler.ScriptTable(view, output);
                    }
                    @Override public void insert(String sql) { inserts.add(sql); }
                });
        var shapes = new LinkedHashMap<>(analysis.nodes()); tokens.forEach(token -> shapes.put(token.getNo(), new EtlNativeSqlCompiler.NodeFields(Map.of(),Map.of())));
        var initialization = initializationSql.isBlank() ? List.<String>of() : SqlScriptPlan.splitStatements(ProductTypeEnum.MYSQL, initializationSql, ";");
        return new Plan(new EtlNativeSqlCompiler.Analysis(analysis.sql(),Collections.unmodifiableMap(shapes)),
                new EtlNativeScriptProgram(engine.name(), streaming, parallelism, initialization, initializers, stages, inserts));
    }

    private static Definition definition(String id, String code, JSONObject config, Map<String,String> input, EtlNativeSqlCompiler.Engine engine, boolean streaming) {
        var allowed = Set.of("code","classPath","hasSetDefaultCode","tokenKey","outputFields","batchSize","resourceBindingId","batchPolicy","maximumRows","flushMillis","workerState");
        if (config.keySet().stream().anyMatch(key -> !allowed.contains(key))) throw invalid("原生脚本存在未支持的参数");
        String source = config.getStr("code", ""); if (source.isBlank()) throw invalid("请填写脚本源码");
        String declaredClass = config.getStr("classPath", "");
        if (!declaredClass.isBlank() && !declaredClass.equals(EtlJavaCompiler.sourceClassName(source))) throw invalid("脚本classPath与实际worker编译类不一致");
        String resource = config.getStr("resourceBindingId", ""); if (!resource.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) throw invalid("请选择当前任务获授权的脚本资源绑定");
        if (config.containsKey("batchSize")) throw invalid("历史batchSize不能静默映射整批语义，请明确清除后声明maximumRows");
        if (!streaming && config.containsKey("flushMillis") && integer(config.get("flushMillis"),0,300000)!=0) throw invalid("批处理不能保留实时flushMillis配置");
        boolean token = "custom-token".equals(code);
        if (token && config.getJSONArray("outputFields") != null && !config.getJSONArray("outputFields").isEmpty()) throw invalid("认证脚本不声明令牌输出字段");
        boolean qdata = source.contains("EtlBase");
        var language = token ? EtlScriptRunner.Language.QDATA_TOKEN : qdata ? EtlScriptRunner.Language.QDATA_JAVA : EtlScriptRunner.Language.JAVA;
        if (!"BATCH".equals(config.getStr("workerState", ""))) throw invalid("请明确选择每批独立 worker 状态，不允许暗中复用用户对象");
        boolean partition = "PARTITION".equals(config.getStr("batchPolicy", ""));
        if (streaming && partition || !streaming && !partition) throw invalid("批任务须声明完整分区批次；实时任务须声明 COUNT_OR_TIME 有限窗口");
        if (streaming && !"COUNT_OR_TIME".equals(config.getStr("batchPolicy", ""))) throw invalid("实时脚本必须声明有限条数与时间窗口");
        int maximum = integer(config.get("maximumRows"), 1, 10000); long flush = streaming ? integer(config.get("flushMillis"),10,300000) : 0;
        var in = input.entrySet().stream().map(entry -> new EtlNativeScriptProgram.Field(entry.getKey(), canonical(entry.getValue(),engine),null)).toList();
        var out = new ArrayList<EtlNativeScriptProgram.Field>();
        if (!token) {
            var fields = config.getJSONArray("outputFields");
            if (fields == null || fields.isEmpty()) out.addAll(in);
            else for (Object field : fields) {
                var item = JSONUtil.parseObj(field);
                if (item.keySet().stream().anyMatch(key -> !Set.of("name","type","columnName","columnType","source","nullable").contains(key))) throw invalid("脚本输出字段存在未支持的参数");
                out.add(new EtlNativeScriptProgram.Field(item.getStr("name",item.getStr("columnName")),canonical(item.getStr("type",item.getStr("columnType")),engine),item.getBool("nullable")));
            }
        }
        return new Definition(id, resource, source, language, in, List.copyOf(out),partition,maximum,flush);
    }
    public static List<EtlFieldContract> contracts(List<EtlNativeScriptProgram.Field> fields) {
        return fields.stream().map(field -> {
            String sql = field.sqlType(); String type = switch(sql) { case "STRING" -> "String"; case "TINYINT","SMALLINT","INT" -> "Integer"; case "BIGINT" -> "Long"; case "FLOAT","DOUBLE" -> "Double"; case "BOOLEAN" -> "Boolean"; default -> sql.startsWith("DECIMAL") ? "Decimal" : "Date"; };
            Integer precision=null,scale=null;
            if (sql.startsWith("DECIMAL(")) { var shape=sql.substring(8,sql.length()-1).split(","); precision=Integer.valueOf(shape[0]);scale=Integer.valueOf(shape[1]); }
            return new EtlFieldContract(field.name(),type,field.nullable(),precision,scale,null);
        }).toList();
    }
    public static String canonical(String value, EtlNativeSqlCompiler.Engine engine) {
        if (value == null) throw invalid("脚本字段类型未知");
        String type=value.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        type=switch(type){ case "VARCHAR","TEXT","CHAR","CHARACTERVARYING"->"STRING"; case "INTEGER","INT4"->"INT"; case "LONG","INT8"->"BIGINT";case "BOOL"->"BOOLEAN";case "FLOAT4"->"FLOAT";case "FLOAT8"->"DOUBLE";case "TIMESTAMP","DATETIME"->"TIMESTAMP(6)";default->type;};
        if (type.matches("(?:VARCHAR|CHAR)\\([0-9]+\\)")) type="STRING";
        new EtlNativeScriptProgram.Field("type",type,null);
        if(engine==EtlNativeSqlCompiler.Engine.SPARK && type.matches("TIMESTAMP\\([789]\\)")) throw invalid("Spark脚本阶段不能无损表达超过微秒的时间戳");
        return type;
    }
    private static int integer(Object value,int min,int max){try {int parsed=new java.math.BigDecimal(String.valueOf(value)).intValueExact();if(parsed<min||parsed>max)throw invalid("脚本批次预算超限");return parsed;}catch(RuntimeException failure){throw invalid("脚本批次预算须为明确的有界整数");}}
    public static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException(failure);}}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
