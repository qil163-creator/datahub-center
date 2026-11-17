package cn.iocoder.yudao.module.dataforge.api.database;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.rpc.core.MutualTlsFeignClients;
import cn.iocoder.yudao.module.dataforge.enums.ApiConstants;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Supply 执行已发布的查询，或凭已认领的操作票据执行写入；数据源凭据仅在 DataForge 内使用。 */
@FeignClient(name = ApiConstants.NAME, contextId = "supplyPublishedQueryApi", configuration = PublishedQueryApi.ClientConfig.class)
public interface PublishedQueryApi {

    @PostMapping(ApiConstants.PREFIX + "/published-query/page")
    CommonResult<PageResult<Map<String, Object>>> query(@Valid @RequestBody Query query);

    /** 仅提供受限写计划编译所需的信息，不返回连接地址或凭据。 */
    @PostMapping(ApiConstants.PREFIX + "/published-write/profile")
    CommonResult<Profile> writeProfile(@Valid @RequestBody Source source);

    /** 只接受 Supply 已认领操作对应的一次性随机票据。 */
    @PostMapping(ApiConstants.PREFIX + "/published-write/execute")
    CommonResult<List<Integer>> executeClaim(@Valid @RequestBody Ticket ticket);

    record Source(@NotNull @Positive Long databaseId) { }
    record Profile(String productType, String schema, String revision) { }
    record Ticket(@NotNull @Pattern(regexp = "[a-f0-9]{64}") String value) { }

    record Query(@NotNull @Positive Long databaseId, @NotBlank String sql,
                 @NotNull List<@NotNull @Valid Bind> bindings, @Positive int page,
                 @Min(1) @Max(1000) int limit, @Min(1) @Max(300) int timeoutSeconds, @Valid Projection projection) { }
    record Projection(@NotBlank String schema, @NotBlank String table, @NotEmpty List<@NotBlank String> approvedColumns) { }

    /** 固定 JDBC 绑定类型，避免 JSON 对大整数和日期的隐式转换。 */
    record Bind(@NotNull Type type, String value) {
        public enum Type { STRING, LONG, DOUBLE, TIMESTAMP, NULL }

        public Bind {
            java.util.Objects.requireNonNull(type, "查询参数缺少类型");
            if ((type == Type.NULL) != (value == null)) {
                throw new IllegalArgumentException("查询参数的空值与声明类型不一致");
            }
        }

        public static Bind from(Object value) {
            return switch (value) {
                case null -> new Bind(Type.NULL, null);
                case Long n -> new Bind(Type.LONG, n.toString());
                case Double n -> new Bind(Type.DOUBLE, n.toString());
                case Timestamp t -> new Bind(Type.TIMESTAMP, t.toInstant().toString());
                case String s -> new Bind(Type.STRING, s);
                default -> throw new IllegalArgumentException("不支持的查询参数类型");
            };
        }

        public Object jdbcValue() {
            return switch (type) {
                case NULL -> null;
                case STRING -> value;
                case LONG -> Long.valueOf(value);
                case DOUBLE -> Double.valueOf(value);
                case TIMESTAMP -> Timestamp.from(java.time.Instant.parse(value));
            };
        }
    }

    class ClientConfig {
        @Bean
        public Client publishedQueryClient(Environment env, ObjectProvider<SslBundles> bundles,
                ObjectProvider<LoadBalancerClient> balancer, ObjectProvider<LoadBalancerClientFactory> factory,
                ObjectProvider<LoadBalancerFeignRequestTransformer> transformers) {
            String key = "yudao.dataforge.published-query.client.ssl-bundle";
            return MutualTlsFeignClients.loadBalanced(MutualTlsFeignClients.fromBundle(env.getProperty(key),
                            bundles.getIfAvailable(), key), balancer.getIfAvailable(), factory.getIfAvailable(),
                    transformers.orderedStream().toList());
        }

        @Bean
        public feign.Logger.Level publishedQueryLogLevel() {
            return feign.Logger.Level.NONE;
        }

        @Bean
        public Retryer publishedQueryRetryer() {
            return Retryer.NEVER_RETRY;
        }

        @Bean
        public Request.Options publishedQueryOptions() {
            // DB Switch 的数据查询和总数查询分别受 JDBC 超时限制。
            return new Request.Options(3, TimeUnit.SECONDS, 610, TimeUnit.SECONDS, false);
        }
    }
}
