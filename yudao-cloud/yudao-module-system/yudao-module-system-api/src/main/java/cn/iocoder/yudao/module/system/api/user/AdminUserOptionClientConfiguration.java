package cn.iocoder.yudao.module.system.api.user;

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

/** 账号候选读取的独立客户端配置；缺少服务证书时拒绝请求。 */
public class AdminUserOptionClientConfiguration {

    public static final String SSL_BUNDLE_PROPERTY = "yudao.system.user-option.client.ssl-bundle";

    @Bean
    public Client adminUserOptionClient(Environment environment, ObjectProvider<SslBundles> bundles,
            ObjectProvider<LoadBalancerClient> balancer, ObjectProvider<LoadBalancerClientFactory> factory,
            ObjectProvider<LoadBalancerFeignRequestTransformer> transformers) {
        return MutualTlsFeignClients.loadBalanced(MutualTlsFeignClients.fromBundle(
                        environment.getProperty(SSL_BUNDLE_PROPERTY), bundles.getIfAvailable(), SSL_BUNDLE_PROPERTY),
                balancer.getIfAvailable(), factory.getIfAvailable(), transformers.orderedStream().toList());
    }

    @Bean
    public Retryer adminUserOptionRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Request.Options adminUserOptionOptions() {
        return new Request.Options(2, TimeUnit.SECONDS, 5, TimeUnit.SECONDS, false);
    }
}
