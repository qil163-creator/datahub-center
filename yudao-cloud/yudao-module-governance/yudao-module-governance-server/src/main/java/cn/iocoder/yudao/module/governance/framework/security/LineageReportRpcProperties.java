package cn.iocoder.yudao.module.governance.framework.security;

import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Set;

@ConfigurationProperties("yudao.governance.lineage-report.rpc")
public record LineageReportRpcProperties(Map<String, Set<String>> peers) {
    public LineageReportRpcProperties {
        peers = VerifiedServicePeerResolver.requireUnambiguous(peers == null ? Map.of() : peers);
        if (!Set.of("dataforge").containsAll(peers.keySet())) {
            throw new IllegalArgumentException("血缘上报仅接受 DataForge 服务");
        }
    }
}
