package cn.iocoder.yudao.module.infra.api.file;

import cn.iocoder.yudao.framework.rpc.core.MutualTlsFeignClients;
import feign.Client;
import feign.Request;
import feign.Retryer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 使用共享 RPC 证书配置；独立 Feign 子上下文不改变既有 FileApi 的调用契约。 */
public class ManagedFileClientConfiguration {
    @Bean
    public Client managedFileClient(Environment environment, ObjectProvider<SslBundles> bundles,
            ObjectProvider<LoadBalancerClient> balancer, ObjectProvider<LoadBalancerClientFactory> factory,
            ObjectProvider<LoadBalancerFeignRequestTransformer> transformers) {
        Client tls = restrict(MutualTlsFeignClients.fromBundle(environment.getProperty("yudao.rpc.client.ssl-bundle"),
                bundles.getIfAvailable(), "yudao.rpc.client.ssl-bundle"));
        if (org.springframework.util.StringUtils.hasText(environment.getProperty("spring.cloud.openfeign.client.config.managedFileApi.url")))
            return tls;
        return restrict(MutualTlsFeignClients.loadBalanced(tls, balancer.getIfAvailable(), factory.getIfAvailable(),
                transformers.orderedStream().toList()));
    }

    public static Client restrict(Client delegate) {
        Map<String, Request.HttpMethod> allowed = Map.of(
                ManagedFileApi.PREFIX + "/create", Request.HttpMethod.POST,
                ManagedFileApi.PREFIX + "/get", Request.HttpMethod.GET,
                ManagedFileApi.PREFIX + "/content", Request.HttpMethod.GET,
                ManagedFileApi.PREFIX + "/user-upload", Request.HttpMethod.GET,
                ManagedFileApi.PREFIX + "/delete", Request.HttpMethod.DELETE);
        return (request, options) -> {
            if (allowed.get(URI.create(request.url()).getRawPath()) != request.httpMethod())
                throw new IllegalStateException("受管文件客户端不允许访问此路径或方法");
            return delegate.execute(request, options);
        };
    }

    @Bean public Retryer managedFileRetryer() { return Retryer.NEVER_RETRY; }
    @Bean public Request.Options managedFileOptions() { return new Request.Options(3, TimeUnit.SECONDS, 60, TimeUnit.SECONDS, false); }
}
