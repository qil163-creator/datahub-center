package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlOdpsTransport;
import com.aliyun.odps.PartitionSpec;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** MaxCompute 官方 Tunnel 原生批处理计划；云端执行能力与本地协议验收分别记录。 */
public final class EtlNativeOdpsCompiler {
    private EtlNativeOdpsCompiler() { }
    public record Resource(String table, String partition, Map<String, String> columns, boolean source, boolean sink, boolean allowOverwrite) {
        public Resource { columns = columns == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(columns)); }
    }
    public record Binding(String serviceEndpoint, String tunnelEndpoint, String project, String credentialRef,
                          List<Resource> resources, boolean sparkConnectorInstalled, boolean allowPlainHttp, boolean flinkConnectorInstalled) {
        public Binding(String serviceEndpoint, String tunnelEndpoint, String project, String credentialRef, List<Resource> resources,
                       boolean sparkConnectorInstalled, boolean allowPlainHttp) {
            this(serviceEndpoint, tunnelEndpoint, project, credentialRef, resources, sparkConnectorInstalled, allowPlainHttp, false);
        }
        public Binding { resources = resources == null ? List.of() : List.copyOf(resources); }
    }
    public static Set<String> supportedCodes(EtlNativeSqlCompiler.Engine engine, boolean streaming) {
        return engine != null && !streaming ? Set.of("read-odps", "write-odps") : Set.of();
    }
    public static EtlNativeConnectorCompiler.TablePlan compile(EtlNativeSqlCompiler.Engine engine, boolean streaming, String identity,
            String code, Map<String, Object> input, Binding binding, Set<String> functions, Set<String> secrets) {
        if (!supportedCodes(engine, streaming).contains(code)) throw invalid("ODPS Tunnel 仅支持有界批处理");
        if (binding == null || (engine == EtlNativeSqlCompiler.Engine.SPARK ? !binding.sparkConnectorInstalled() : !binding.flinkConnectorInstalled()) || binding.project() == null || !binding.project().matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
            throw invalid("ODPS 原生 SDK 尚未安装或项目未受管绑定");
        URI service = EtlOdpsTransport.endpoint(URI.create(binding.serviceEndpoint())), tunnel = EtlOdpsTransport.endpoint(URI.create(binding.tunnelEndpoint()));
        if ((!service.getScheme().equals("https") || !tunnel.getScheme().equals("https")) && !binding.allowPlainHttp()) throw invalid("ODPS 必须使用 TLS；隔离协议夹具需管理员显式声明");
        if (binding.credentialRef() == null || !binding.credentialRef().matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || !secrets.contains(binding.credentialRef()))
            throw invalid("ODPS 凭据引用未获当前任务、引擎和工作组授权");
        if (input == null || JSONUtil.toJsonStr(input).length() > 500000) throw invalid("ODPS 配置缺失或超限");
        var config = JSONUtil.parseObj(input); boolean output = code.startsWith("write-");
        for (String key : config.keySet()) if (!Set.of("databaseId", "tableName", "partition", "columns", "ifSql", "sql", "readMode", "whereCondition", "writeModeType", "writeMode", "batchSize", "preSql", "postSql", "maxRows", "maxBytes", "fieldMappings").contains(key))
            throw invalid("ODPS 参数尚未提供等价执行：" + key);
        if (!output && config.containsKey("fieldMappings")) throw invalid("ODPS 输入不接受输出字段映射");
        var mappings = output ? EtlNativeOutputMappings.require(config) : List.<Map<String, Object>>of();
        if (!"0".equals(config.getStr("ifSql", "0")) || !config.getStr("sql", "").isBlank()) throw invalid("ODPS Tunnel 使用精确授权表；独立 SQL 作业需要单独受管适配");
        if (!"FULL".equals(config.getStr("readMode", "FULL"))) throw invalid("ODPS 增量读取尚未提供持久游标");
        for (String key : List.of("whereCondition", "preSql", "postSql")) if (!config.getStr(key, "").isBlank()) throw invalid("ODPS Tunnel 不执行附加 SQL，请使用独立过滤或转换组件");
        String table = config.getStr("tableName", ""), partition = partition(config.getStr("partition", ""));
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw invalid("ODPS 表名无效");
        var choices = binding.resources().stream().filter(resource -> resource.table().equals(table) && partition(resource.partition()).equals(partition)
                && (output ? resource.sink() : resource.source())).toList();
        if (choices.size() != 1) throw invalid("ODPS 表、分区或读写方向尚未唯一授权");
        var resource = choices.getFirst();
        if (resource.columns().isEmpty() || resource.columns().size() > 1024) throw invalid("ODPS 受管表缺少完整原生字段合同");
        var columns = config.getJSONArray("columns"); if (columns == null || columns.isEmpty() || columns.size() > 1024) throw invalid("请选择 ODPS 属性字段");
        var declared = JSONUtil.createArray(); var names = new LinkedHashSet<String>();
        for (Object value : columns) {
            var field = value instanceof String name ? JSONUtil.createObj().set("name", name) : JSONUtil.parseObj(value);
            for (String key : field.keySet()) if (!Set.of("name", "type", "key", "index", "comment", "length", "precision").contains(key)) throw invalid("ODPS 字段包含未适配参数");
            String name = field.getStr("name", ""), actual = resource.columns().get(name);
            if (!name.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,127}") || actual == null || !names.add(name)) throw invalid("ODPS 属性字段未在受管合同内或重复");
            if (!output && !field.getStr("key", name).equals(name)) throw invalid("ODPS 输入不接受字段取值路径");
            if (field.containsKey("index") && integer(field.get("index"), -1, 0, 1023) != names.size() - 1) throw invalid("ODPS 字段序号必须与声明顺序一致");
            String type = sqlType(actual), requested = field.getStr("type", type).toUpperCase(Locale.ROOT);
            if (!requested.equals(actual.toUpperCase(Locale.ROOT)) && !requested.equals(type) && !requested.equals(alias(type)) && !(type.startsWith("DECIMAL(") && Set.of("BIGNUMBER", "DECIMAL", "NUMERIC").contains(requested)))
                throw invalid("ODPS 声明类型与受管原生合同不一致");
            var normalized = JSONUtil.createObj().set("name", name).set("type", type);
            if (type.startsWith("DECIMAL(")) {
                String[] shape = type.substring(8, type.length() - 1).split(","); int precision = Integer.parseInt(shape[0]), scale = Integer.parseInt(shape[1]);
                if (field.get("length") != null && !field.getStr("length").isBlank() && integer(field.get("length"), precision, 1, 38) != precision
                        || field.get("precision") != null && !field.getStr("precision").isBlank() && integer(field.get("precision"), scale, 0, 38) != scale)
                    throw invalid("ODPS 小数总位数或精度与物理合同不一致");
                normalized.set("type", "DECIMAL").set("length", precision).set("precision", scale);
            } else if (field.get("length") != null && !field.getStr("length").isBlank() || field.get("precision") != null && !field.getStr("precision").isBlank())
                throw invalid("ODPS 非 Decimal 的额外长度或精度约束尚未适配");
            declared.add(normalized);
        }
        if (output && !new ArrayList<>(names).equals(new ArrayList<>(resource.columns().keySet()))) throw invalid("ODPS Tunnel 写入需要按原生表完整字段顺序声明");
        var plan = JSONUtil.createObj().set("serviceEndpoint", service.toString()).set("tunnelEndpoint", tunnel.toString()).set("project", binding.project())
                .set("table", table).set("partition", partition).set("credentialRef", binding.credentialRef()).set("columns", resource.columns())
                .set("selectedColumns", names).set("allowPlainHttp", binding.allowPlainHttp());
        if (!output) {
            for (String key : List.of("writeModeType", "writeMode", "batchSize")) if (config.containsKey(key)) throw invalid("ODPS 输入不能包含输出参数");
            if (engine == EtlNativeSqlCompiler.Engine.SPARK && !functions.contains("DH_READ_ODPS")) throw invalid("工作环境尚未注册受管 ODPS Tunnel 行源");
            plan.set("maxRows", integer(config.get("maxRows"), 100000, 1, 1000000)).set("maxBytes", integer(config.get("maxBytes"), 16777216, 1, 67108864));
            return EtlNativeHttpCompiler.rowSource(engine, identity, EtlNativeDeclaredColumns.parse(declared), plan, "DH_READ_ODPS", "odps");
        }
        for (String key : List.of("ifSql", "sql", "readMode", "maxRows", "maxBytes")) if (config.containsKey(key)) throw invalid("ODPS 输出不能包含输入参数");
        int mode = integer(config.get("writeModeType"), 2, 1, 2);
        if (mode == 1 && !resource.allowOverwrite()) throw invalid("ODPS 当前表分区未授权全量覆盖");
        if (!config.getStr("writeMode", "INSERT").equals("INSERT")) throw invalid("ODPS Tunnel 不支持未声明的更新模式");
        plan.set("overwrite", mode == 1).set("batchSize", integer(config.get("batchSize"), 1000, 1, 10000));
        var shape = EtlNativeHttpCompiler.rowSource(identity, EtlNativeDeclaredColumns.parse(declared), plan, "DH_READ_ODPS", "odps").table().columns();
        if (engine == EtlNativeSqlCompiler.Engine.FLINK) return EtlNativeFlinkConnectorSql.table(identity, "odps", true, shape, plan, mappings);
        String alias = "dh_native_odps_" + DigestUtil.sha256Hex(identity).substring(0, 24), payload = Base64.getEncoder().encodeToString(plan.toString().getBytes(StandardCharsets.UTF_8));
        String ddl = "CREATE TEMPORARY VIEW `" + alias + "` (" + String.join(", ", shape.entrySet().stream().map(field -> "`" + field.getKey() + "` " + field.getValue()).toList())
                + ") USING cn.iocoder.yudao.framework.etl.spark.EtlOdpsProvider OPTIONS (plan '" + payload + "');\n";
        return new EtlNativeConnectorCompiler.TablePlan(ddl, new EtlNativeSqlCompiler.Table(List.of(alias), shape, ProductTypeEnum.UNKNOWN, null, alias, false), mappings);
    }
    public static String partition(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.length() > 2048 || value.codePoints().anyMatch(Character::isISOControl)) throw invalid("ODPS 分区规格无效");
        try { return new PartitionSpec(value).toString(); } catch (RuntimeException failure) { throw invalid("ODPS 分区规格无效"); }
    }
    public static String sqlType(String nativeType) {
        String type = Objects.toString(nativeType, "").toUpperCase(Locale.ROOT).replace(" ", "");
        if (Set.of("BIGINT", "INT", "DOUBLE", "BOOLEAN", "DATE", "STRING").contains(type)) return type;
        if (Set.of("SMALLINT", "TINYINT").contains(type)) return "INT";
        if (type.equals("FLOAT")) return "DOUBLE";
        if (type.equals("DATETIME") || type.equals("TIMESTAMP")) return "TIMESTAMP";
        if (type.matches("DECIMAL\\(([1-9]|[12][0-9]|3[0-8]),([0-9]|[12][0-9]|3[0-8])\\)")) {
            String[] shape = type.substring(8, type.length() - 1).split(","); if (Integer.parseInt(shape[1]) <= Integer.parseInt(shape[0])) return type;
        }
        throw invalid("ODPS 原生字段类型尚未适配：" + type);
    }
    private static String alias(String type) { return switch (type) { case "BIGINT" -> "LONG"; case "INT" -> "INTEGER"; case "DOUBLE" -> "NUMBER"; default -> type; }; }
    private static int integer(Object value, int fallback, int min, int max) {
        try { int parsed = value == null ? fallback : new java.math.BigDecimal(value.toString()).intValueExact(); if (parsed < min || parsed > max) throw new ArithmeticException(); return parsed; }
        catch (RuntimeException failure) { throw invalid("ODPS 数值参数必须为范围内整数"); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
