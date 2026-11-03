package cn.iocoder.yudao.module.infra.framework.security.config;

import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.infra.api.file.ManagedFileApi;
import cn.iocoder.yudao.module.infra.framework.security.ManagedFileRpcFilter;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.ResolvableType;
import java.util.Map;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
public class ManagedFileRpcConfiguration {
    @Bean public FilterRegistrationBean<ManagedFileRpcFilter> managedFileRpcFilter(Environment environment) {
        ResolvableType type = ResolvableType.forClassWithGenerics(Map.class, ResolvableType.forClass(String.class),
                ResolvableType.forClassWithGenerics(Set.class, String.class));
        Map<String, Set<String>> peers = Binder.get(environment).bind("yudao.rpc.peers",
                Bindable.<Map<String, Set<String>>>of(type)).orElse(Map.of());
        var trusted = VerifiedServicePeerResolver.requireUnambiguous(peers);
        if (trusted.keySet().stream().anyMatch(name -> !name.matches("[A-Za-z0-9_-]{1,64}")))
            throw new IllegalArgumentException("RPC 服务标识无效");
        var registration = new FilterRegistrationBean<>(new ManagedFileRpcFilter(() -> trusted));
        registration.addUrlPatterns(ManagedFileApi.PREFIX + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("managedFileRpcFilter");
        return registration;
    }
}
