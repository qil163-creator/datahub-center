package cn.iocoder.yudao.module.asset.framework.security.config;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.datapermission.core.rpc.DataPermissionRequestInterceptor;
import cn.iocoder.yudao.framework.env.core.util.EnvUtils;
import cn.iocoder.yudao.framework.security.core.filter.ExternalIdentityHeadersFilter;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 敏感清单使用当前访问令牌跨服务读取分类，入口身份必须来自同一真实令牌。 */
@Configuration(proxyBeanMethods = false)
public class SensitiveListSecurityConfiguration {

    @Bean
    public FilterRegistrationBean<ExternalIdentityHeadersFilter> sensitiveListIdentityFilter(WebProperties properties) {
        var registration = new FilterRegistrationBean<>(new ExternalIdentityHeadersFilter(List.of(
                WebFrameworkUtils.HEADER_TENANT_ID, WebFrameworkUtils.HEADER_VISIT_TENANT_ID,
                DataPermissionRequestInterceptor.ENABLE_HEADER_NAME, EnvUtils.HEADER_TAG)));
        registration.addUrlPatterns(properties.getAdminApi().getPrefix() + "/asset/sensitive-list/*");
        registration.setOrder(WebFilterOrderEnum.ENV_TAG_FILTER - 1);
        registration.setName("sensitiveListIdentityFilter");
        return registration;
    }
}
