package cn.iocoder.yudao.module.system.framework.security.config;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;
@Data @Component @ConfigurationProperties(prefix="yudao.system.user-option.rpc")
public class AdminUserOptionRpcProperties {
    private Map<String,Set<String>> peers=Map.of();
    @PostConstruct public void validate(){
        peers=VerifiedServicePeerResolver.requireUnambiguous(peers==null?Map.of():peers);
        if(!Set.of("governance").containsAll(peers.keySet()))throw new IllegalArgumentException("账号候选通道仅允许治理服务");
    }
}
