package cn.iocoder.yudao.module.dataforge.framework.security;

import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Set;

@ConfigurationProperties("yudao.dataforge.published-query.rpc")
public record PublishedQueryRpcProperties(Map<String, Set<String>> peers) {
    public PublishedQueryRpcProperties {
        peers = VerifiedServicePeerResolver.requireUnambiguous(peers == null ? Map.of() : peers);
        if (!Set.of("supply").containsAll(peers.keySet())) {
            throw new IllegalArgumentException("已发布数据接口仅接受 Supply 服务");
        }
    }
}
