package cn.iocoder.yudao.module.supply.api.app;

import feign.Client;
import cn.iocoder.yudao.framework.rpc.core.MutualTlsFeignClients;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;


/**
 * {@link SupplyGrantSourceApi} 专属的 Feign 客户端配置，只通过 {@code @FeignClient(configuration = ...)} 载入独立 contextId，
 * 不得标注 {@code @Configuration}，否则会被组件扫描成为全局配置影响其他调用方。
 *
 * <p>调用方通过 Spring Boot SSL bundle（{@value #SSL_BUNDLE_PROPERTY}）提供自己的客户端证书与信任库，
 * 建立经过服务端证书链和主机名校验的双向 TLS；未配置 bundle 时每次调用都失败关闭，不退回明文、不使用 trust-all。
 * 负载均衡解析出的实例地址仍必须是 https，否则拒绝发送。
 */
public class SupplyGrantSourceClientConfiguration {

    /** 调用方配置：用于来源级授权 v2 RPC 的 SSL bundle 名称（spring.ssl.bundle.* 中定义）。 */
    public static final String SSL_BUNDLE_PROPERTY = "yudao.supply.grant-source.client.ssl-bundle";

    /**
     * 注册客户端组件。
     *
     * @param environment 环境
     * @param sslBundles SSL资源包
     * @param loadBalancerClient 加载均衡器客户端
     * @param loadBalancerClientFactory 加载均衡器客户端工厂
     * @param transformers 转换器
     * @return 客户端
     */
    @Bean
    public Client supplyGrantSourceFeignClient(Environment environment, ObjectProvider<SslBundles> sslBundles,
            ObjectProvider<LoadBalancerClient> loadBalancerClient,
            ObjectProvider<LoadBalancerClientFactory> loadBalancerClientFactory,
            ObjectProvider<LoadBalancerFeignRequestTransformer> transformers) {
        Client delegate = mutualTlsClient(environment.getProperty(SSL_BUNDLE_PROPERTY), sslBundles.getIfAvailable());
        return MutualTlsFeignClients.loadBalanced(delegate, loadBalancerClient.getIfAvailable(),
                loadBalancerClientFactory.getIfAvailable(), transformers.orderedStream().toList());
    }

    /** 按名称解析 bundle；未配置或不存在时返回失败关闭的客户端，而不是抛出异常阻止调用方启动。 */
    public static Client mutualTlsClient(String bundleName, SslBundles bundles) {
        return MutualTlsFeignClients.fromBundle(bundleName, bundles, SSL_BUNDLE_PROPERTY);
    }

    /**
     * 使用 bundle 的密钥管理器（客户端证书）与信任管理器（服务端 CA）建立 TLS；
     * 保留 JDK HttpsURLConnection 默认的 HTTPS 端点识别算法进行主机名校验，不自定义信任逻辑。
     */
    public static Client mutualTlsClient(SslBundle bundle) {
        return MutualTlsFeignClients.fromBundle(bundle);
    }

}
