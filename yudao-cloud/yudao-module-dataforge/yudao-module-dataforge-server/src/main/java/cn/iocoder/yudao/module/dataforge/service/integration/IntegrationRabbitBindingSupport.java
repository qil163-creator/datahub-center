package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeRabbitCompiler;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.database.ConnectionSettings;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import cn.iocoder.yudao.module.dataforge.service.databasetype.DatabaseTypeService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 业务侧只执行任务授权和平台连接身份绑定，协议、格式及凭据读取交给组件和工作节点。 */
@Component
public class IntegrationRabbitBindingSupport {
    @Resource private IntegrationRabbitBindingsProperties properties;
    @Resource private IntegrationRuntimeProperties runtimeProperties;
    @Resource private DatabaseService databases;
    @Resource private DatabaseTypeService databaseTypes;
    @Resource private ExecutionAccessService access;

    public EtlNativeRabbitCompiler.Binding require(IntegrationTaskDO task, Long databaseId, String code, Map<String, Object> config) {
        if (task == null || task.getId() == null || databaseId == null || code == null || !Set.of("read-rabbitmq", "write-rabbitmq").contains(code)
                || !("FLINK".equals(task.getEngine()) && "REALTIME".equals(task.getMode()) || Set.of("SPARK", "FLINK").contains(task.getEngine()) && "OFFLINE".equals(task.getMode())) || config == null)
            throw invalid("RabbitMQ 需要已授权数据连接与明确的 Flink 实时任务");
        var binding = properties.getBindings() == null ? null : properties.getBindings().get(databaseId);
        if (binding == null || !installed(binding, task) || !contains(binding.getAllowedTaskIds(), task.getId())
                || !contains(binding.getWorkerGroups(), task.getWorkerGroup()) || !contains(binding.getEngines(), task.getEngine()))
            throw invalid("当前任务、引擎或工作组尚未授权 RabbitMQ 原生资源");
        String reference = binding.getCredentialRef();
        var secrets = runtimeProperties.getManagedSecretBindings();
        var secret = reference == null || secrets == null ? null : secrets.get(reference);
        if (reference == null || !reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || secret == null
                || !contains(secret.getAllowedTaskIds(), task.getId()) || !contains(secret.getWorkerGroups(), task.getWorkerGroup())
                || !contains(secret.getEngines(), task.getEngine())) throw invalid("RabbitMQ 秘密引用未获当前任务、工作组和引擎授权");
        String queue = Objects.toString(config.get("topic"), "");
        if (!contains(code.startsWith("write-") ? binding.getSinkQueues() : binding.getSourceQueues(), queue))
            throw invalid("RabbitMQ 队列不在当前任务的受管读写范围");
        access.requireDatabase(databaseId);
        var database = databases.getDatabase(databaseId);
        if (database == null || database.getDatabaseType() == null) throw invalid("RabbitMQ 平台连接不存在");
        var type = databaseTypes.getDatabaseType(database.getDatabaseType().longValue());
        if (!"RABBITMQ".equals(ConnectionSettings.code(type))) throw invalid("RabbitMQ 组件不能借用其他产品的数据连接");
        var settings = ConnectionSettings.read(database, type);
        if (!Objects.equals(binding.getHost(), database.getDatabaseIp()) || !Integer.toString(binding.getPort()).equals(database.getDatabasePort())
                || !Objects.equals(binding.getVirtualHost(), settings.get("virtualHost", "/")) || binding.isUseSsl() != settings.flag("tls", false))
            throw invalid("RabbitMQ 平台连接与已批准的物理地址、虚拟主机或 TLS 设置不一致");
        return new EtlNativeRabbitCompiler.Binding(binding.getHost(), binding.getPort(), binding.getVirtualHost(), binding.getSourceQueues(),
                binding.getSinkQueues(), reference, binding.isUseSsl(), binding.isFlinkConnectorInstalled(), binding.isSparkConnectorInstalled(), binding.isFlinkBoundedConnectorInstalled());
    }
    private static boolean installed(IntegrationRabbitBindingsProperties.ManagedBinding binding, IntegrationTaskDO task) {
        return "REALTIME".equals(task.getMode()) ? binding.isFlinkConnectorInstalled() : "SPARK".equals(task.getEngine()) ? binding.isSparkConnectorInstalled() : binding.isFlinkBoundedConnectorInstalled();
    }
    private static <T> boolean contains(Set<T> values, T value) { return values != null && value != null && values.contains(value); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
