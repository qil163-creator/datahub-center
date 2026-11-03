package cn.iocoder.yudao.module.dataforge.framework.security;

import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** 元数据采集仅接收治理服务的证书身份，默认不信任任何调用方。 */
@Data
@Component
@ConfigurationProperties(prefix = "yudao.dataforge.metadata-collection.rpc")
public class MetadataCollectionRpcProperties {

    /** 治理服务叶证书 DER 的 SHA-256 指纹；轮换时可同时配置新旧指纹。 */
    private Map<String, Set<String>> peers = Map.of();

    /**
     * 校验元数据集合RPC输入及状态。
     */
    @PostConstruct
    public void validate() {
        peers = VerifiedServicePeerResolver.requireUnambiguous(peers == null ? Map.of() : peers);
        if (peers.keySet().stream().anyMatch(peer -> !"governance".equals(peer))) {
            throw new IllegalArgumentException("元数据采集 RPC 仅允许 governance 服务身份");
        }
    }
}
