package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.etl.core.compile.EtlDataxConnectorCompiler;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 管理员维护的 DataX 资源授权；浏览器只选择平台连接编号或受管文件绑定编号。 */
@Data
@Component
@ConfigurationProperties(prefix = "dataforge.integration.datax")
public class IntegrationDataxBindingsProperties {
    private Map<String, ManagedBinding> bindings = Map.of();

    @Data
    public static class ManagedBinding {
        private Long databaseId;
        private Set<Long> allowedTaskIds = Set.of();
        private Set<String> connectorCodes = Set.of();
        private Set<String> installedPlugins = Set.of();
        private String rootPath;
        private String defaultFs;
        private List<String> addresses = List.of();
        /** 控制面只读元数据请求的精确地址授权，默认关闭，且必须属于执行地址授权。 */
        private Set<String> metadataAddresses = Set.of();
        private String database;
        private EtlDataxConnectorCompiler.Authentication authentication = EtlDataxConnectorCompiler.Authentication.REQUIRED;
    }

    /** 控制面授权与执行资源分开，不能因工作节点可访问就允许平台服务发起请求。 */
    public Set<String> requireMetadata(Long taskId, String code, String bindingId) {
        require(taskId, code, null, bindingId);
        var binding = bindings.get(bindingId);
        if (binding.getMetadataAddresses().isEmpty() || !binding.getAddresses().containsAll(binding.getMetadataAddresses()))
            throw new IllegalArgumentException("当前执行资源未配置有效的控制面元数据地址授权");
        return Set.copyOf(binding.getMetadataAddresses());
    }

    /** 调用方还须对 databaseId 使用现有 ExecutionAccessService；此处额外按任务锁定绑定。 */
    public EtlDataxConnectorCompiler.Binding require(Long taskId, String code, Long databaseId, String bindingId) {
        if (bindingId != null && !bindingId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))
            throw new IllegalArgumentException("受管资源绑定编号无效");
        ManagedBinding binding;
        if (databaseId != null) {
            var candidates = bindings.entrySet().stream().filter(entry -> Objects.equals(databaseId, entry.getValue().getDatabaseId())
                    && entry.getValue().getConnectorCodes().contains(code) && entry.getValue().getAllowedTaskIds().contains(taskId)).toList();
            if (candidates.size() != 1) throw new IllegalArgumentException("数据连接尚未唯一绑定当前任务允许使用的 DataX 资源");
            if (bindingId != null && !bindingId.equals(candidates.getFirst().getKey()))
                throw new IllegalArgumentException("节点资源绑定编号与已授权数据连接资源不一致");
            binding = candidates.getFirst().getValue();
        } else {
            if (bindingId == null || !bindingId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))
                throw new IllegalArgumentException("请选择管理员为当前任务授权的 DataX 文件资源绑定");
            binding = bindings.get(bindingId);
            if (binding == null || binding.getDatabaseId() != null || !binding.getAllowedTaskIds().contains(taskId)
                    || !binding.getConnectorCodes().contains(code))
                throw new IllegalArgumentException("当前任务无权使用该 DataX 文件资源绑定");
        }
        return new EtlDataxConnectorCompiler.Binding(binding.getConnectorCodes(), binding.getRootPath(), binding.getDefaultFs(),
                binding.getAddresses(), binding.getDatabase(), binding.getAuthentication(), binding.getInstalledPlugins());
    }
}
