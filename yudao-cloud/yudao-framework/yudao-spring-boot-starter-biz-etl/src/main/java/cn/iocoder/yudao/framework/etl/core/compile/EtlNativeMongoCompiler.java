package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.math.BigDecimal;
import java.util.*;

/** MongoDB Spark 官方连接器的有界读写编译，连接、任务授权与插件安装由受管绑定提供。 */
public final class EtlNativeMongoCompiler {
    private static final Set<String> KEYS = Set.of("databaseId", "resourceBindingId", "tableName", "columns", "ifSql", "sql",
            "readMode", "whereCondition", "writeModeType", "primaryKeys", "batchSize", "writeMode", "name", "description", "fieldMappings");
    private static final Set<String> QUERY_OPERATORS = Set.of("$eq", "$ne", "$gt", "$gte", "$lt", "$lte", "$in", "$nin", "$exists",
            "$type", "$regex", "$options", "$all", "$elemMatch", "$size", "$mod", "$not", "$and", "$or", "$nor");

    private EtlNativeMongoCompiler() { }

    public static Set<String> supportedCodes(EtlNativeSqlCompiler.Engine engine, boolean streaming) {
        return engine != null && !streaming ? Set.of("read-mongodb", "write-mongodb") : Set.of();
    }

    /** 输入读取完整声明字段，输出显式使用 insert 或带键的增量更新，绝不隐式清空集合。 */
    public static EtlNativeConnectorCompiler.TablePlan compile(EtlNativeSqlCompiler.Engine engine, boolean streaming,
            String identity, String code, Map<String, Object> input, EtlDataxConnectorCompiler.Binding binding) {
        if (!supportedCodes(engine, streaming).contains(code)) throw invalid("MongoDB 原生连接器当前仅支持有界批处理");
        if (binding == null || !binding.connectorCodes().contains(code)) throw invalid("MongoDB 连接器未获得当前任务的受管资源授权");
        if (binding.authentication() != EtlDataxConnectorCompiler.Authentication.ANONYMOUS)
            throw invalid("MongoDB 认证材料需要受管 Worker 秘密引用，不能写入任务 SQL");
        if (!binding.installedPlugins().contains(engine == EtlNativeSqlCompiler.Engine.SPARK ? "spark-mongodb" : "flink-bounded-mongodb")) throw invalid("当前工作环境尚未确认安装 MongoDB Spark 连接器");
        if (input == null || JSONUtil.toJsonStr(input).length() > 500_000) throw invalid("MongoDB 配置缺失或超过大小限制");
        var config = JSONUtil.parseObj(input);
        for (String key : config.keySet()) if (!KEYS.contains(key)) throw invalid("MongoDB 参数尚未提供等价原生映射：" + key);
        if (Integer.parseInt(integer(config.get("ifSql") == null ? 0 : config.get("ifSql"), 0, 1, "MongoDB连接方式")) != 0
                || !config.getStr("sql", "").isBlank()) throw invalid("MongoDB 连接器不执行关系型 SQL，请使用 JSON 查询条件");
        if (!"FULL".equals(config.getStr("readMode", "FULL"))) throw invalid("MongoDB 增量读取需要持久游标，当前原生路径只执行有界全量读取");
        String database = identifier(binding.database(), "受管 MongoDB 数据库");
        String collection = config.getStr("tableName", "");
        if (!collection.matches("[\\p{L}_][\\p{L}\\p{N}_.-]{0,119}") || collection.startsWith("system.")) throw invalid("MongoDB 集合名称无效");
        if (binding.addresses().isEmpty() || binding.addresses().size() > 16) throw invalid("MongoDB 受管服务地址缺失或超限");
        for (String address : binding.addresses()) {
            if (address == null || !address.matches("[A-Za-z0-9.-]+:[0-9]{1,5}")) throw invalid("MongoDB 受管服务地址无效");
            integer(address.substring(address.lastIndexOf(':') + 1), 1, 65535, "MongoDB端口");
        }
        boolean output = code.startsWith("write-");
        if (!output && config.containsKey("fieldMappings")) throw invalid("MongoDB 输入不接受输出字段映射");
        var mappings = output ? EtlNativeOutputMappings.require(config) : List.<Map<String, Object>>of();
        String uri = "mongodb://" + String.join(",", binding.addresses()) + "/";
        var columns = columns(config, output); var options = new LinkedHashMap<String, String>();
        String prefix = output ? "spark.mongodb.write." : "spark.mongodb.read.";
        options.put(prefix + "connection.uri", uri); options.put(prefix + "database", database); options.put(prefix + "collection", collection);
        if (output) {
            if (!config.getStr("whereCondition", "").isBlank()) throw invalid("MongoDB 输出条件不能替代更新主键，请使用明确的主键字段");
            int mode = Integer.parseInt(integer(config.get("writeModeType") == null ? 0 : config.get("writeModeType"), 0, 2, "MongoDB写入模式"));
            String requested = config.getStr("writeMode", mode == 2 ? "UPDATE" : "INSERT").toUpperCase(Locale.ROOT);
            if (mode == 1 || !Set.of("INSERT", "UPDATE", "REPLACE").contains(requested)) throw invalid("MongoDB 输出禁止隐式清空、覆盖集合或未知写入模式");
            if (mode != 0 && mode != 2) throw invalid("MongoDB 写入模式无效");
            if (mode == 2 && "INSERT".equals(requested)) throw invalid("MongoDB 增量更新模式与 INSERT 配置冲突");
            if (!"INSERT".equals(requested)) {
                String rawKeys = config.getStr("primaryKeys", "");
                var keys = Arrays.stream(rawKeys.split(",", -1)).map(String::trim).toList();
                var targets = columns.stream().map(Column::target).collect(java.util.stream.Collectors.toSet());
                if (keys.isEmpty() || keys.stream().anyMatch(key -> !targets.contains(key)) || keys.stream().distinct().count() != keys.size())
                    throw invalid("MongoDB 增量更新必须配置不重复、存在于输出字段中的更新主键");
                options.put(prefix + "idFieldList", String.join(",", keys)); options.put(prefix + "upsertDocument", "true");
            } else if (!config.getStr("primaryKeys", "").isBlank()) throw invalid("MongoDB INSERT 不应配置增量更新主键");
            options.put(prefix + "operationType", requested.toLowerCase(Locale.ROOT));
            options.put(prefix + "maxBatchSize", integer(config.get("batchSize") == null ? 1000 : config.get("batchSize"), 1, 100_000, "MongoDB批次条数"));
            options.put(prefix + "ordered", "true"); options.put(prefix + "convertJson", "false"); options.put(prefix + "ignoreNullValues", "false");
        } else {
            for (String key : List.of("writeModeType", "writeMode", "batchSize", "primaryKeys")) if (config.containsKey(key))
                throw invalid("MongoDB 输入不接受输出配置：" + key);
            options.put(prefix + "mode", "FAILFAST");
            options.put(prefix + "partitioner", "com.mongodb.spark.sql.connector.read.partitioner.SinglePartitionPartitioner");
            String where = config.getStr("whereCondition", "");
            if (!where.isBlank()) {
                if (!JSONUtil.isTypeJSONObject(where)) throw invalid("MongoDB 查询条件必须为只读 JSON 对象");
                var query = JSONUtil.parseObj(where); validateQuery(query, 0);
                options.put(prefix + "aggregation.pipeline", JSONUtil.toJsonStr(List.of(Map.of("$match", query))));
            }
        }
        if (engine == EtlNativeSqlCompiler.Engine.FLINK) {
            var shape = new LinkedHashMap<String, String>(); columns.forEach(column -> shape.put(column.target(), column.type()));
            var plan = new JSONObject().set("uri", uri).set("database", database).set("collection", collection).set("maxRows", 100000).set("maxBytes", 67108864)
                    .set("fields", columns.stream().map(column -> Map.of("source", column.source(), "target", column.target(), "type", column.type())).toList())
                    .set("where", config.getStr("whereCondition", "")).set("operation", options.getOrDefault(prefix + "operationType", "insert"))
                    .set("batchSize", Integer.parseInt(options.getOrDefault(prefix + "maxBatchSize", "1000"))).set("primaryKeys", options.getOrDefault(prefix + "idFieldList", ""));
            return EtlNativeFlinkConnectorSql.table(identity, "mongodb", output, shape, plan, mappings);
        }
        String alias = "dh_native_mongo_" + DigestUtil.sha256Hex(Objects.requireNonNull(identity)).substring(0, 24);
        String raw = output ? alias : alias + "_raw";
        String ddl = "CREATE TEMPORARY VIEW " + quote(raw) + " ("
                + String.join(", ", columns.stream().map(column -> quote(output ? column.target() : column.source()) + " " + column.type()).toList())
                + ") USING mongodb OPTIONS (" + String.join(", ", options.entrySet().stream()
                .map(entry -> quote(entry.getKey()) + " " + literal(entry.getValue())).toList()) + ");\n";
        if (!output) ddl += "CREATE TEMPORARY VIEW " + quote(alias) + " AS SELECT " + String.join(", ", columns.stream()
                .map(column -> quote(column.source()) + " AS " + quote(column.target())).toList()) + " FROM " + quote(raw) + ";\n";
        var types = new LinkedHashMap<String, String>(); columns.forEach(column -> types.put(column.target(), column.type()));
        return new EtlNativeConnectorCompiler.TablePlan(ddl, new EtlNativeSqlCompiler.Table(List.of(alias), types, ProductTypeEnum.UNKNOWN, null, alias, false), mappings);
    }

    private record Column(String source, String target, String type) { }

    private static List<Column> columns(JSONObject config, boolean output) {
        var values = config.getJSONArray("columns");
        if (values == null || values.isEmpty() || values.size() > 1024) throw invalid("MongoDB 需要1至1024个明确类型的属性字段");
        var result = new ArrayList<Column>(); var sources = new HashSet<String>(); var targets = new HashSet<String>();
        for (Object value : values) {
            var field = JSONUtil.parseObj(value);
            for (String key : field.keySet()) if (!Set.of("name", "type", "key", "jsonPath", "comment", "length", "precision", "format", "index").contains(key))
                throw invalid("MongoDB 字段参数尚未支持：" + key);
            if (field.containsKey("index") && Integer.parseInt(integer(field.get("index"), 0, 1023, "MongoDB字段序号")) != result.size()) throw invalid("MongoDB 字段序号必须与声明顺序一致");
            String target = identifier(field.getStr("name"), "MongoDB输出字段");
            String key = field.getStr("key", field.getStr("jsonPath", target)); if (key.isBlank()) key = target;
            if (!output && field.containsKey("key") && field.containsKey("jsonPath") && !Objects.equals(field.getStr("key"), field.getStr("jsonPath")))
                throw invalid("MongoDB 字段不能同时配置不同的取值路径");
            if (key.startsWith("$.")) key = key.substring(2);
            if (output && !field.getStr("jsonPath", "").isBlank() && !field.getStr("jsonPath").equals(target)) throw invalid("MongoDB 输出不接受输入 JSON 取值路径");
            String source = output ? target : identifier(key, "MongoDB源字段");
            if (!sources.add(source) || !targets.add(target)) throw invalid("MongoDB 属性字段重复");
            if (!field.getStr("format", "").isBlank()) throw invalid("MongoDB 原生字段不接受文本日期格式，请使用字段转换组件");
            String type = switch (field.getStr("type", "").toUpperCase(Locale.ROOT)) {
                case "STRING", "VARCHAR", "TEXT", "CHAR", "INTERNETADDRESS" -> "STRING";
                case "INTEGER", "INT", "LONG", "BIGINT", "SMALLINT", "TINYINT" -> "BIGINT";
                case "NUMBER", "DOUBLE" -> "DOUBLE";
                case "FLOAT" -> "FLOAT";
                case "BOOLEAN" -> "BOOLEAN";
                case "BINARY", "BYTES" -> "BINARY";
                case "DATE", "TIMESTAMP" -> "TIMESTAMP";
                case "BIGNUMBER", "DECIMAL", "NUMERIC" -> {
                    int precision = Integer.parseInt(integer(field.get("length") == null || field.getStr("length").isBlank() ? 38 : field.get("length"), 1, 38, "Decimal总位数"));
                    int scale = Integer.parseInt(integer(field.get("precision") == null || field.getStr("precision").isBlank() ? 18 : field.get("precision"), 0, precision, "Decimal小数位数"));
                    yield "DECIMAL(" + precision + "," + scale + ")";
                }
                default -> throw invalid("MongoDB 属性字段类型未知或尚未适配：" + target);
            };
            if (!type.startsWith("DECIMAL(") && (!field.getStr("length", "").isBlank() || !field.getStr("precision", "").isBlank()))
                throw invalid("MongoDB 非 Decimal 字段的长度或精度约束尚未适配：" + target);
            result.add(new Column(source, target, type));
        }
        return result;
    }

    private static void validateQuery(Object value, int depth) {
        if (depth > 16) throw invalid("MongoDB 查询条件嵌套过深");
        if (value instanceof Map<?, ?> map) map.forEach((key, child) -> {
            String name = String.valueOf(key);
            if (name.startsWith("$") && !QUERY_OPERATORS.contains(name)) throw invalid("MongoDB 查询不允许脚本、写入或未知操作符：" + name);
            if (name.indexOf('\0') >= 0) throw invalid("MongoDB 查询字段包含非法字符");
            validateQuery(child, depth + 1);
        });
        else if (value instanceof Iterable<?> list) list.forEach(child -> validateQuery(child, depth + 1));
    }
    private static String identifier(String value, String label) {
        if (value == null || !value.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,127}")) throw invalid(label + "必须为普通名称，不接受嵌套路径"); return value;
    }
    private static String integer(Object raw, int min, int max, String name) {
        try { int value = new BigDecimal(String.valueOf(raw)).intValueExact(); if (value < min || value > max) throw new ArithmeticException(); return String.valueOf(value); }
        catch (RuntimeException invalid) { throw invalid(name + "必须为范围内整数"); }
    }
    private static String quote(String value) { return "`" + value.replace("`", "``") + "`"; }
    private static String literal(String value) { return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
