package cn.iocoder.yudao.module.dataforge.framework.security.config;

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

/** 浏览器开发入口从已验证的 OAuth 令牌恢复执行人。 */
@Configuration(proxyBeanMethods = false)
public class DevExternalIdentityConfiguration {

    /**
     * 注册筛选登记组件外部身份请求头筛选组件。
     *
     * @param webProperties Web属性
     * @return 筛选登记组件外部身份请求头筛选
     */
    @Bean
    public FilterRegistrationBean<ExternalIdentityHeadersFilter> devExternalIdentityFilter(
            WebProperties webProperties) {
        var registration = new FilterRegistrationBean<>(new ExternalIdentityHeadersFilter(List.of(
                WebFrameworkUtils.HEADER_TENANT_ID, WebFrameworkUtils.HEADER_VISIT_TENANT_ID,
                DataPermissionRequestInterceptor.ENABLE_HEADER_NAME, EnvUtils.HEADER_TAG)));
        String prefix = webProperties.getAdminApi().getPrefix();
        registration.addUrlPatterns(prefix + "/dataforge/dev-task/*", prefix + "/dataforge/dev-dir/*",
                prefix + "/dataforge/dev-record/*");
        registration.setOrder(WebFilterOrderEnum.ENV_TAG_FILTER - 1);
        registration.setName("devExternalIdentityFilter");
        return registration;
    }
}
