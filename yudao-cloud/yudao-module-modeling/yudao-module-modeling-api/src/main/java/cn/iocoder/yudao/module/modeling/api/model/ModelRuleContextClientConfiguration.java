package cn.iocoder.yudao.module.modeling.api.model;

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
import java.util.concurrent.TimeUnit;

/** 仅由专用 Feign 子上下文加载；缺少 TLS 配置时调用失败关闭。 */
public class ModelRuleContextClientConfiguration {

    public static final String URL_PROPERTY = "yudao.modeling.rule-context.client.url";

    public static final String SSL_BUNDLE_PROPERTY = "yudao.modeling.rule-context.client.ssl-bundle";

    /**
     * 注册客户端组件。
     *
     * @param environment 环境
     * @param bundles 资源包
     * @param balancer 均衡器
     * @param factory 工厂
     * @param transformers 转换器
     * @return 客户端
     */
    @Bean
    public Client modelRuleContextClient(Environment environment, ObjectProvider<SslBundles> bundles,
            ObjectProvider<LoadBalancerClient> balancer, ObjectProvider<LoadBalancerClientFactory> factory,
            ObjectProvider<LoadBalancerFeignRequestTransformer> transformers) {
        Client tls = MutualTlsFeignClients.fromBundle(environment.getProperty(SSL_BUNDLE_PROPERTY),
                bundles.getIfAvailable(), SSL_BUNDLE_PROPERTY);
        // 显式地址不经过服务发现，避免外层白名单包装隐藏 LB 类型后无法由 Feign 解包。
        if (org.springframework.util.StringUtils.hasText(environment.getProperty(URL_PROPERTY))
                || org.springframework.util.StringUtils.hasText(environment.getProperty(
                "spring.cloud.openfeign.client.config.modelRuleContextApi.url"))) {
            return restrict(tls);
        }
        return restrict(MutualTlsFeignClients.loadBalanced(restrict(tls), balancer.getIfAvailable(),
                factory.getIfAvailable(), transformers.orderedStream().toList()));
    }

    /** 双层白名单同时约束逻辑请求和发现后的最终实例请求。 */
    public static Client restrict(Client delegate) {
        return (request, options) -> {
            URI uri = URI.create(request.url());
            if (request.httpMethod() != Request.HttpMethod.GET || !ModelRuleContextApi.PATH.equals(uri.getRawPath())) {
                throw new IllegalStateException("模型规则候选客户端仅允许指定查询路径");
            }
            return delegate.execute(request, options);
        };
    }

    /**
     * 注册重试器组件。
     *
     * @return 重试器
     */
    @Bean
    public Retryer modelRuleContextRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /**
     * 注册选项组件。
     *
     * @return 选项
     */
    @Bean
    public Request.Options modelRuleContextOptions() {
        return new Request.Options(2, TimeUnit.SECONDS, 5, TimeUnit.SECONDS, false);
    }
}
