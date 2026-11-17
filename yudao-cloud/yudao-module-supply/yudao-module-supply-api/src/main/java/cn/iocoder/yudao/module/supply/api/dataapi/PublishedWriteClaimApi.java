package cn.iocoder.yudao.module.supply.api.dataapi;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.rpc.core.MutualTlsFeignClients;
import cn.iocoder.yudao.module.supply.enums.ApiConstants;
import feign.Client;
import feign.Request;
import feign.Retryer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** DataForge 通过固定的双向 TLS 回调领取 Supply 已认领的单次操作。 */
@FeignClient(name = ApiConstants.NAME, contextId = "dataforgeWriteClaimApi", configuration = PublishedWriteClaimApi.ClientConfig.class)
public interface PublishedWriteClaimApi {
    @PostMapping(ApiConstants.PREFIX + "/write-claim/redeem")
    CommonResult<Contract> redeem(@Valid @RequestBody Ticket ticket);

    record Ticket(@NotNull @Pattern(regexp = "[a-f0-9]{64}") String value) { }
    record Statement(int ordinal, String schema, String table, String sql) { }
    record Contract(long appId, long apiId, long releaseId, long operationId, long databaseId,
                    String revision, String apiType, List<Statement> steps, List<Bind> bindings, int timeoutSeconds) { }

    /** 参数和语句分开传输，JSON 数字不参与精确值或日期的转换。 */
    record Bind(String type, String value) {
        public static Bind from(Object value) {
            if (value == null) {
                return new Bind("NULL", null);
            }
            return switch (value) {
                case Long number -> new Bind("LONG", number.toString());
                case String text -> new Bind("STRING", text);
                case Timestamp timestamp -> new Bind("TIMESTAMP", timestamp.toInstant().toString());
                default -> throw new IllegalArgumentException("不支持的写入参数类型");
            };
        }
        public Object jdbcValue() {
            return switch (type) {
                case "NULL" -> {
                    if (value != null) {
                        throw new IllegalArgumentException("空值参数无效");
                    }
                    yield null;
                }
                case "LONG" -> Long.valueOf(value);
                case "STRING" -> java.util.Objects.requireNonNull(value);
                case "TIMESTAMP" -> Timestamp.from(java.time.Instant.parse(value));
                default -> throw new IllegalArgumentException("不支持的写入参数类型");
            };
        }
    }

    class ClientConfig {
        @Bean
        public Client writeClaimClient(Environment env, ObjectProvider<SslBundles> bundles,
                ObjectProvider<LoadBalancerClient> balancer, ObjectProvider<LoadBalancerClientFactory> factory,
                ObjectProvider<LoadBalancerFeignRequestTransformer> transformers) {
            String key = "yudao.supply.write-claim.client.ssl-bundle";
            return MutualTlsFeignClients.loadBalanced(MutualTlsFeignClients.fromBundle(env.getProperty(key),
                            bundles.getIfAvailable(), key), balancer.getIfAvailable(), factory.getIfAvailable(),
                    transformers.orderedStream().toList());
        }

        @Bean
        public feign.Logger.Level writeClaimLogLevel() {
            return feign.Logger.Level.NONE;
        }

        @Bean
        public Retryer writeClaimRetryer() {
            return Retryer.NEVER_RETRY;
        }

        @Bean
        public Request.Options writeClaimOptions() {
            return new Request.Options(3, TimeUnit.SECONDS, 8, TimeUnit.SECONDS, false);
        }
    }
}
