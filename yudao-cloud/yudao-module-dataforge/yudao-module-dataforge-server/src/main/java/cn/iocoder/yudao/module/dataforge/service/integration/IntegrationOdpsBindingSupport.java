package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeOdpsCompiler;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.database.ConnectionSettings;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import cn.iocoder.yudao.module.dataforge.service.databasetype.DatabaseTypeService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import java.util.*;

/** 只适配平台身份和任务授权；官方 SDK 的字段及分区核验由原生执行组件完成。 */
@Component
public class IntegrationOdpsBindingSupport {
    @Resource private IntegrationOdpsBindingsProperties properties;
    @Resource private DatabaseService databases;
    @Resource private DatabaseTypeService databaseTypes;
    @Resource private ExecutionAccessService access;
    public EtlNativeOdpsCompiler.Binding require(IntegrationTaskDO task, Long databaseId) {
        var binding = databaseId == null || properties.getBindings() == null ? null : properties.getBindings().get(databaseId);
        if (task == null || task.getId() == null || databaseId == null || binding == null
                || !contains(binding.getAllowedTaskIds(), task.getId()) || !contains(binding.getWorkerGroups(), task.getWorkerGroup())
                || !contains(binding.getEngines(), task.getEngine()) || !Set.of("SPARK", "FLINK").contains(task.getEngine()) || !"OFFLINE".equals(task.getMode())
                || "SPARK".equals(task.getEngine()) && !binding.isSparkConnectorInstalled()
                || "FLINK".equals(task.getEngine()) && !binding.isFlinkConnectorInstalled())
            throw invalid("当前任务、引擎或工作组尚未授权 ODPS Tunnel 资源");
        access.requireDatabase(databaseId); var database = databases.getDatabase(databaseId);
        if (database == null || database.getDatabaseType() == null) throw invalid("ODPS 平台连接不存在");
        var type = databaseTypes.getDatabaseType(database.getDatabaseType().longValue());
        if (!"ODPS".equals(ConnectionSettings.code(type))) throw invalid("ODPS 组件不能借用其他产品的连接");
        var settings = ConnectionSettings.read(database, type);
        if (!Objects.equals(binding.getServiceEndpoint(), settings.get("endpoint", "")) || !Objects.equals(binding.getProject(), database.getDatabaseName()))
            throw invalid("ODPS 平台服务地址或项目与已批准的物理绑定不一致");
        return new EtlNativeOdpsCompiler.Binding(binding.getServiceEndpoint(), binding.getTunnelEndpoint(), binding.getProject(), binding.getCredentialRef(),
                binding.getResources(), binding.isSparkConnectorInstalled(), binding.isAllowPlainHttp(), binding.isFlinkConnectorInstalled());
    }
    private static <T> boolean contains(Set<T> values, T value) { return values != null && value != null && values.contains(value); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
