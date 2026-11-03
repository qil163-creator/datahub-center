package cn.iocoder.yudao.module.dataforge.api.migration;

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

/** 仅独立 Feign 子上下文加载，不得标注全局 Configuration。 */
public class SyncBatchEvidenceClientConfiguration {

    public static final String SSL_BUNDLE_PROPERTY = "yudao.dataforge.sync-evidence.client.ssl-bundle";

    /**
     * 注册客户端组件。
     *
     * @param env 环境
     * @param bundles 资源包
     * @param balancer 均衡器
     * @param factory 工厂
     * @param transformers 转换器
     * @return 客户端
     */
    @Bean
    public Client syncEvidenceClient(Environment env, ObjectProvider<SslBundles> bundles,
            ObjectProvider<LoadBalancerClient> balancer,
            ObjectProvider<LoadBalancerClientFactory> factory,
            ObjectProvider<LoadBalancerFeignRequestTransformer> transformers) {
        return MutualTlsFeignClients.loadBalanced(MutualTlsFeignClients.fromBundle(
                        env.getProperty(SSL_BUNDLE_PROPERTY), bundles.getIfAvailable(), SSL_BUNDLE_PROPERTY),
                balancer.getIfAvailable(), factory.getIfAvailable(), transformers.orderedStream().toList());
    }

    /**
     * 注册重试器组件。
     *
     * @return 重试器
     */
    @Bean
    public Retryer syncEvidenceRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /**
     * 注册选项组件。
     *
     * @return 选项
     */
    @Bean
    public Request.Options syncEvidenceOptions() {
        // 无自动重试/重定向；观察失败由调用方留存未证明原因，不阻断普通质量计数。
        return new Request.Options(2, TimeUnit.SECONDS, 5, TimeUnit.SECONDS, false);
    }
}

