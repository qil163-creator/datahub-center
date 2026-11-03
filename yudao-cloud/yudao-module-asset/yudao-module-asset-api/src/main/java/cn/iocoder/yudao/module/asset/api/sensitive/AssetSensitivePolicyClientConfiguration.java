package cn.iocoder.yudao.module.asset.api.sensitive;

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

/** 规则工作台敏感清单分页的独立客户端配置；缺少服务证书时拒绝请求。 */
public class AssetSensitivePolicyClientConfiguration {

    public static final String SSL_BUNDLE_PROPERTY = "yudao.asset.sensitive-policy.client.ssl-bundle";

    /**
     * 创建并配置资产敏感策略客户端组件。
     */
    @Bean
    public Client assetSensitivePolicyClient(Environment environment, ObjectProvider<SslBundles> bundles,
            ObjectProvider<LoadBalancerClient> balancer, ObjectProvider<LoadBalancerClientFactory> factory,
            ObjectProvider<LoadBalancerFeignRequestTransformer> transformers) {
        return MutualTlsFeignClients.loadBalanced(MutualTlsFeignClients.fromBundle(
                        environment.getProperty(SSL_BUNDLE_PROPERTY), bundles.getIfAvailable(), SSL_BUNDLE_PROPERTY),
                balancer.getIfAvailable(), factory.getIfAvailable(), transformers.orderedStream().toList());
    }

    /**
     * 创建并配置资产敏感策略重试策略组件。
     */
    @Bean
    public Retryer assetSensitivePolicyRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /**
     * 创建并配置资产敏感策略选项组件。
     */
    @Bean
    public Request.Options assetSensitivePolicyOptions() {
        return new Request.Options(2, TimeUnit.SECONDS, 5, TimeUnit.SECONDS, false);
    }
}
