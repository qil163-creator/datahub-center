package cn.iocoder.yudao.module.supply.framework.security;

import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Set;

@ConfigurationProperties("yudao.supply.write-claim.rpc")
public record WriteClaimRpcProperties(Map<String, Set<String>> peers) {
    public WriteClaimRpcProperties {
        peers = VerifiedServicePeerResolver.requireUnambiguous(peers == null ? Map.of() : peers);
        if (!Set.of("dataforge").containsAll(peers.keySet())) {
            throw new IllegalArgumentException("写入凭据仅接受 DataForge 服务");
        }
    }
}
