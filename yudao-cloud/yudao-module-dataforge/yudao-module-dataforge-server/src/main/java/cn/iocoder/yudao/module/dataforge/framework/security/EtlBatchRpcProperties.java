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
@ConfigurationProperties(prefix = "yudao.dataforge.etl-batch.rpc")
public class EtlBatchRpcProperties {

    /** 服务身份到叶证书 SHA-256 pin；缺省为空并拒绝全部调用。 */
    private Map<String, Set<String>> peers = Map.of();

    /**
     * 校验ETL批次RPC输入及状态。
     */
    @PostConstruct
    public void validate() {
        peers = VerifiedServicePeerResolver.requireUnambiguous(peers == null ? Map.of() : peers);
        if (!Set.of("asset", "supply").containsAll(peers.keySet())) {
            throw new IllegalArgumentException("ETL batch peers must be asset or supply");
        }
    }
}
