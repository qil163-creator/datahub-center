package cn.iocoder.yudao.module.governance.api.lineage;

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

/** 血缘生产者专用客户端配置。未提供 TLS 信任材料时拒绝上报。 */
public class LineageReportClientConfiguration {

    public static final String SSL_BUNDLE_PROPERTY = "yudao.governance.lineage-report.client.ssl-bundle";

    /**
     * 创建并配置血缘上报客户端组件。
     */
    @Bean
    public Client lineageReportClient(Environment environment, ObjectProvider<SslBundles> bundles,
            ObjectProvider<LoadBalancerClient> balancer, ObjectProvider<LoadBalancerClientFactory> factory,
            ObjectProvider<LoadBalancerFeignRequestTransformer> transformers) {
        return MutualTlsFeignClients.loadBalanced(MutualTlsFeignClients.fromBundle(
                        environment.getProperty(SSL_BUNDLE_PROPERTY), bundles.getIfAvailable(), SSL_BUNDLE_PROPERTY),
                balancer.getIfAvailable(), factory.getIfAvailable(), transformers.orderedStream().toList());
    }

    /**
     * 创建并配置血缘上报重试策略组件。
     */
    @Bean
    public Retryer lineageReportRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /**
     * 创建并配置血缘上报选项组件。
     */
    @Bean
    public Request.Options lineageReportOptions() {
        return new Request.Options(2, TimeUnit.SECONDS, 30, TimeUnit.SECONDS, false);
    }

}
