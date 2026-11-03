package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeTdengineCompiler;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.*;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_NOT_EXISTS;

/** 薄适配：领域授权和平台连接解析在本层，原生元数据读取复用 DB Switch。 */
@Component
public class IntegrationTdengineBindingSupport {
    @Resource private IntegrationTdengineBindingsProperties properties;
    @Resource private IntegrationRuntimeProperties runtimeProperties;
    @Resource private AccessDatabaseResolver connections;
    @Resource private ExecutionAccessService access;
    @Resource private DbSwitchRuntime dbSwitch;

    public EtlNativeTdengineCompiler.Binding require(IntegrationTaskDO task, Long databaseId, Map<String, Object> config) {
        if (task == null || task.getId() == null || databaseId == null || !("FLINK".equals(task.getEngine()) && "REALTIME".equals(task.getMode()) || Set.of("SPARK", "FLINK").contains(task.getEngine()) && "OFFLINE".equals(task.getMode())))
            throw invalid("TDengine TMQ 需要明确的 Flink 实时任务和平台数据源");
        if (config == null) throw invalid("TDengine 组件配置缺失");
        var entries = properties.getBindings();
        if (entries == null) throw invalid("TDengine 受管绑定未配置");
        String bindingId = Objects.toString(config.get("resourceBindingId"), "");
        IntegrationTdengineBindingsProperties.ManagedBinding binding;
        if (!bindingId.isBlank()) {
            if (!bindingId.matches("[A-Za-z0-9_-]{1,100}")) throw invalid("TDengine 绑定编号无效");
            binding = entries.get(bindingId);
        } else {
            var candidates = entries.values().stream().filter(value -> permitted(value, task, databaseId)).toList();
            if (candidates.size() != 1) throw invalid("当前任务尚未唯一授权 TDengine 受管绑定");
            binding = candidates.getFirst();
        }
        if (!permitted(binding, task, databaseId) || !("REALTIME".equals(task.getMode()) ? binding.isFlinkConnectorInstalled() : "SPARK".equals(task.getEngine()) ? binding.isSparkConnectorInstalled() : binding.isFlinkBoundedConnectorInstalled())) throw invalid("当前任务、工作组或引擎无权使用已安装的 TDengine 受管绑定");
        var secrets = runtimeProperties.getManagedSecretBindings();
        var secret = secrets == null ? null : secrets.get(binding.getCredentialRef());
        if (secret == null || !contains(secret.getAllowedTaskIds(), task.getId()) || !contains(secret.getWorkerGroups(), task.getWorkerGroup()) || !contains(secret.getEngines(), task.getEngine()))
            throw invalid("TDengine 凭据引用未获当前任务、工作组和引擎授权");
        if ("REALTIME".equals(task.getMode()) && (!contains(binding.getSourceTopics(), Objects.toString(config.get("topic"), "")) || !contains(binding.getConsumerGroups(), Objects.toString(config.get("groupId"), ""))))
            throw invalid("TDengine 主题或消费组未获当前任务授权");
        if ("OFFLINE".equals(task.getMode()) && !Objects.equals(binding.getSourceTableName(), config.get("tableName"))) throw invalid("TDengine离线表名与受管物理表不一致");
        access.requireDatabase(databaseId);
        var connection = connections.resolve(databaseId, DATABASE_NOT_EXISTS);
        if (connection.getProductType() != ProductTypeEnum.TDENGINE) throw invalid("TDengine 组件不能借用其他数据库产品的连接");
        String expected = binding.getExpectedJdbcUrlSha256();
        if (expected == null || !expected.matches("[a-f0-9]{64}") || !expected.equals(DigestUtil.sha256Hex(connection.getJdbcUrl())))
            throw invalid("TDengine 平台连接配置已变化或尚未固定物理绑定");
        if (binding.getSourceTableName() == null || binding.getSourceTableName().isBlank() || binding.getColumns() == null || binding.getColumns().isEmpty())
            throw invalid("TDengine 受管主题缺少物理表和有序字段合同");
        var columns = new LinkedHashMap<String, String>();
        for (var column : binding.getColumns()) if (column == null || column.getName() == null || column.getType() == null || columns.putIfAbsent(column.getName(), column.getType()) != null)
            throw invalid("TDengine 受管字段合同缺失或重复");
        try {
            var metadata = dbSwitch.jdbcMetadata(ProductTypeEnum.TDENGINE).queryTableMeta(connection.getJdbcUrl(), connection.getUsername(), connection.getPassword(), connection.getSchema(), binding.getSourceTableName());
            EtlNativeTdengineCompiler.validateMetadata(columns, metadata.getColumns());
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw invalid("TDengine 原生元数据校验失败，请核查受管连接和物理表"); }
        return new EtlNativeTdengineCompiler.Binding(binding.getBootstrapServers(), binding.getSourceTopics(), binding.getConsumerGroups(), binding.getCredentialRef(), columns,
                binding.getPollIntervalMillis(), binding.isUseSsl(), binding.isFlinkConnectorInstalled(), connection.getJdbcUrl(), connection.getSchema(), binding.getSourceTableName(),
                binding.isSparkConnectorInstalled(), binding.isFlinkBoundedConnectorInstalled());
    }

    private static boolean permitted(IntegrationTdengineBindingsProperties.ManagedBinding binding, IntegrationTaskDO task, Long databaseId) {
        return binding != null && Objects.equals(databaseId, binding.getDatabaseId()) && contains(binding.getAllowedTaskIds(), task.getId())
                && contains(binding.getWorkerGroups(), task.getWorkerGroup()) && contains(binding.getEngines(), task.getEngine());
    }
    private static <T> boolean contains(Set<T> values, T value) { return values != null && value != null && values.contains(value); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
