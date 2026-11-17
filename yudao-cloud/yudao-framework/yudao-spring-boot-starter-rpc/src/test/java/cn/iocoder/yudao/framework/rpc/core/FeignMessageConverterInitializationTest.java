package cn.iocoder.yudao.framework.rpc.core;

import cn.iocoder.yudao.framework.rpc.config.YudaoFeignAutoConfiguration;
import feign.Client;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.FeignClientFactory;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.cloud.openfeign.support.HttpMessageConverterCustomizer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FeignMessageConverterInitializationTest {

    @Test
    void eachClientFinishesApplyingItsConvertersBeforeConcurrentRequests() throws Exception {
        var boot = new AtomicInteger();
        var parentConverter = new StringHttpMessageConverter();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(YudaoFeignAutoConfiguration.class, FeignAutoConfiguration.class, Clients.class);
            context.registerBean(ClientHttpMessageConvertersCustomizer.class, () -> builder -> {
                boot.incrementAndGet();
                builder.addCustomConverter(parentConverter);
            });
            context.refresh();
            var a = context.getBean(First.class);
            var b = context.getBean(Second.class);
            // 尚未发送请求，也未调用 getConverters，两个子上下文均应已完成初始化。
            assertEquals(2, boot.get());
            var factory = context.getBean(FeignClientFactory.class);
            var first = factory.getInstance("first", FeignHttpMessageConverters.class).getConverters();
            var second = factory.getInstance("second", FeignHttpMessageConverters.class).getConverters();
            assertTrue(first.contains(parentConverter) && second.contains(parentConverter));
            assertTrue(first.contains(FirstConfig.MARKER));
            assertFalse(second.contains(FirstConfig.MARKER));

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Callable<String>> calls = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    calls.add(() -> a.echo("first"));
                    calls.add(() -> b.echo("second"));
                }
                var results = executor.invokeAll(calls);
                for (int i = 0; i < results.size(); i++) {
                    assertEquals(i % 2 == 0 ? "first" : "second", results.get(i).get());
                }
            }
            assertEquals(2, boot.get());
        }
    }

    @EnableFeignClients(clients = {First.class, Second.class})
    static class Clients {
        @Bean
        Client echoTransport() {
            return (request, options) -> Response.builder().request(request).status(200).reason("OK")
                    .headers(Map.of("Content-Type", List.of("text/plain"))).body(request.body()).build();
        }
    }

    static class FirstConfig {
        static final StringHttpMessageConverter MARKER = new StringHttpMessageConverter();

        @Bean
        HttpMessageConverterCustomizer firstConverter() {
            return list -> list.add(MARKER);
        }
    }

    interface Echo {
        @PostMapping(path = "/echo", consumes = "text/plain", produces = "text/plain")
        String echo(@RequestBody String text);
    }

    @FeignClient(name = "first", url = "http://localhost", configuration = FirstConfig.class)
    interface First extends Echo {}
    @FeignClient(name = "second", url = "http://localhost")
    interface Second extends Echo {}
}
