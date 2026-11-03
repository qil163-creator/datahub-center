package cn.iocoder.yudao.module.supply.framework.security;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.module.supply.framework.security.config.SourceGrantRpcFilterConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 门禁注册必须早于租户上下文与 Spring Security，只覆盖 v2 路径，并在配置缺失/歧义时失败关闭。 */
class SourceGrantRpcFilterConfigurationTest {

    private static final String PIN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void registrationRunsBeforeTenantAndSecurityFiltersOnlyForTheProtectedPath() {
        SourceGrantRpcProperties properties = new SourceGrantRpcProperties();
        properties.setPeers(Map.of("asset", Set.of(PIN)));
        properties.validate();
        var registration = new SourceGrantRpcFilterConfiguration().sourceGrantRpcFilterRegistration(properties);

        assertThat(registration.getOrder()).isLessThan(WebFilterOrderEnum.TENANT_CONTEXT_FILTER);
        assertThat(registration.getOrder()).isLessThan(-100); // Spring Security 过滤链默认顺序
        assertThat(registration.getUrlPatterns()).containsExactly("/rpc-api/supply/grant-source/v2/*");
        assertThat(registration.getFilter()).isInstanceOf(SourceGrantRpcFilter.class);
    }

    @Test
    void emptyConfigurationKeepsTheGateShut() throws Exception {
        SourceGrantRpcProperties properties = new SourceGrantRpcProperties();
        properties.setPeers(null);
        properties.validate();
        var filter = new SourceGrantRpcFilterConfiguration().sourceGrantRpcFilterRegistration(properties).getFilter();
        var request = new MockHttpServletRequest("GET", "/rpc-api/supply/grant-source/v2/operation");
        request.setSecure(true);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void ambiguousOrMalformedConfigurationFailsStartup() {
        SourceGrantRpcProperties ambiguous = new SourceGrantRpcProperties();
        ambiguous.setPeers(Map.of("asset", Set.of(PIN), "governance", Set.of(PIN)));
        assertThatThrownBy(ambiguous::validate).isInstanceOf(IllegalArgumentException.class);
        SourceGrantRpcProperties malformed = new SourceGrantRpcProperties();
        malformed.setPeers(Map.of("asset", Set.of("sha256:" + PIN)));
        assertThatThrownBy(malformed::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
