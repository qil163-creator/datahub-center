package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeRocketCompiler;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.database.ConnectionSettings;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import cn.iocoder.yudao.module.dataforge.service.databasetype.DatabaseTypeService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/** 本层只验证任务、平台连接和资源归属，协议及检查点行为由 ETL 组件实现。 */
@Component
public class IntegrationRocketBindingSupport {
    @Resource private IntegrationRocketBindingsProperties properties;
    @Resource private IntegrationRuntimeProperties runtimeProperties;
    @Resource private DatabaseService databases;
    @Resource private DatabaseTypeService databaseTypes;
    @Resource private ExecutionAccessService access;
    public EtlNativeRocketCompiler.Binding require(IntegrationTaskDO task, Long databaseId, String code, Map<String, Object> config) {
        if (task == null || task.getId() == null || databaseId == null || config == null || code == null || !Set.of("read-rocketmq", "write-rocketmq").contains(code)
                || !("FLINK".equals(task.getEngine()) && "REALTIME".equals(task.getMode()) || Set.of("SPARK", "FLINK").contains(task.getEngine()) && "OFFLINE".equals(task.getMode()))) throw invalid("RocketMQ 需要明确的已授权 Flink 实时任务");
        var binding = properties.getBindings() == null ? null : properties.getBindings().get(databaseId);
        if (binding == null || !installed(binding, task) || !contains(binding.getAllowedTaskIds(), task.getId())
                || !contains(binding.getWorkerGroups(), task.getWorkerGroup()) || !contains(binding.getEngines(), task.getEngine())) throw invalid("当前任务、工作组或引擎未获 RocketMQ 原生资源授权");
        var secrets = runtimeProperties.getManagedSecretBindings(); String reference = binding.getCredentialRef();
        var secret = reference == null || secrets == null ? null : secrets.get(reference);
        if (reference == null || !reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || secret == null || !contains(secret.getAllowedTaskIds(), task.getId())
                || !contains(secret.getWorkerGroups(), task.getWorkerGroup()) || !contains(secret.getEngines(), task.getEngine())) throw invalid("RocketMQ 秘密引用未获当前执行范围授权");
        String group = Objects.toString(config.get("groupId"), ""), topic = Objects.toString(config.get("topic"), ""); boolean output = code.startsWith("write-");
        var owners = binding.getConsumerGroupTaskIds();
        if (!contains(output ? binding.getSinkTopics() : binding.getSourceTopics(), topic)
                || (output ? !contains(binding.getProducerGroups(), group) : owners == null || !Objects.equals(owners.get(group), task.getId())))
            throw invalid("RocketMQ 主题、消息组或消费者组的唯一任务归属不匹配");
        access.requireDatabase(databaseId);
        var database = databases.getDatabase(databaseId);
        if (database == null || database.getDatabaseType() == null) throw invalid("RocketMQ 平台连接不存在");
        var type = databaseTypes.getDatabaseType(database.getDatabaseType().longValue());
        if (!"ROCKETMQ".equals(ConnectionSettings.code(type))) throw invalid("RocketMQ 组件不能借用其他产品连接");
        var settings = ConnectionSettings.read(database, type);
        String addresses = canonical(binding.getNameServers());
        if (!addresses.equals(canonical(settings.get("servers", ""))) || binding.isUseTls() != settings.flag("tls", false)
                || !"ACL".equals(settings.get("authenticationMode", "NONE"))) throw invalid("RocketMQ 平台连接的地址、TLS 或 ACL 模式与受管绑定不一致");
        if (!output) for (var other : properties.getBindings().values()) {
            if (other == null || !other.isFlinkConnectorInstalled() || other.getConsumerGroupTaskIds() == null || other.getNameServers() == null) continue;
            Long owner = other.getConsumerGroupTaskIds().get(group);
            if (owner != null && !Objects.equals(owner, task.getId()) && addresses.equals(canonical(other.getNameServers())))
                throw invalid("同一 RocketMQ 资源的消费者组不能同时分配给不同任务");
        }
        Set<String> groups = owners == null ? Set.of() : owners.entrySet().stream().filter(entry -> Objects.equals(task.getId(), entry.getValue())).map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
        return new EtlNativeRocketCompiler.Binding(addresses, binding.getSourceTopics(), binding.getSinkTopics(), groups, binding.getProducerGroups(), reference, binding.isUseTls(), binding.isFlinkConnectorInstalled(), binding.isSparkConnectorInstalled(), binding.isFlinkBoundedConnectorInstalled());
    }
    private static String canonical(String value) {
        if (value == null || value.isBlank()) throw invalid("RocketMQ 平台 NameServer 未配置");
        var addresses = Arrays.stream(value.split("[,;]", -1)).map(address -> address.trim().toLowerCase(Locale.ROOT)).sorted().toList();
        if (addresses.isEmpty() || addresses.size() > 8 || addresses.stream().anyMatch(String::isBlank) || new HashSet<>(addresses).size() != addresses.size()) throw invalid("RocketMQ NameServer 范围不完整或重复");
        return String.join(";", addresses);
    }
    private static boolean installed(IntegrationRocketBindingsProperties.ManagedBinding binding, IntegrationTaskDO task) {
        return "REALTIME".equals(task.getMode()) ? binding.isFlinkConnectorInstalled() : "SPARK".equals(task.getEngine()) ? binding.isSparkConnectorInstalled() : binding.isFlinkBoundedConnectorInstalled();
    }
    private static <T> boolean contains(Set<T> values, T value) { return values != null && value != null && values.contains(value); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
