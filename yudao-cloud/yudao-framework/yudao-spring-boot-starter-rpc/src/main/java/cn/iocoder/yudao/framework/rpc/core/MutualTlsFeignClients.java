package cn.iocoder.yudao.framework.rpc.core;

import feign.Client;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 按客户端显式启用的 TLS 传输；不注册全局 Feign Bean，也不决定业务身份/权限。 */
public final class MutualTlsFeignClients {

    private MutualTlsFeignClients() { }

    /** 缺少配置时允许应用启动，但调用失败关闭；绝不回退默认明文客户端。 */
    public static Client fromBundle(String name, SslBundles bundles, String propertyName) {
        if (name == null || name.isBlank() || bundles == null) {
            return rejecting("RPC 未配置双向 TLS：缺少 " + propertyName);
        }
        try {
            return fromBundle(bundles.getBundle(name.strip()));
        } catch (NoSuchSslBundleException missing) {
            return rejecting("RPC 的 SSL bundle 不存在：" + name.strip());
        }
    }

    public static Client fromBundle(SslBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        // null hostname verifier 保留 JDK 默认端点识别；不使用 trust-all。
        Client delegate = new Client.Default(bundle.createSslContext().getSocketFactory(), null);
        return (request, options) -> {
            if (request.url() == null || !request.url().toLowerCase(Locale.ROOT).startsWith("https://")) {
                throw new IllegalStateException("RPC 只允许通过 https 调用");
            }
            return delegate.execute(request, options);
        };
    }

    /** HTTPS 校验置于负载均衡内部，对最终实例地址校验，防止发现元数据降级。 */
    public static Client loadBalanced(Client delegate, LoadBalancerClient balancer,
                                      LoadBalancerClientFactory factory,
                                      List<LoadBalancerFeignRequestTransformer> transformers) {
        if (balancer == null || factory == null) {
            return delegate;
        }
        return new FeignBlockingLoadBalancerClient(delegate, balancer, factory, List.copyOf(transformers));
    }

    private static Client rejecting(String reason) {
        return (request, options) -> { throw new IllegalStateException(reason); };
    }
}
