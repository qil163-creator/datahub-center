package cn.iocoder.yudao.module.supply.framework.web;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.datapermission.core.rpc.DataPermissionRequestInterceptor;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

/**
 * 对外网关在建立租户/登录上下文之前剥离内部控制头，应用身份由网关验证后建立。
 */
@Configuration(proxyBeanMethods = false)
public class SupplyOpenGatewayHeaderFilter {

    private static final Set<String> STRIPPED = Set.of(
            SecurityFrameworkUtils.LOGIN_USER_HEADER.toLowerCase(Locale.ROOT),
            WebFrameworkUtils.HEADER_TENANT_ID.toLowerCase(Locale.ROOT),
            WebFrameworkUtils.HEADER_VISIT_TENANT_ID.toLowerCase(Locale.ROOT),
            DataPermissionRequestInterceptor.ENABLE_HEADER_NAME.toLowerCase(Locale.ROOT),
            "authorization");

    /**
     * 注册筛选登记组件一次每项请求筛选组件。
     *
     * @return 筛选登记组件一次每项请求筛选
     */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> supplyOpenGatewayHeaderFilterRegistration() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            /**
             * 按当前过滤器规则处理 HTTP 请求并传递过滤器链。
             *
             * @param request 请求参数
             * @param response 响应对象
             * @param filterChain HTTP 过滤器链
             */
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain filterChain) throws ServletException, IOException {
                filterChain.doFilter(new StrippingRequest(request), response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/supply/gateway/*");
        registration.setOrder(WebFilterOrderEnum.TENANT_CONTEXT_FILTER - 2);
        registration.setName("supplyOpenGatewayHeaderFilter");
        return registration;
    }

    static final class StrippingRequest extends HttpServletRequestWrapper {

        /**
         * 创建剥离请求实例。
         *
         * @param request 请求参数
         */
        StrippingRequest(HttpServletRequest request) {
            super(request);
        }

        /**
         * 读取请求头。
         *
         * @param name 名称
         * @return 请求头文本
         */
        @Override
        public String getHeader(String name) {
            return stripped(name) ? null : super.getHeader(name);
        }

        /**
         * 读取请求头。
         *
         * @param name 名称
         * @return 枚举字符串
         */
        @Override
        public Enumeration<String> getHeaders(String name) {
            return stripped(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        /**
         * 读取请求头名称。
         *
         * @return 请求头名称
         */
        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                    .filter(name -> !stripped(name))
                    .toList());
        }

        /**
         * 判断已剥离条件是否成立。
         *
         * @param name 名称
         * @return 处理是否成功
         */
        private boolean stripped(String name) {
            return name != null && STRIPPED.contains(name.toLowerCase(Locale.ROOT));
        }
    }
}
