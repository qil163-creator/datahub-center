package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.math.BigDecimal;
import java.util.*;

/** 官方 RocketMQ 拉取/同步发送到 Flink 持续表的受管编译，认证材料不进入 SQL。 */
public final class EtlNativeRocketCompiler {
    private static final Set<String> KEYS = Set.of("databaseId", "topic", "groupId", "offsetReset", "config", "columns", "batchSize", "fieldMappings");
    private EtlNativeRocketCompiler() { }
    public record Binding(String nameServers, Set<String> sourceTopics, Set<String> sinkTopics, Set<String> consumerGroups,
                          Set<String> producerGroups, String credentialRef, boolean useTls, boolean flinkConnectorInstalled, boolean sparkConnectorInstalled, boolean flinkBoundedConnectorInstalled) {
        public Binding(String nameServers, Set<String> sourceTopics, Set<String> sinkTopics, Set<String> consumerGroups, Set<String> producerGroups, String credentialRef, boolean useTls, boolean flinkConnectorInstalled) {
            this(nameServers, sourceTopics, sinkTopics, consumerGroups, producerGroups, credentialRef, useTls, flinkConnectorInstalled, false, false);
        }
        public Binding {
            sourceTopics = sourceTopics == null ? Set.of() : Set.copyOf(sourceTopics); sinkTopics = sinkTopics == null ? Set.of() : Set.copyOf(sinkTopics);
            consumerGroups = consumerGroups == null ? Set.of() : Set.copyOf(consumerGroups); producerGroups = producerGroups == null ? Set.of() : Set.copyOf(producerGroups);
        }
    }
    public static Set<String> supportedCodes(EtlNativeSqlCompiler.Engine engine, boolean streaming) {
        return engine == EtlNativeSqlCompiler.Engine.FLINK || !streaming ? Set.of("read-rocketmq", "write-rocketmq") : Set.of();
    }
    public static EtlNativeConnectorCompiler.TablePlan compile(EtlNativeSqlCompiler.Engine engine, boolean streaming,
            String identity, String code, Map<String, Object> input, Binding binding, Set<String> authorizedSecretRefs) {
        if (!supportedCodes(engine, streaming).contains(code)) throw invalid("RocketMQ 当前仅支持 Flink 持续读取和确认写入");
        if (input == null || JSONUtil.toJsonStr(input).length() > 500_000) throw invalid("RocketMQ 配置缺失或超过预算");
        var config = JSONUtil.parseObj(input);
        if (!KEYS.containsAll(config.keySet())) throw invalid("RocketMQ 节点包含未适配参数");
        if (binding == null || (streaming ? !binding.flinkConnectorInstalled() : engine == EtlNativeSqlCompiler.Engine.SPARK ? !binding.sparkConnectorInstalled() : !binding.flinkBoundedConnectorInstalled())) throw invalid("RocketMQ 原生连接器未在授权环境中安装");
        String servers = binding.nameServers();
        if (servers == null || servers.length() > 2048 || !servers.matches("[A-Za-z0-9.-]+:[0-9]{1,5}(;[A-Za-z0-9.-]+:[0-9]{1,5})*")) throw invalid("RocketMQ 受管 NameServer 地址无效");
        String[] addresses = servers.split(";");
        if (addresses.length > 8 || new HashSet<>(Arrays.asList(addresses)).size() != addresses.length) throw invalid("RocketMQ NameServer 地址重复或超过预算");
        for (String address : addresses) integer(address.substring(address.lastIndexOf(':') + 1), 1, 65535);
        String reference = binding.credentialRef();
        if (reference == null || !reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || authorizedSecretRefs == null || !authorizedSecretRefs.contains(reference))
            throw invalid("RocketMQ 凭据引用未获当前任务授权");
        boolean output = code.startsWith("write-"); String topic = config.getStr("topic", ""), group = config.getStr("groupId", "");
        identifier(topic); identifier(group);
        if (!(output ? binding.sinkTopics() : binding.sourceTopics()).contains(topic) || !(output ? binding.producerGroups() : binding.consumerGroups()).contains(group))
            throw invalid("RocketMQ 主题或消息组不在当前任务的受管范围");
        var raw = config.get("config");
        if (raw instanceof String text && !JSONUtil.isTypeJSONObject(text) || raw != null && !(raw instanceof String || raw instanceof Map<?, ?>)) throw invalid("RocketMQ 配置参数必须为 JSON 对象");
        var options = raw == null ? JSONUtil.createObj() : JSONUtil.parseObj(raw);
        if (!(output ? Set.of("sendTimeoutMillis") : Set.of("pullTimeoutMillis")).containsAll(options.keySet()))
            throw invalid("RocketMQ 客户端参数尚未提供等价执行，地址和认证只能由管理员提供");
        if (config.get("batchSize") != null && (!output || integer(config.get("batchSize"), 1, 1) != 1)) throw invalid("RocketMQ 当前逐条同步确认，写入批次只能为 1");
        var fields = EtlNativeMessageSchema.columns(config.getJSONArray("columns"), output);
        if (!output && config.containsKey("fieldMappings")) throw invalid("RocketMQ 输入不接受输出字段映射");
        var mappings = output ? EtlNativeOutputMappings.require(config) : List.<Map<String, Object>>of();
        var properties = new LinkedHashMap<String, String>();
        properties.put("connector", "datahub-rocketmq"); properties.put("nameservers", servers); properties.put("topic", topic); properties.put("group.id", group);
        properties.put("credential-ref", reference); properties.put("use-tls", Boolean.toString(binding.useTls()));
        properties.put("format", "json"); properties.put("json.timestamp-format.standard", "ISO-8601");
        if (output) {
            if (config.containsKey("offsetReset")) throw invalid("RocketMQ 输出不接受输入位点配置");
            properties.put("send.timeout.ms", Integer.toString(integer(options.get("sendTimeoutMillis") == null ? 10_000 : options.get("sendTimeoutMillis"), 100, 60_000)));
        } else {
            String offset = config.getStr("offsetReset", "earliest");
            if (!Set.of("earliest", "latest").contains(offset)) throw invalid("RocketMQ 无已保存位点时的起始模式无效");
            properties.put("offset.reset", offset); properties.put("pull.timeout.ms", Integer.toString(integer(options.get("pullTimeoutMillis") == null ? 1000 : options.get("pullTimeoutMillis"), 100, 10_000)));
            properties.put("json.fail-on-missing-field", "true"); properties.put("json.ignore-parse-errors", "false");
        }
        if (!streaming) {
            var plan = new JSONObject().set("nameservers", servers).set("topic", topic).set("group", group)
                    .set("credentialRef", reference).set("useTls", binding.useTls()).set("properties", properties);
            return EtlNativeBoundedMessageSql.table(engine, identity, "rocketmq", output, fields, plan, mappings);
        }
        String alias = "dh_native_rocket_" + DigestUtil.sha256Hex(Objects.requireNonNull(identity)).substring(0, 24);
        String ddl = "CREATE TEMPORARY TABLE `" + alias + "` (" + String.join(", ", fields.entrySet().stream().map(entry -> "`" + entry.getKey() + "` " + entry.getValue()).toList())
                + ") WITH (" + String.join(", ", properties.entrySet().stream().map(entry -> literal(entry.getKey()) + "=" + literal(entry.getValue())).toList()) + ");\n";
        return new EtlNativeConnectorCompiler.TablePlan(ddl, new EtlNativeSqlCompiler.Table(List.of(alias), fields, ProductTypeEnum.UNKNOWN, null, alias, !output), mappings);
    }
    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,126}") || value.equals("TBW102")) throw invalid("RocketMQ 主题或消息组名称无效");
    }
    private static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static int integer(Object value, int min, int max) {
        try { int number = new BigDecimal(value.toString()).intValueExact(); if (number < min || number > max) throw new ArithmeticException(); return number; }
        catch (RuntimeException invalid) { throw invalid("RocketMQ 数值超出已支持范围"); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
