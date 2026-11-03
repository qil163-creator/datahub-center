package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.math.BigDecimal;
import java.util.*;

/** RabbitMQ 官方数据流连接器的受管表适配；规划过程只处理资源与秘密引用。 */
public final class EtlNativeRabbitCompiler {
    private static final Set<String> KEYS = Set.of("databaseId", "topic", "config", "columns", "batchSize", "fieldMappings");
    private EtlNativeRabbitCompiler() { }

    /** 队列读写范围和连接器安装声明来自管理员，不能由节点参数覆盖。 */
    public record Binding(String host, int port, String virtualHost, Set<String> sourceQueues, Set<String> sinkQueues,
                          String credentialRef, boolean useSsl, boolean flinkConnectorInstalled, boolean sparkConnectorInstalled, boolean flinkBoundedConnectorInstalled) {
        public Binding(String host, int port, String virtualHost, Set<String> sourceQueues, Set<String> sinkQueues, String credentialRef, boolean useSsl, boolean flinkConnectorInstalled) {
            this(host, port, virtualHost, sourceQueues, sinkQueues, credentialRef, useSsl, flinkConnectorInstalled, false, false);
        }
        public Binding {
            sourceQueues = sourceQueues == null ? Set.of() : Set.copyOf(sourceQueues);
            sinkQueues = sinkQueues == null ? Set.of() : Set.copyOf(sinkQueues);
        }
    }

    public static Set<String> supportedCodes(EtlNativeSqlCompiler.Engine engine, boolean streaming) {
        return engine == EtlNativeSqlCompiler.Engine.FLINK || !streaming ? Set.of("read-rabbitmq", "write-rabbitmq") : Set.of();
    }

    public static EtlNativeConnectorCompiler.TablePlan compile(EtlNativeSqlCompiler.Engine engine, boolean streaming,
            String identity, String code, Map<String, Object> input, Binding binding, Set<String> authorizedSecretRefs) {
        if (!supportedCodes(engine, streaming).contains(code)) throw invalid("RabbitMQ 当前仅支持 Flink 持续消息读取和写入");
        if (input == null || JSONUtil.toJsonStr(input).length() > 500_000) throw invalid("RabbitMQ 配置缺失或超过大小限制");
        var config = JSONUtil.parseObj(input);
        if (!KEYS.containsAll(config.keySet())) throw invalid("RabbitMQ 节点包含未适配参数");
        if (binding == null || (streaming ? !binding.flinkConnectorInstalled() : engine == EtlNativeSqlCompiler.Engine.SPARK ? !binding.sparkConnectorInstalled() : !binding.flinkBoundedConnectorInstalled())) throw invalid("RabbitMQ 尚未绑定已安装受管连接器的执行环境");
        if (binding.host() == null || !binding.host().matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}") || binding.port() < 1 || binding.port() > 65535
                || binding.virtualHost() == null || binding.virtualHost().isEmpty() || binding.virtualHost().length() > 255
                || binding.virtualHost().codePoints().anyMatch(Character::isISOControl)) throw invalid("RabbitMQ 受管地址或虚拟主机无效");
        String reference = binding.credentialRef();
        if (reference == null || !reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || authorizedSecretRefs == null || !authorizedSecretRefs.contains(reference))
            throw invalid("RabbitMQ 凭据引用未获当前任务授权");
        String queue = config.getStr("topic", ""); boolean output = code.startsWith("write-");
        if (!queue.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}") || queue.startsWith("amq.")
                || !(output ? binding.sinkQueues() : binding.sourceQueues()).contains(queue)) throw invalid("RabbitMQ 队列不在当前受管读写范围");
        var parameters = config.get("config");
        if (parameters instanceof String text && !JSONUtil.isTypeJSONObject(text) || parameters != null && !(parameters instanceof String || parameters instanceof Map<?, ?>))
            throw invalid("RabbitMQ 配置参数必须为 JSON 对象");
        var options = parameters == null ? JSONUtil.createObj() : JSONUtil.parseObj(parameters);
        if (!(output ? Set.of("confirmTimeoutMillis") : Set.of("prefetchCount")).containsAll(options.keySet()))
            throw invalid("RabbitMQ 配置参数尚未提供等价执行，认证与地址只能由受管资源配置");
        if (config.get("batchSize") != null && (!output || number(config.get("batchSize"), 1, 1) != 1))
            throw invalid("RabbitMQ 当前每条消息等待发布确认，单次写入条数只能为 1");
        var fields = EtlNativeMessageSchema.columns(config.getJSONArray("columns"), output);
        if (!output && config.containsKey("fieldMappings")) throw invalid("RabbitMQ 输入不接受输出字段映射");
        var mappings = output ? EtlNativeOutputMappings.require(config) : List.<Map<String, Object>>of();
        var properties = new LinkedHashMap<String, String>();
        properties.put("connector", "datahub-rabbitmq"); properties.put("host", binding.host()); properties.put("port", Integer.toString(binding.port()));
        properties.put("virtual-host", binding.virtualHost()); properties.put("queue", queue); properties.put("credential-ref", reference);
        properties.put("use-ssl", Boolean.toString(binding.useSsl())); properties.put("format", "json");
        properties.put("json.timestamp-format.standard", "ISO-8601");
        if (output) properties.put("confirm.timeout.ms", Integer.toString(number(options.get("confirmTimeoutMillis") == null ? 10_000 : options.get("confirmTimeoutMillis"), 100, 60_000)));
        else {
            properties.put("prefetch-count", Integer.toString(number(options.get("prefetchCount") == null ? 100 : options.get("prefetchCount"), 1, 10_000)));
            properties.put("json.fail-on-missing-field", "true"); properties.put("json.ignore-parse-errors", "false");
        }
        if (!streaming && !output && options.containsKey("prefetchCount")) throw invalid("RabbitMQ有界浏览必须按启动条数保留未确认消息，不接受持续消费的prefetchCount");
        if (!streaming) {
            var plan = new JSONObject().set("host", binding.host()).set("port", binding.port()).set("virtualHost", binding.virtualHost())
                    .set("queue", queue).set("credentialRef", reference).set("useSsl", binding.useSsl()).set("properties", properties);
            return EtlNativeBoundedMessageSql.table(engine, identity, "rabbitmq", output, fields, plan, mappings);
        }
        String alias = "dh_native_rabbit_" + DigestUtil.sha256Hex(Objects.requireNonNull(identity)).substring(0, 24);
        String ddl = "CREATE TEMPORARY TABLE " + identifier(alias) + " (" + String.join(", ", fields.entrySet().stream().map(entry -> identifier(entry.getKey()) + " " + entry.getValue()).toList())
                + ") WITH (" + String.join(", ", properties.entrySet().stream().map(entry -> literal(entry.getKey()) + "=" + literal(entry.getValue())).toList()) + ");\n";
        return new EtlNativeConnectorCompiler.TablePlan(ddl, new EtlNativeSqlCompiler.Table(List.of(alias), fields, ProductTypeEnum.UNKNOWN, null, alias, !output), mappings);
    }
    private static String identifier(String name) {
        if (name == null || !name.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,127}")) throw invalid("RabbitMQ 字段标识符无效");
        return "`" + name + "`";
    }
    private static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static int number(Object value, int min, int max) {
        try { int result = new BigDecimal(value.toString()).intValueExact(); if (result < min || result > max) throw new ArithmeticException(); return result; }
        catch (RuntimeException failure) { throw invalid("RabbitMQ 数值参数超出已支持范围"); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
