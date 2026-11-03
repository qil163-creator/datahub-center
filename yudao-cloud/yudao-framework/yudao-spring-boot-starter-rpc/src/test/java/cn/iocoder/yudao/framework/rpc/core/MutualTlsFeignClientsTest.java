package cn.iocoder.yudao.framework.rpc.core;

import feign.Client;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MutualTlsFeignClientsTest {
    private Request request(String scheme) {
        return Request.create(Request.HttpMethod.GET, scheme + "://localhost:1/test", Map.of(), null, StandardCharsets.UTF_8);
    }
    @Test void missingBundleFailsClosedButContextStarts() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(Client.class, () -> MutualTlsFeignClients.fromBundle(null, null, "test.ssl-bundle"));
            context.refresh();
            var error = assertThrows(IllegalStateException.class,
                    () -> context.getBean(Client.class).execute(request("https"), new Request.Options()));
            assertTrue(error.getMessage().contains("test.ssl-bundle"));
        }
    }
    @Test void unknownBundleFailsClosed() {
        var client = MutualTlsFeignClients.fromBundle("absent", new DefaultSslBundleRegistry(), "test.ssl-bundle");
        assertThrows(IllegalStateException.class, () -> client.execute(request("https"), new Request.Options()));
    }
    @Test void httpNeverConnects() throws Exception {
        var bundle = mock(SslBundle.class);
        when(bundle.createSslContext()).thenReturn(SSLContext.getDefault());
        var client = MutualTlsFeignClients.fromBundle(bundle);
        assertThrows(IllegalStateException.class, () -> client.execute(request("http"), new Request.Options()));
    }
    @Test void loadBalancerContainsHttpsGuardInsteadOfReplacingIt() {
        var delegate = mock(Client.class);
        var client = MutualTlsFeignClients.loadBalanced(delegate, mock(LoadBalancerClient.class),
                mock(LoadBalancerClientFactory.class), List.of());
        assertSame(delegate, ((FeignBlockingLoadBalancerClient) client).getDelegate());
        assertSame(delegate, MutualTlsFeignClients.loadBalanced(delegate, null, null, List.of()));
    }
}
