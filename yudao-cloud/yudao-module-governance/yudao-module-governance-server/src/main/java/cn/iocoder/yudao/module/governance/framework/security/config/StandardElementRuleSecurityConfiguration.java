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

/** 新关联规则入口须验证真实令牌，防止客户端伪造身份后经受信 RPC 传播。 */
@Configuration(proxyBeanMethods = false)
public class StandardElementRuleSecurityConfiguration {

    /**
     * 创建并配置标准数据元规则身份筛选组件。
     */
    @Bean
    public FilterRegistrationBean<ExternalIdentityHeadersFilter> standardElementRuleIdentityFilter(
            WebProperties properties) {
        var registration = new FilterRegistrationBean<>(new ExternalIdentityHeadersFilter(List.of(
                WebFrameworkUtils.HEADER_TENANT_ID, WebFrameworkUtils.HEADER_VISIT_TENANT_ID,
                DataPermissionRequestInterceptor.ENABLE_HEADER_NAME, EnvUtils.HEADER_TAG)));
        for (String path : List.of("rule-page", "rule-get", "rule-create", "rule-update", "rule-delete",
                "rule-templates", "rule-context", "rule-sample")) {
            registration.addUrlPatterns(properties.getAdminApi().getPrefix() + "/governance/standard-element/" + path);
        }
        registration.setOrder(WebFilterOrderEnum.ENV_TAG_FILTER - 1);
        registration.setName("standardElementRuleIdentityFilter");
        return registration;
    }
}
