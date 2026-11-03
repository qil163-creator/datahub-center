package cn.iocoder.yudao.module.governance.framework.security;

import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "yudao.governance.standard-term.rpc")
public class StandardTermRpcProperties {

    /** No caller is trusted until the asset service certificate pins are configured. */
    private Map<String, Set<String>> peers = Map.of();

    /**
     * 校验标准术语RPC配置满足当前业务约束。
     */
    @PostConstruct
    public void validate() {
        peers = VerifiedServicePeerResolver.requireUnambiguous(peers == null ? Map.of() : peers);
        if (!Set.of("asset").containsAll(peers.keySet())) {
            throw new IllegalArgumentException("Standard term peer must be asset");
        }
    }

}
