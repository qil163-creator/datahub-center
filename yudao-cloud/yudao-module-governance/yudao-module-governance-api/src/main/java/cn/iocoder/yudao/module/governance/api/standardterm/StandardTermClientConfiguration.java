package cn.iocoder.yudao.module.governance.api.standardterm;

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

import java.util.concurrent.TimeUnit;

/** Only loaded in this Feign child context; absent TLS configuration rejects calls. */
public class StandardTermClientConfiguration {

    public static final String SSL_BUNDLE_PROPERTY = "yudao.governance.standard-term.client.ssl-bundle";

    /**
     * 创建并配置标准术语客户端组件。
     */
    @Bean
    public Client standardTermClient(Environment environment, ObjectProvider<SslBundles> bundles,
            ObjectProvider<LoadBalancerClient> balancer, ObjectProvider<LoadBalancerClientFactory> factory,
            ObjectProvider<LoadBalancerFeignRequestTransformer> transformers) {
        return MutualTlsFeignClients.loadBalanced(MutualTlsFeignClients.fromBundle(
                        environment.getProperty(SSL_BUNDLE_PROPERTY), bundles.getIfAvailable(), SSL_BUNDLE_PROPERTY),
                balancer.getIfAvailable(), factory.getIfAvailable(), transformers.orderedStream().toList());
    }

    /**
     * 创建并配置标准术语重试策略组件。
     */
    @Bean
    public Retryer standardTermRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /**
     * 创建并配置标准术语选项组件。
     */
    @Bean
    public Request.Options standardTermOptions() {
        return new Request.Options(2, TimeUnit.SECONDS, 5, TimeUnit.SECONDS, false);
    }

}
