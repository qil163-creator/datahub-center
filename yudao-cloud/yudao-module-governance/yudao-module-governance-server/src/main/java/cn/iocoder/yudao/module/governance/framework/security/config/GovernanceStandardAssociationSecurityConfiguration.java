package cn.iocoder.yudao.module.governance.framework.security.config;

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

/** 标准关联检查是浏览器入口，建立上下文前剥离内部身份与控制头并要求真实令牌。 */
@Configuration(proxyBeanMethods = false)
public class GovernanceStandardAssociationSecurityConfiguration {

    /**
     * 创建并配置治理标准关联身份筛选组件。
     */
    @Bean
    public FilterRegistrationBean<ExternalIdentityHeadersFilter> governanceStandardAssociationIdentityFilter(
            WebProperties properties) {
        var registration = new FilterRegistrationBean<>(new ExternalIdentityHeadersFilter(List.of(
                WebFrameworkUtils.HEADER_TENANT_ID, WebFrameworkUtils.HEADER_VISIT_TENANT_ID,
                DataPermissionRequestInterceptor.ENABLE_HEADER_NAME, EnvUtils.HEADER_TAG)));
        registration.addUrlPatterns(properties.getAdminApi().getPrefix() + "/governance/standard-association/*");
        registration.setOrder(WebFilterOrderEnum.ENV_TAG_FILTER - 1);
        registration.setName("governanceStandardAssociationIdentityFilter");
        return registration;
    }
}
