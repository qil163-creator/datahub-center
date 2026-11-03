package cn.iocoder.yudao.module.dataforge.framework.security;

import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "yudao.dataforge.sync-evidence.rpc")
public class SyncEvidenceRpcProperties {

    /** 只允许 governance；叶证书 SHA-256 pin 可配置新旧两个以轮换，默认拒绝。 */
    private Map<String, Set<String>> peers = Map.of();

    /**
     * 校验同步证据RPC输入及状态。
     */
    @PostConstruct
    public void validate() {
        peers = VerifiedServicePeerResolver.requireUnambiguous(peers == null ? Map.of() : peers);
        if (peers.keySet().stream().anyMatch(key -> !"governance".equals(key))) {
            throw new IllegalArgumentException("Sync evidence only accepts governance peers");
        }
    }
}

