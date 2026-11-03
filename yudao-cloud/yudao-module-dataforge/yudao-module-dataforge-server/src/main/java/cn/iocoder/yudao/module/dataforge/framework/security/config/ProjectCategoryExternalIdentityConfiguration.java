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

/** 类目管理入口从真实令牌恢复登录人，剥离客户端伪造的内部控制头。 */
@Configuration(proxyBeanMethods = false)
public class ProjectCategoryExternalIdentityConfiguration {

    /**
     * 注册筛选登记组件外部身份请求头筛选组件。
     *
     * @param properties 组件配置
     * @return 筛选登记组件外部身份请求头筛选
     */
    @Bean
    public FilterRegistrationBean<ExternalIdentityHeadersFilter> projectCategoryExternalIdentityFilter(
            WebProperties properties) {
        var registration = new FilterRegistrationBean<>(new ExternalIdentityHeadersFilter(List.of(
                WebFrameworkUtils.HEADER_TENANT_ID, WebFrameworkUtils.HEADER_VISIT_TENANT_ID,
                DataPermissionRequestInterceptor.ENABLE_HEADER_NAME, EnvUtils.HEADER_TAG)));
        registration.addUrlPatterns(properties.getAdminApi().getPrefix() + "/dataforge/project-category/*");
        registration.setOrder(WebFilterOrderEnum.ENV_TAG_FILTER - 1);
        registration.setName("projectCategoryExternalIdentityFilter");
        return registration;
    }
}
