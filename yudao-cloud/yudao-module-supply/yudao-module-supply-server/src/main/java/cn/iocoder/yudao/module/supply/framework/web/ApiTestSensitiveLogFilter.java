package cn.iocoder.yudao.module.supply.framework.web;

import cn.iocoder.yudao.framework.apilog.core.util.ApiLogUtils;
import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.common.util.http.HttpUtils;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 入口先标记敏感路径，认证拒绝和无匹配方法时也不记录任意 HTTP 测试凭据。 */
@Configuration(proxyBeanMethods = false)
public class ApiTestSensitiveLogFilter {

    /**
     * 注册筛选登记组件一次每项请求筛选组件。
     *
     * @param properties 组件配置
     * @return 筛选登记组件一次每项请求筛选
     */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> apiTestSensitiveLogFilterRegistration(
            WebProperties properties) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            /**
             * 按当前过滤器规则处理 HTTP 请求并传递过滤器链。
             *
             * @param request 请求参数
             * @param response 响应对象
             * @param chain 处理链
             */
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                try {
                    path = HttpUtils.decodeUrlPath(path).replaceAll(";[^/]*", "");
                } catch (IllegalArgumentException ignored) {
                    // 无法解析的路径不会映射到业务入口。
                }
                String admin = properties.getAdminApi().getPrefix().replaceAll("/+$", "") + "/supply/api-test";
                if (path.equals(admin) || path.startsWith(admin + "/")
                        || path.equals("/supply/gateway") || path.startsWith("/supply/gateway/")) {
                    ApiLogUtils.disableRequestLogging(request);
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(WebFilterOrderEnum.TENANT_CONTEXT_FILTER - 3);
        registration.setName("apiTestSensitiveLogFilter");
        return registration;
    }
}
