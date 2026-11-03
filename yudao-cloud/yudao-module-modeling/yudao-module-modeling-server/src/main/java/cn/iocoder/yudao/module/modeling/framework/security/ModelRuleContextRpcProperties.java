package cn.iocoder.yudao.module.modeling.framework.security;

import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "yudao.modeling.rule-context.rpc")
public class ModelRuleContextRpcProperties {

    /** 部署时配置治理服务的客户端证书指纹，默认不信任任何调用者。 */
    private Map<String, Set<String>> peers = Map.of();

    /**
     * 校验模型规则上下文RPC输入及状态。
     */
    @PostConstruct
    public void validate() {
        peers = VerifiedServicePeerResolver.requireUnambiguous(peers == null ? Map.of() : peers);
        if (!Set.of("governance").containsAll(peers.keySet())) {
            throw new IllegalArgumentException("模型规则候选仅信任治理服务");
        }
    }
}
