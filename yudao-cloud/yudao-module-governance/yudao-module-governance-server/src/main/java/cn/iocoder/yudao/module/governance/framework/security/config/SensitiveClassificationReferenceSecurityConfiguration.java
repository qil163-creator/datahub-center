package cn.iocoder.yudao.module.governance.framework.security.config;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.datapermission.core.rpc.DataPermissionRequestInterceptor;
import cn.iocoder.yudao.framework.env.core.util.EnvUtils;
import cn.iocoder.yudao.framework.security.core.filter.ExternalIdentityHeadersFilter;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.governance.api.security.SensitiveClassificationReferenceApi;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 分类引用使用真实用户令牌；在建立任何身份、环境和数据权限上下文前清除内部控制头。 */
@Configuration(proxyBeanMethods = false)
public class SensitiveClassificationReferenceSecurityConfiguration {

    /**
     * 仅保护分类引用管理端入口，保留已有服务证书 RPC 的认证方式。
     */
    @Bean
    public FilterRegistrationBean<ExternalIdentityHeadersFilter> sensitiveClassificationReferenceIdentityFilter(
            WebProperties properties) {
        var registration = new FilterRegistrationBean<>(new ExternalIdentityHeadersFilter(List.of(
                WebFrameworkUtils.HEADER_TENANT_ID, WebFrameworkUtils.HEADER_VISIT_TENANT_ID,
                DataPermissionRequestInterceptor.ENABLE_HEADER_NAME, EnvUtils.HEADER_TAG)));
        registration.addUrlPatterns(properties.getAdminApi().getPrefix() + SensitiveClassificationReferenceApi.PREFIX + "/*");
        registration.setOrder(WebFilterOrderEnum.ENV_TAG_FILTER - 1);
        registration.setName("sensitiveClassificationReferenceIdentityFilter");
        return registration;
    }
}
