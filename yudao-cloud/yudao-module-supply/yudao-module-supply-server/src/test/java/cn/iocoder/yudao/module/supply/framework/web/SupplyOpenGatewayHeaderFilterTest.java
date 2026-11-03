package cn.iocoder.yudao.module.supply.framework.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SupplyOpenGatewayHeaderFilterTest {

    @Test
    void stripsInternalIdentityHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("login-user", "{\"id\":1}");
        request.addHeader("tenant-id", "9");
        request.addHeader("visit-tenant-id", "9");
        request.addHeader("data-permission-enable", "false");
        request.addHeader("Authorization", "Bearer x");
        request.addHeader("X-AppKey", "app-1");
        SupplyOpenGatewayHeaderFilter.StrippingRequest wrapped =
                new SupplyOpenGatewayHeaderFilter.StrippingRequest(request);

        assertThat(wrapped.getHeader("login-user")).isNull();
        assertThat(wrapped.getHeader("tenant-id")).isNull();
        assertThat(wrapped.getHeader("Authorization")).isNull();
        assertThat(wrapped.getHeader("X-AppKey")).isEqualTo("app-1");
    }
}
