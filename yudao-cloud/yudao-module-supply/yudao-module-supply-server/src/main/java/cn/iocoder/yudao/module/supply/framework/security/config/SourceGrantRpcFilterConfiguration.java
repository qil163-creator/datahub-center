package cn.iocoder.yudao.module.supply.framework.security.config;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.supply.framework.security.SourceGrantRpcFilter;
import cn.iocoder.yudao.module.supply.framework.security.SourceGrantRpcProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 只对来源级授权 v2 RPC 路径注册服务身份门禁；早于租户上下文过滤器与 Spring Security 过滤链执行。
 */
@Configuration(proxyBeanMethods = false)
public class SourceGrantRpcFilterConfiguration {

    public static final String FILTER_NAME = "sourceGrantRpcFilter";

    /**
     * 注册筛选登记组件来源授权RPC筛选组件。
     *
     * @param properties 组件配置
     * @return 筛选登记组件来源授权RPC筛选
     */
    @Bean
    public FilterRegistrationBean<SourceGrantRpcFilter> sourceGrantRpcFilterRegistration(
            SourceGrantRpcProperties properties) {
        SourceGrantRpcFilter filter = new SourceGrantRpcFilter(new VerifiedServicePeerResolver(), properties::getPeers);
        FilterRegistrationBean<SourceGrantRpcFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(SourceGrantRpcFilter.PROTECTED_PATH + "/*");
        // 先于租户上下文（-104）、请求上下文与 Spring Security（-100）；晚于 CORS/链路追踪。
        registration.setOrder(WebFilterOrderEnum.TENANT_CONTEXT_FILTER - 3);
        registration.setName(FILTER_NAME);
        return registration;
    }

}
