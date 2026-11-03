package cn.iocoder.yudao.gateway.filter.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.CodecConfigurer;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AccessLogFilterSensitiveTest {

    @ParameterizedTest
    @ValueSource(strings = {"/supply-api/supply/api-test/send", "/admin-api/supply/api-test/send",
            "/admin-api/supply/api-test/detail", "/supply/gateway/services/v1/orders",
            "/supply-api/supply/api-%74est/send", "/supply-api/supply/api-test;matrix=value/send",
            "/admin-api/dataforge/file-resource/preview", "/dataforge-api/dataforge/file-resource/preview-content",
            "/admin-api/dataforge/file-resource/upload", "/admin-api/dataforge/file-resource/download-batch",
            "/admin-api/dataforge/file-%72esource/preview", "/admin-api/dataforge/file-resource;matrix=value/preview"})
    void sensitiveBodiesHeadersAndQueryAreNeverCapturedOrLogged(String path) {
        Logger logger = (Logger) LoggerFactory.getLogger(AccessLogFilter.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.setContext(logger.getLoggerContext());
        captured.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(captured);
        try {
            String requestBody = "{\"headers\":[{\"name\":\"Authorization\",\"value\":\"body-secret\"}]}";
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .method(org.springframework.http.HttpMethod.POST, java.net.URI.create(path + "?token=query-secret"))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer header-secret").header("Cookie", "cookie-secret")
                    .body(requestBody));
            AtomicReference<String> downstreamBody = new AtomicReference<>();
            new AccessLogFilter().filter(exchange, forwarded -> DataBufferUtils.join(forwarded.getRequest().getBody())
                    .flatMap(buffer -> {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        DataBufferUtils.release(buffer);
                        downstreamBody.set(new String(bytes, StandardCharsets.UTF_8));
                        forwarded.getResponse().setStatusCode(HttpStatus.OK);
                        forwarded.getResponse().getHeaders().add("Set-Cookie", "response-cookie-secret");
                        return forwarded.getResponse().writeWith(Mono.just(forwarded.getResponse().bufferFactory()
                                .wrap("response-body-secret".getBytes(StandardCharsets.UTF_8))));
                    })).block();
            assertThat(downstreamBody.get()).isEqualTo(requestBody);
            assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("response-body-secret");
            assertThat(captured.list).hasSize(1);
            assertThat(captured.list.getFirst().getFormattedMessage())
                    .contains("requestMethod", "requestUrl", "duration", "httpStatus")
                    .doesNotContain("query-secret", "header-secret", "body-secret", "cookie-secret",
                            "response-cookie-secret", "queryParams", "requestHeaders", "responseHeaders", "requestBody", "responseBody");
        } finally {
            logger.detachAppender(captured);
            logger.setLevel(originalLevel);
            captured.stop();
        }
    }

    @Test
    void sensitivePathSelectionLeavesOtherRoutesUnchanged() {
        assertThat(AccessLogFilter.isSensitivePath("/admin-api/system/user/page")).isFalse();
        assertThat(AccessLogFilter.isSensitivePath("/supply-api/supply/data-api/invoke")).isFalse();
        assertThat(AccessLogFilter.isSensitivePath("/admin-api/dataforge/database/page")).isFalse();
        assertThat(AccessLogFilter.isSensitivePath("/admin-api/dataforge/file-resource-other/preview")).isFalse();
        assertThat(AccessLogFilter.isSensitivePath(null)).isFalse();
    }

    @Test
    void minimalSpringContextStillWiresAccessLogFilter() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(CodecConfigurer.class, ServerCodecConfigurer::create);
            context.register(AccessLogFilter.class);
            context.refresh();
            assertThat(context.getBean(AccessLogFilter.class)).isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/supply/gateway/services/v1/orders", "/admin-api/dataforge/file-resource/preview"})
    void gatewayRoutingExceptionsOmitQueryAndExceptionEchoedSecrets(String path) {
        Logger logger = (Logger) LoggerFactory.getLogger(cn.iocoder.yudao.gateway.handler.GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.setContext(logger.getLoggerContext());
        captured.start();
        logger.addAppender(captured);
        try {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(
                    path + "?credential=QUERY_LOG_SENTINEL"));
            var handler = new cn.iocoder.yudao.gateway.handler.GlobalExceptionHandler();
            handler.handle(exchange, new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "QUERY_LOG_SENTINEL")).block();
            handler.defaultExceptionHandler(exchange, new IllegalStateException("QUERY_LOG_SENTINEL"));
            assertThat(captured.list).hasSize(2);
            for (ILoggingEvent event : captured.list) {
                assertThat(event.getFormattedMessage()).contains(path)
                        .doesNotContain("QUERY_LOG_SENTINEL", "credential=");
                assertThat(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()))
                        .doesNotContain("QUERY_LOG_SENTINEL");
            }
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/supply-api/supply/api-test/detail", "/admin-api/dataforge/file-resource/preview"})
    void serializationFailuresOmitQueryAndObjectEchoedSecrets(String path) {
        Logger logger = (Logger) LoggerFactory.getLogger(cn.iocoder.yudao.gateway.util.WebFrameworkUtils.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.setContext(logger.getLoggerContext());
        captured.start();
        logger.addAppender(captured);
        try {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(
                    path + "?credential=QUERY_LOG_SENTINEL"));
            cn.iocoder.yudao.gateway.util.WebFrameworkUtils.writeJSON(exchange, new ThrowingResponse()).block();
            assertThat(captured.list).hasSize(1);
            ILoggingEvent event = captured.list.getFirst();
            assertThat(event.getFormattedMessage()).doesNotContain("QUERY_LOG_SENTINEL", "credential=");
            assertThat(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()))
                    .doesNotContain("QUERY_LOG_SENTINEL");
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    public static class ThrowingResponse {
        public String getValue() {
            throw new IllegalStateException("QUERY_LOG_SENTINEL");
        }
    }
}
