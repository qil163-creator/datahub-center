package cn.iocoder.yudao.module.dataforge.api.metadata;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.rpc.core.MutualTlsFeignClients;
import cn.iocoder.yudao.module.dataforge.api.metadata.dto.*;
import cn.iocoder.yudao.module.dataforge.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import feign.Client;
import feign.Request;
import feign.Retryer;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** 仅经认证治理服务的双向 TLS 通道调用；只返回有界技术元数据，不返回凭据。 */
@FeignClient(name = ApiConstants.NAME, contextId = "metadataCollectionApi",
        url = "${yudao.dataforge.metadata-collection.client.url:}", configuration = MetadataCollectionApi.ClientConfig.class)
@Tag(name = "RPC 服务 - 元数据采集源")
public interface MetadataCollectionApi {

    String PREFIX = ApiConstants.PREFIX + "/metadata-collection";

    /**
     * 获得启用的采集连接和来源系统，不含凭据。
     *
     * @param actorId 操作人编号
     * @return 元数据来源选项
     */
    @GetMapping(PREFIX + "/options")
    @Operation(summary = "获得启用的采集连接和来源系统，不含凭据")
    CommonResult<MetadataSourceOptionsDTO> options(@RequestParam("actorId") Long actorId);

    /**
     * 只读采集远端目录技术元数据，最多 10000 项、32 层。
     *
     * @param id 记录编号
     * @param path 路径
     * @param actorId 操作人编号
     * @return 只读采集远端目录技术元数据，最多 10000 项、32 层结果集合
     */
    @GetMapping(PREFIX + "/file-manifest")
    @Operation(summary = "只读采集远端目录技术元数据，最多 10000 项、32 层")
    CommonResult<List<MetadataFileEntryDTO>> fileManifest(@RequestParam("id") Long id,
            @RequestParam("path") String path, @RequestParam("actorId") Long actorId);

    /**
     * 校验启用的来源系统。
     *
     * @param id 记录编号
     * @param actorId 操作人编号
     * @return 系统选项
     */
    @GetMapping(PREFIX + "/system")
    @Operation(summary = "校验启用的来源系统")
    CommonResult<MetadataSourceOptionsDTO.SystemOption> system(@RequestParam("id") Long id,
            @RequestParam("actorId") Long actorId);

    /** 仅此 Feign 子上下文使用，不注册全局客户端。 */
    class ClientConfig {

        public static final String SSL_BUNDLE_PROPERTY = "yudao.dataforge.metadata-collection.client.ssl-bundle";

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
        public Client metadataCollectionClient(Environment environment, ObjectProvider<SslBundles> bundles,
                ObjectProvider<LoadBalancerClient> balancer, ObjectProvider<LoadBalancerClientFactory> factory,
                ObjectProvider<LoadBalancerFeignRequestTransformer> transformers) {
            return MutualTlsFeignClients.loadBalanced(
                    MutualTlsFeignClients.fromBundle(
                            environment.getProperty(SSL_BUNDLE_PROPERTY), bundles.getIfAvailable(),
                            SSL_BUNDLE_PROPERTY),
                    balancer.getIfAvailable(), factory.getIfAvailable(), transformers.orderedStream().toList());
        }

        /**
         * 注册选项组件。
         *
         * @return 选项
         */
        @Bean
        public Request.Options metadataCollectionOptions() {
            return new Request.Options(5, TimeUnit.SECONDS, 90, TimeUnit.SECONDS, false);
        }

        /**
         * 注册重试器组件。
         *
         * @return 重试器
         */
        @Bean
        public Retryer metadataCollectionRetryer() {
            return Retryer.NEVER_RETRY;
        }
    }
}
