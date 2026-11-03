package cn.iocoder.yudao.module.modeling.api.model;

import feign.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;
import org.springframework.mock.env.MockEnvironment;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelRuleContextClientTest {
    @Test void transportRejectsOtherMethodsAndPathsBeforeDelegate() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Client client = ModelRuleContextClientConfiguration.restrict((request, options) -> {
            calls.incrementAndGet(); return Response.builder().request(request).status(200).headers(Map.of()).build();
        });
        client.execute(request(Request.HttpMethod.GET, ModelRuleContextApi.PATH + "?standardId=7"), new Request.Options());
        assertThrows(IllegalStateException.class, () -> client.execute(request(Request.HttpMethod.POST, ModelRuleContextApi.PATH), new Request.Options()));
        assertThrows(IllegalStateException.class, () -> client.execute(request(Request.HttpMethod.GET, ModelFieldApi.PREFIX + "/list-standard-bound"), new Request.Options()));
        assertEquals(1, calls.get());
    }
    @Test void missingTlsConfigNeverFallsBackToDefaultTransport() {
        var beans = new DefaultListableBeanFactory();
        Client client = new ModelRuleContextClientConfiguration().modelRuleContextClient(new MockEnvironment(),
                beans.getBeanProvider(SslBundles.class), beans.getBeanProvider(LoadBalancerClient.class),
                beans.getBeanProvider(LoadBalancerClientFactory.class), beans.getBeanProvider(LoadBalancerFeignRequestTransformer.class));
        assertThrows(IllegalStateException.class, () -> client.execute(request(Request.HttpMethod.GET, ModelRuleContextApi.PATH), new Request.Options()));
        FeignClient annotation = ModelRuleContextApi.class.getAnnotation(FeignClient.class);
        assertEquals("modelRuleContextApi", annotation.contextId());
        assertArrayEquals(new Class[]{ModelRuleContextClientConfiguration.class}, annotation.configuration());
        assertThrows(NoSuchMethodException.class, () -> ModelFieldApi.class.getMethod("getRuleContextFieldList", Long.class));
    }
    @Test void realFeignContractPropagatesCurrentActorThroughDedicatedReadPath() {
        var captured = new java.util.concurrent.atomic.AtomicReference<Request>();
        cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.setLoginUser(
                new cn.iocoder.yudao.framework.security.core.LoginUser().setId(9007199254740993L).setUserType(2),
                new org.springframework.mock.web.MockHttpServletRequest());
        try {
            var client = Feign.builder().contract(new org.springframework.cloud.openfeign.support.SpringMvcContract())
                    .decoder((response, type) -> cn.iocoder.yudao.framework.common.pojo.CommonResult.success(java.util.List.of()))
                    .requestInterceptor(new cn.iocoder.yudao.framework.security.core.rpc.LoginUserRequestInterceptor())
                    .client(ModelRuleContextClientConfiguration.restrict((request, options) -> {
                        captured.set(request);
                        return Response.builder().request(request).status(200).headers(Map.of()).body("{}", StandardCharsets.UTF_8).build();
                    })).target(ModelRuleContextApi.class, "https://modeling.test");
            assertTrue(client.getRuleContextFieldList(7L).getCheckedData().isEmpty());
            assertEquals("https://modeling.test" + ModelRuleContextApi.PATH + "?standardId=7", captured.get().url());
            String raw = captured.get().headers().get(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.LOGIN_USER_HEADER).iterator().next();
            var actor = cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(
                    java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8), cn.iocoder.yudao.framework.security.core.LoginUser.class);
            assertEquals(9007199254740993L, actor.getId());
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    @Test void dedicatedAndLegacyFeignClientsCoexistWithoutGlobalTransportOverride() {
        var loadBalancerFactory = mock(LoadBalancerClientFactory.class);
        var balancer = new org.springframework.cloud.loadbalancer.blocking.client.BlockingLoadBalancerClient(loadBalancerFactory);
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.cloud.openfeign.FeignAutoConfiguration.class))
                .withUserConfiguration(ClientRegistration.class)
                .withBean(LoadBalancerClient.class, () -> balancer)
                .withBean(LoadBalancerClientFactory.class, () -> loadBalancerFactory)
                .withPropertyValues("yudao.modeling.rule-context.client.url=https://modeling.test",
                        "spring.cloud.openfeign.client.config.modeling-server.url=https://legacy.test")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var api = context.getBean(ModelRuleContextApi.class);
                    assertNotNull(api);
                    var error = assertThrows(IllegalStateException.class, () -> api.getRuleContextFieldList(7L));
                    assertTrue(error.getMessage().contains(ModelRuleContextClientConfiguration.SSL_BUNDLE_PROPERTY));
                    verify(loadBalancerFactory, never()).getInstance(anyString());
                    verify(loadBalancerFactory, never()).getProperties(anyString());
                    assertNotNull(context.getBean(ModelFieldApi.class));
                    assertTrue(context.getBeansOfType(Client.class).isEmpty());
                    var factory = context.getBean(org.springframework.cloud.openfeign.FeignClientFactory.class);
                    Client transport = factory.getInstance("modelRuleContextApi", Client.class);
                    assertNotNull(transport);
                    assertThrows(IllegalStateException.class, () -> transport.execute(request(Request.HttpMethod.GET, ModelRuleContextApi.PATH), new Request.Options()));
                });
    }
    @Test void serviceDiscoveryStillUsesRealBlockingBalancerBeforeTlsDelegate() throws Exception {
        var factory = mock(LoadBalancerClientFactory.class);
        var balancer = new org.springframework.cloud.loadbalancer.blocking.client.BlockingLoadBalancerClient(factory);
        var selections = new AtomicInteger();
        org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer selecting = request -> {
            selections.incrementAndGet();
            return reactor.core.publisher.Mono.just(new org.springframework.cloud.client.loadbalancer.DefaultResponse(
                    new org.springframework.cloud.client.DefaultServiceInstance("instance", "modeling-server", "127.0.0.1", 9443, true)));
        };
        when(factory.getInstance("modeling-server")).thenReturn(selecting);
        when(factory.getProperties("modeling-server")).thenReturn(new org.springframework.cloud.client.loadbalancer.LoadBalancerProperties());
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("balancer", balancer); beans.registerSingleton("factory", factory);
        Client client = new ModelRuleContextClientConfiguration().modelRuleContextClient(new MockEnvironment(),
                beans.getBeanProvider(SslBundles.class), beans.getBeanProvider(LoadBalancerClient.class),
                beans.getBeanProvider(LoadBalancerClientFactory.class), beans.getBeanProvider(LoadBalancerFeignRequestTransformer.class));
        var request = Request.create(Request.HttpMethod.GET, "http://modeling-server" + ModelRuleContextApi.PATH,
                Map.of(), null, StandardCharsets.UTF_8, new RequestTemplate());
        var error = assertThrows(IllegalStateException.class, () -> client.execute(request, new Request.Options()));
        assertTrue(error.getMessage().contains(ModelRuleContextClientConfiguration.SSL_BUNDLE_PROPERTY));
        assertEquals(1, selections.get()); verify(factory).getInstance("modeling-server");
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.cloud.openfeign.EnableFeignClients(clients = {ModelRuleContextApi.class, ModelFieldApi.class})
    static class ClientRegistration { }

    private static Request request(Request.HttpMethod method, String path) {
        return Request.create(method, "https://modeling.test" + path, Map.of(), null, StandardCharsets.UTF_8, new RequestTemplate());
    }
}
