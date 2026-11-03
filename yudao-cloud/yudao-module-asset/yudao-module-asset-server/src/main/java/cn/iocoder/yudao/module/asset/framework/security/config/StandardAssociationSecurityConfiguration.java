package cn.iocoder.yudao.module.asset.framework.security.config;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.datapermission.core.rpc.DataPermissionRequestInterceptor;
import cn.iocoder.yudao.framework.security.core.filter.ExternalIdentityHeadersFilter;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 标准关联列表是浏览器入口，直连服务同样不接受内部身份与数据范围头。 */
@Configuration(proxyBeanMethods = false)
public class StandardAssociationSecurityConfiguration {

    /**
     * 创建并配置标准关联身份筛选组件。
     */
    @Bean
    public FilterRegistrationBean<ExternalIdentityHeadersFilter> standardAssociationIdentityFilter(
            WebProperties properties) {
        var registration = new FilterRegistrationBean<>(new ExternalIdentityHeadersFilter(List.of(
                WebFrameworkUtils.HEADER_TENANT_ID, WebFrameworkUtils.HEADER_VISIT_TENANT_ID,
                DataPermissionRequestInterceptor.ENABLE_HEADER_NAME)));
        registration.addUrlPatterns(properties.getAdminApi().getPrefix() + "/asset/standard-association/*");
        registration.setOrder(WebFilterOrderEnum.TENANT_CONTEXT_FILTER - 3);
        registration.setName("standardAssociationIdentityFilter");
        return registration;
    }
}
