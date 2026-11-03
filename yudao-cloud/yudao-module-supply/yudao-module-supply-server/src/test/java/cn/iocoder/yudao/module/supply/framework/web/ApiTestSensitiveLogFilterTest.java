package cn.iocoder.yudao.module.supply.framework.web;

import cn.iocoder.yudao.framework.apilog.core.util.ApiLogUtils;
import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTestSensitiveLogFilterTest {
    @ParameterizedTest
    @ValueSource(strings = {"/supply-api/supply/api-test/send", "/supply-api/supply/api-test/detail",
            "/supply-api/supply/api-%74est/send", "/supply-api/supply/api-test;matrix=x/send",
            "/supply/gateway/services/v1/orders"})
    void marksSensitiveRoutesBeforeAuthenticationWithoutAHandler(String path) throws Exception {
        WebProperties properties = new WebProperties();
        properties.getAdminApi().setPrefix("/supply-api");
        var registration = new ApiTestSensitiveLogFilter().apiTestSensitiveLogFilterRegistration(properties);
        assertThat(registration.getOrder()).isLessThan(WebFilterOrderEnum.API_ACCESS_LOG_FILTER);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/context" + path);
        request.setContextPath("/context");
        registration.getFilter().doFilter(request, new MockHttpServletResponse(), (forwarded, response) ->
                assertThat(ApiLogUtils.isRequestEnabled((jakarta.servlet.http.HttpServletRequest) forwarded)).isFalse());
    }

    @Test
    void ordinaryRouteKeepsExistingLogging() throws Exception {
        var registration = new ApiTestSensitiveLogFilter().apiTestSensitiveLogFilterRegistration(new WebProperties());
        registration.getFilter().doFilter(new MockHttpServletRequest("POST", "/admin-api/supply/data-api/invoke"),
                new MockHttpServletResponse(), (forwarded, response) ->
                        assertThat(ApiLogUtils.isRequestEnabled((jakarta.servlet.http.HttpServletRequest) forwarded)).isTrue());
    }
}
