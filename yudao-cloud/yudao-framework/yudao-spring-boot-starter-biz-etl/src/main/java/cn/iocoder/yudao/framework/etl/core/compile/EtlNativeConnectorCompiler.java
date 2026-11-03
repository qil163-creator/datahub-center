package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.math.BigDecimal;
import java.util.*;

/** 受管外部连接器到原生会话表的编译；不读取凭据，不建立连接，不把客户端任意参数透传到集群。 */
public final class EtlNativeConnectorCompiler {
    private static final Set<String> NODE_KEYS = Set.of("databaseId", "topic", "columns", "configParameters", "batchSize", "messageFormat", "fieldMappings");
    private static final Set<String> FIELD_KEYS = Set.of("name", "type", "key", "jsonPath", "length", "precision", "format", "comment", "index");
    private static final Set<String> CONSUMER_NUMBERS = Set.of("fetch.min.bytes", "fetch.max.bytes", "fetch.max.wait.ms",
            "max.partition.fetch.bytes", "max.poll.records", "request.timeout.ms", "session.timeout.ms", "heartbeat.interval.ms");
    private static final Set<String> PRODUCER_NUMBERS = Set.of("linger.ms", "request.timeout.ms", "delivery.timeout.ms", "max.request.size", "transaction.timeout.ms");

    public enum Authentication { NONE, REQUIRED }

    /** 仅来自管理员配置；读写 Topic 权限分开，安装声明也不能由节点覆盖。 */
    public record KafkaBinding(String bootstrapServers, Set<String> sourceTopics, Set<String> sinkTopics,
                               Authentication authentication, boolean flinkConnectorInstalled, boolean sparkConnectorInstalled) {
        public KafkaBinding(String bootstrapServers, Set<String> sourceTopics, Set<String> sinkTopics, Authentication authentication, boolean flinkConnectorInstalled) {
            this(bootstrapServers, sourceTopics, sinkTopics, authentication, flinkConnectorInstalled, false);
        }
        public KafkaBinding {
            sourceTopics = sourceTopics == null ? Set.of() : Set.copyOf(sourceTopics);
            sinkTopics = sinkTopics == null ? Set.of() : Set.copyOf(sinkTopics);
            authentication = authentication == null ? Authentication.REQUIRED : authentication;
        }
    }

    public record TablePlan(String initializationSql, EtlNativeSqlCompiler.Table table, List<Map<String, Object>> fieldMappings) {
        public TablePlan(String initializationSql, EtlNativeSqlCompiler.Table table) { this(initializationSql, table, List.of()); }
        public TablePlan {
            Objects.requireNonNull(initializationSql); Objects.requireNonNull(table);
            table = new EtlNativeSqlCompiler.Table(List.copyOf(table.path()), Collections.unmodifiableMap(new LinkedHashMap<>(table.columns())),
                    table.product(), table.schema(), table.physicalTable(), table.continuousSource(), table.jdbcUpsertSink());
            fieldMappings = fieldMappings == null ? List.of() : fieldMappings.stream().map(value -> Collections.unmodifiableMap(new LinkedHashMap<>(value))).toList();
        }
    }

    private EtlNativeConnectorCompiler() { }

    /** 只声明编译器代码覆盖范围，具体任务仍必须通过绑定、Topic、格式和位点校验。 */
    public static Set<String> supportedCodes(EtlNativeSqlCompiler.Engine engine, boolean streaming) {
        return engine == EtlNativeSqlCompiler.Engine.FLINK || !streaming ? Set.of("read-kafka", "write-kafka") : Set.of();
    }

    /** 配置中的每个执行参数必须被消费，无法等价映射的参数在编译期拒绝。 */
    public static TablePlan kafka(EtlNativeSqlCompiler.Engine engine, boolean streaming, String nodeIdentity,
                                  String code, Map<String, Object> config, KafkaBinding binding) {
        if (engine == EtlNativeSqlCompiler.Engine.SPARK && streaming) throw invalid("Spark Kafka集成当前仅支持有界批处理");
        if (!Set.of("read-kafka", "write-kafka").contains(code)) throw invalid("不是 Kafka 输入输出组件");
        if (config == null || JSONUtil.toJsonStr(config).length() > 500_000) throw invalid("Kafka 配置缺失或超过大小限制");
        var values = JSONUtil.parseObj(config); rejectUnknown(values.keySet(), NODE_KEYS, "Kafka 节点");
        if (binding == null || (engine == EtlNativeSqlCompiler.Engine.FLINK ? !binding.flinkConnectorInstalled() : !binding.sparkConnectorInstalled()))
            throw invalid("Kafka数据源未绑定当前引擎已安装的连接器工作环境");
        if (binding.authentication() != Authentication.NONE) throw invalid("Kafka 认证材料需要受管 Worker 秘密引用，不能写入任务 SQL");
        String servers = binding.bootstrapServers();
        if (servers == null || !servers.matches("[A-Za-z0-9.-]+:[0-9]{1,5}(,[A-Za-z0-9.-]+:[0-9]{1,5})*"))
            throw invalid("Kafka 受管服务地址无效");
        for (String server : servers.split(",")) number(server.substring(server.lastIndexOf(':') + 1), 1, 65535, "Kafka 端口");
        String topic = values.getStr("topic", "");
        if (!topic.matches("[A-Za-z0-9._-]{1,249}") || Set.of(".", "..").contains(topic)) throw invalid("Kafka 主题名称无效");
        boolean output = "write-kafka".equals(code);
        if (!output && values.containsKey("fieldMappings")) throw invalid("Kafka 输入不接受输出字段映射");
        var mappings = output ? EtlNativeOutputMappings.require(values) : List.<Map<String, Object>>of();
        if (!(output ? binding.sinkTopics() : binding.sourceTopics()).contains(topic)) throw invalid("Kafka 主题未在当前连接器的受管读写白名单中");
        String alias = "dh_native_kafka_" + DigestUtil.sha256Hex(Objects.requireNonNull(nodeIdentity)).substring(0, 24);
        String format = values.getStr("messageFormat", "JSON").toUpperCase(Locale.ROOT);
        if (!Set.of("JSON", "STRING").contains(format)) throw invalid("Kafka 消息格式仅支持 JSON 或 STRING");
        var fields = fields(values, output); var options = new LinkedHashMap<String, String>();
        options.put("connector", "kafka"); options.put("topic", topic); options.put("properties.bootstrap.servers", servers);
        var parameters = parameters(values.get("configParameters"));
        if (parameters.containsKey("auto.offset.reset") && parameters.containsKey("scan.startup.mode"))
            throw invalid("Kafka 起始位点不能同时配置 auto.offset.reset 与 scan.startup.mode");
        boolean bounded = !streaming;
        if (output) {
            if (values.containsKey("batchSize")) options.put("properties.batch.size", number(values.get("batchSize"), 1, 100_000_000, "单次写入字节"));
            else options.put("properties.batch.size", "10000");
            options.put("sink.delivery-guarantee", "at-least-once");
        } else {
            if (values.containsKey("batchSize")) throw invalid("Kafka 输入不接受输出批次字节参数");
            options.put("properties.group.id", "dh_" + DigestUtil.sha256Hex(nodeIdentity).substring(0, 24));
            options.put("scan.startup.mode", "earliest-offset");
            if (bounded) options.put("scan.bounded.mode", "latest-offset");
        }
        for (var parameter : parameters.entrySet()) apply(options, parameter.getKey(), parameter.getValue(), output);
        if (!output) {
            String start = options.get("scan.startup.mode");
            requireModeArgument(options, "scan.startup", start);
            String end = options.get("scan.bounded.mode");
            if (end != null) requireModeArgument(options, "scan.bounded", end);
            bounded = end != null;
            if (!streaming && !bounded) throw invalid("离线 Kafka 输入必须具有明确结束位点");
        } else if ("exactly-once".equals(options.get("sink.delivery-guarantee"))) {
            options.put("sink.transactional-id-prefix", "dh_" + DigestUtil.sha256Hex(nodeIdentity).substring(0, 24) + "_");
        }
        if (output && !"none".equals(options.get("sink.delivery-guarantee")) && "0".equals(options.get("properties.acks")))
            throw invalid("Kafka 至少一次或精确一次投递不能使用 acks=0");
        if (output && "exactly-once".equals(options.get("sink.delivery-guarantee"))
                && "false".equals(options.get("properties.enable.idempotence"))) throw invalid("Kafka 精确一次投递不能关闭生产者幂等");
        String ddl;
        var columns = new LinkedHashMap<String, String>(); fields.forEach(field -> columns.put(field.name(), field.type()));
        if (format.equals("STRING") && (fields.size() != 1 || !fields.getFirst().type().equals("STRING") || !output && !fields.getFirst().path().equals("$.value"))) throw invalid("STRING消息必须声明唯一String字段，输入路径须为value");
        if (engine == EtlNativeSqlCompiler.Engine.SPARK) {
            if (output && "exactly-once".equals(options.get("sink.delivery-guarantee"))) throw invalid("Spark Kafka有界输出只承诺确认写入的至少一次语义");
            var declarations = fields.stream().map(field -> Map.of("name", field.name(), "path", field.path(), "type", field.type())).toList();
            var plan = new JSONObject().set("bootstrapServers", servers).set("topic", topic).set("options", options)
                    .set("messageFormat", format).set("columns", declarations);
            return EtlNativeBoundedMessageSql.table(engine, nodeIdentity, "kafka", output, columns, plan, mappings);
        }
        if (output) {
            if (format.equals("STRING") && (fields.size() != 1 || !fields.getFirst().type().equals("STRING")))
                throw invalid("STRING 消息输出必须且只能声明一个 String 字段");
            options.put("format", format.equals("JSON") ? "json" : "raw");
            if (format.equals("JSON")) options.put("json.timestamp-format.standard", "SQL");
            ddl = "CREATE TEMPORARY TABLE " + quote(alias) + " (" + String.join(", ", fields.stream()
                    .map(field -> quote(field.name()) + " " + field.type()).toList()) + ") WITH (" + options(options) + ");\n";
        } else {
            options.put("format", "raw"); String raw = alias + "_raw";
            ddl = "CREATE TEMPORARY TABLE " + quote(raw) + " (`dh_payload` STRING) WITH (" + options(options) + ");\n";
            var projections = new ArrayList<String>();
            for (var field : fields) {
                String expression;
                if (format.equals("STRING")) {
                    if (fields.size() != 1 || !field.type().equals("STRING") || !field.path().equals("$.value"))
                        throw invalid("STRING 消息输入必须仅声明 value String 字段");
                    expression = "`dh_payload`";
                } else expression = "CAST(JSON_VALUE(`dh_payload`, " + literal(field.path()) + " RETURNING STRING ERROR ON ERROR) AS " + field.type() + ")";
                projections.add(expression + " AS " + quote(field.name()));
            }
            ddl += "CREATE TEMPORARY VIEW " + quote(alias) + " AS SELECT " + String.join(", ", projections) + " FROM " + quote(raw) + ";\n";
        }
        return new TablePlan(ddl, new EtlNativeSqlCompiler.Table(List.of(alias), columns, ProductTypeEnum.UNKNOWN,
                null, alias, !output && !bounded), mappings);
    }

    private record Field(String name, String type, String path) { }

    private static List<Field> fields(JSONObject values, boolean output) {
        var columns = values.getJSONArray("columns");
        if (columns == null || columns.isEmpty() || columns.size() > 1024) throw invalid("Kafka 需要1至1024个明确类型的属性字段");
        var result = new ArrayList<Field>(); var names = new HashSet<String>();
        for (Object item : columns) {
            var column = JSONUtil.parseObj(item); rejectUnknown(column.keySet(), FIELD_KEYS, "Kafka 属性字段");
            if (column.containsKey("index") && Integer.parseInt(number(column.get("index"), 0, 1023, "Kafka字段序号")) != result.size())
                throw invalid("Kafka 字段序号必须与声明顺序一致");
            String name = column.getStr("name", "");
            if (name.isBlank() || name.length() > 128 || name.codePoints().anyMatch(Character::isISOControl) || !names.add(name))
                throw invalid("Kafka 字段名称缺失、重复或超限");
            if (!column.getStr("format", "").isBlank()) throw invalid("Kafka 原生字段尚不接受自定义日期格式，请使用字段转换组件");
            String type = switch (column.getStr("type", "").toUpperCase(Locale.ROOT)) {
                case "STRING", "INTERNETADDRESS", "VARCHAR", "CHAR", "TEXT" -> "STRING";
                case "INTEGER", "INT", "LONG", "BIGINT" -> "BIGINT";
                case "NUMBER", "DOUBLE", "FLOAT" -> "DOUBLE";
                case "BOOLEAN" -> "BOOLEAN";
                case "DATE", "TIMESTAMP" -> "TIMESTAMP(3)";
                case "BINARY", "BYTES" -> throw invalid("Kafka JSON标量路径暂不支持二进制字段");
                case "BIGNUMBER", "DECIMAL", "NUMERIC" -> {
                    int precision = Integer.parseInt(number(column.get("length") == null || column.getStr("length").isBlank() ? 38 : column.get("length"), 1, 38, "Decimal总位数"));
                    int scale = Integer.parseInt(number(column.get("precision") == null || column.getStr("precision").isBlank() ? 18 : column.get("precision"), 0, precision, "Decimal小数位数"));
                    yield "DECIMAL(" + precision + "," + scale + ")";
                }
                default -> throw invalid("Kafka 字段类型未知或尚未适配：" + name);
            };
            if (!type.startsWith("DECIMAL(") && (!column.getStr("length", "").isBlank() || !column.getStr("precision", "").isBlank()))
                throw invalid("Kafka 非 Decimal 字段的长度或精度约束尚未适配：" + name);
            String key = column.getStr("key", column.getStr("jsonPath", name)); if (key.isBlank()) key = name;
            String path = key.startsWith("$") ? key : "$.\"" + key + "\"";
            if (!key.startsWith("$") && key.matches("[A-Za-z_][A-Za-z0-9_]*")) path = "$." + key;
            if (!path.matches("\\$(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[\\d+]|\\.\"[^\"\\\\]+\")+"))
                throw invalid("Kafka JSON取值仅支持确定的属性路径和数组下标：" + name);
            if (output && column.containsKey("jsonPath") && !column.getStr("jsonPath", "").isBlank() && !column.getStr("jsonPath").equals(name)) throw invalid("Kafka 输出不接受输入 JSON 取值路径");
            if (!output && column.containsKey("key") && column.containsKey("jsonPath") && !Objects.equals(column.get("key"), column.get("jsonPath")))
                throw invalid("Kafka 属性字段不能同时配置不同的 key 与 jsonPath");
            result.add(new Field(name, type, path));
        }
        return result;
    }

    private static Map<String, Object> parameters(Object raw) {
        JSONObject parsed;
        try {
            if (raw == null || raw instanceof String text && text.isBlank()) return Map.of();
            if (raw instanceof String text && !JSONUtil.isTypeJSONObject(text) || !(raw instanceof String) && !(raw instanceof Map<?, ?>))
                throw invalid("Kafka 配置参数必须为JSON对象");
            parsed = JSONUtil.parseObj(raw);
        } catch (RuntimeException malformed) { throw invalid("Kafka 配置参数必须为有效JSON对象"); }
        var result = new LinkedHashMap<String, Object>();
        parsed.forEach((key, value) -> {
            if (key.matches("(?i).*(password|passwd|secret|credential|token|username|jaas|bootstrap|security|ssl).*"))
                throw invalid("Kafka 认证和连接地址只能由平台受管绑定提供");
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean))
                throw invalid("Kafka 客户端配置只能是字符串、数值或布尔值");
            result.put(key, value);
        });
        return result;
    }

    private static void apply(Map<String, String> options, String key, Object raw, boolean output) {
        String value = String.valueOf(raw);
        if (!output && key.equals("group.id")) {
            if (!value.matches("[A-Za-z0-9._-]{1,200}")) throw invalid("Kafka消费组名称无效"); options.put("properties.group.id", value); return;
        }
        if (!output && key.equals("auto.offset.reset")) {
            options.put("scan.startup.mode", switch (value) { case "earliest" -> "earliest-offset"; case "latest" -> "latest-offset"; default -> throw invalid("Kafka auto.offset.reset仅接受earliest/latest"); }); return;
        }
        if (!output && Set.of("scan.startup.mode", "scan.bounded.mode").contains(key)) {
            if (!Set.of("earliest-offset", "latest-offset", "group-offsets", "timestamp", "specific-offsets").contains(value)
                    || key.equals("scan.bounded.mode") && value.equals("earliest-offset")) throw invalid("Kafka位点模式无效");
            options.put(key, value); return;
        }
        if (!output && Set.of("scan.startup.timestamp-millis", "scan.bounded.timestamp-millis").contains(key)) {
            options.put(key, number(raw, 0, Long.MAX_VALUE, "Kafka时间位点")); return;
        }
        if (!output && Set.of("scan.startup.specific-offsets", "scan.bounded.specific-offsets").contains(key)) {
            if (!value.matches("partition:[0-9]+,offset:[0-9]+(;partition:[0-9]+,offset:[0-9]+)*")) throw invalid("Kafka分区位点格式无效");
            var partitions = new HashSet<String>();
            for (String pair : value.split(";")) if (!partitions.add(pair.substring(0, pair.indexOf(',')))) throw invalid("Kafka分区位点重复");
            options.put(key, value); return;
        }
        if ((output ? PRODUCER_NUMBERS : CONSUMER_NUMBERS).contains(key)) { options.put("properties." + key, number(raw, 0, Integer.MAX_VALUE, key)); return; }
        if (output && key.equals("batch.size")) {
            String size = number(raw, 1, 100_000_000, key);
            if (!size.equals(options.get("properties.batch.size"))) throw invalid("Kafka batch.size与单次写入字节配置冲突"); return;
        }
        if (output && key.equals("acks")) { if (!Set.of("0", "1", "all", "-1").contains(value)) throw invalid("Kafka acks无效"); options.put("properties.acks", value); return; }
        if (output && key.equals("compression.type")) { if (!Set.of("none", "gzip", "snappy", "lz4", "zstd").contains(value)) throw invalid("Kafka压缩类型无效"); options.put("properties.compression.type", value); return; }
        if (output && key.equals("enable.idempotence")) { if (!Set.of("true", "false").contains(value)) throw invalid("Kafka幂等参数须为布尔值"); options.put("properties.enable.idempotence", value); return; }
        if (output && key.equals("sink.delivery-guarantee")) { if (!Set.of("at-least-once", "exactly-once", "none").contains(value)) throw invalid("Kafka投递语义无效"); options.put(key, value); return; }
        throw invalid("Kafka 参数尚未提供等价原生映射：" + key);
    }

    private static void requireModeArgument(Map<String, String> options, String prefix, String mode) {
        String needed = "timestamp".equals(mode) ? prefix + ".timestamp-millis" : "specific-offsets".equals(mode) ? prefix + ".specific-offsets" : null;
        for (String suffix : List.of(".timestamp-millis", ".specific-offsets")) {
            String key = prefix + suffix;
            if (key.equals(needed) && !options.containsKey(key)) throw invalid("Kafka位点模式缺少参数：" + key);
            if (!key.equals(needed) && options.containsKey(key)) throw invalid("Kafka位点参数与模式不一致：" + key);
        }
    }

    private static void rejectUnknown(Set<String> keys, Set<String> allowed, String owner) {
        for (String key : keys) if (!allowed.contains(key)) throw invalid(owner + "参数尚未提供等价原生映射：" + key);
    }
    private static String number(Object raw, long min, long max, String name) {
        try { long value = new BigDecimal(String.valueOf(raw)).longValueExact(); if (value < min || value > max) throw new ArithmeticException(); return String.valueOf(value); }
        catch (RuntimeException invalid) { throw invalid(name + "必须为范围内整数"); }
    }
    private static String quote(String value) { return "`" + value.replace("`", "``") + "`"; }
    private static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static String options(Map<String, String> values) { return String.join(", ", values.entrySet().stream().map(entry -> literal(entry.getKey()) + " = " + literal(entry.getValue())).toList()); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
