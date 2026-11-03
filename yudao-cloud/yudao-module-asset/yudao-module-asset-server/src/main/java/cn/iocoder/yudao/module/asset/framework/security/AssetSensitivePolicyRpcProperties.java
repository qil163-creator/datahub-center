package cn.iocoder.yudao.module.asset.framework.security;

import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Set;

@Data
@ConfigurationProperties("yudao.asset.sensitive-policy.rpc")
public class AssetSensitivePolicyRpcProperties {

    private Map<String, Set<String>> peers = Map.of();

    /**
     * 校验规则工作台敏感清单 RPC配置满足当前业务约束。
     */
    @PostConstruct
    public void validate() {
        peers = VerifiedServicePeerResolver.requireUnambiguous(peers == null ? Map.of() : peers);
        if (!Set.of("governance").containsAll(peers.keySet())) {
            throw new IllegalArgumentException("规则工作台敏感清单通道仅允许治理服务身份");
        }
    }
}
