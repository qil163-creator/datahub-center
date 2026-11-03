package cn.iocoder.yudao.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** 发布的外部路由不得暴露元数据采集 RPC；服务端口仍须部署于受信内网。 */
class MetadataRpcRouteIsolationTest {
    @Test
    void externalRoutePredicatesDoNotMatchInternalRpcEvenWithForgedIdentity() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();
        List<String> patterns = properties.stringPropertyNames().stream()
                .filter(key -> key.contains(".predicates["))
                .map(properties::getProperty).filter(value -> value.startsWith("Path="))
                .flatMap(value -> Arrays.stream(value.substring(5).split(",")))
                .map(String::trim).toList();
        assertThat(patterns).contains("/admin-api/dataforge/**");
        PathRoutePredicateFactory factory = new PathRoutePredicateFactory(
                new org.springframework.boot.webflux.autoconfigure.WebFluxProperties());
        var predicate = factory.apply(new PathRoutePredicateFactory.Config().setPatterns(patterns));
        for (String path : List.of("/rpc-api/dataforge/metadata-collection/file-manifest",
                "/rpc-api/dataforge/metadata-collection/options", "/%72pc-api/dataforge/metadata-collection/file-manifest",
                "/rpc-api;ignored=x/dataforge/metadata-collection/file-manifest")) {
            var request = MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET, URI.create(path))
                    .header("login-user", "forged-identity").header("tenant-id", "1")
                    .header("Authorization", "Bearer external-user-token").build();
            assertThat(predicate.test(MockServerWebExchange.from(request))).as(path).isFalse();
        }
        assertThat(predicate.test(MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin-api/dataforge/database/page").build()))).isTrue();
    }
}
