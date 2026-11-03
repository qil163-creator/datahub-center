package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeRedisCompiler;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import cn.iocoder.yudao.module.dataforge.service.database.ConnectionSettings;
import cn.iocoder.yudao.module.dataforge.service.databasetype.DatabaseTypeService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** Redis 没有 JDBC 元数据；本层仅绑定平台连接身份，SDK 与行语义留在组件中。 */
@Component
public class IntegrationRedisBindingSupport {
    @Resource private IntegrationRedisBindingsProperties properties;
    @Resource private DatabaseService databases;
    @Resource private DatabaseTypeService databaseTypes;
    @Resource private ExecutionAccessService access;

    public EtlNativeRedisCompiler.Binding require(IntegrationTaskDO task, Long databaseId) {
        var binding = databaseId == null || properties.getBindings() == null ? null : properties.getBindings().get(databaseId);
        if (task == null || task.getId() == null || databaseId == null || binding == null
                || !contains(binding.getAllowedTaskIds(), task.getId()) || !contains(binding.getWorkerGroups(), task.getWorkerGroup())
                || !contains(binding.getEngines(), task.getEngine()) || !Set.of("SPARK", "FLINK").contains(task.getEngine()) || !"OFFLINE".equals(task.getMode())
                || "SPARK".equals(task.getEngine()) && !binding.isSparkConnectorInstalled()
                || "FLINK".equals(task.getEngine()) && !binding.isFlinkConnectorInstalled())
            throw invalid("当前任务、引擎或工作组尚未获授权使用 Redis 原生绑定");
        access.requireDatabase(databaseId);
        var database = databases.getDatabase(databaseId);
        if (database == null || database.getDatabaseType() == null) throw invalid("Redis 平台连接不存在");
        var type = databaseTypes.getDatabaseType(database.getDatabaseType().longValue());
        var settings = ConnectionSettings.read(database, type);
        if (type == null || !"REDIS".equalsIgnoreCase(type.getTypeCode()) || !Objects.equals(binding.getHost(), database.getDatabaseIp())
                || !Integer.toString(binding.getPort()).equals(database.getDatabasePort())
                || !Integer.toString(binding.getDatabase()).equals(database.getDatabaseName()) || binding.isUseSsl() != settings.flag("tls", false))
            throw invalid("Redis 平台连接与受管物理地址或数据库不一致");
        return new EtlNativeRedisCompiler.Binding(binding.getHost(), binding.getPort(), binding.getDatabase(), binding.isUseSsl(),
                binding.getCredentialRef(), binding.getSourcePatterns(), binding.getSinkPrefixes(), binding.isSparkConnectorInstalled(), binding.isFlinkConnectorInstalled());
    }
    private static <T> boolean contains(Set<T> values, T value) { return values != null && value != null && values.contains(value); }
    private static IllegalArgumentException invalid(String value) { return new IllegalArgumentException(value); }
}
