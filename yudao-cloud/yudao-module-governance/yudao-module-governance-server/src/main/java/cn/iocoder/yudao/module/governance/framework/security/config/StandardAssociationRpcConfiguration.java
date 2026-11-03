package cn.iocoder.yudao.module.governance.framework.security.config;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.module.governance.api.standard.StandardAssociationApi;
import cn.iocoder.yudao.module.governance.framework.security.StandardTermRpcFilter;
import cn.iocoder.yudao.module.governance.framework.security.StandardTermRpcProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 复用现有资产服务 TLS 身份与轮换配置，不新增宽松 RPC 通道。 */
@Configuration(proxyBeanMethods = false)
public class StandardAssociationRpcConfiguration {

    /**
     * 创建并配置标准关联RPC筛选组件。
     */
    @Bean
    public FilterRegistrationBean<StandardTermRpcFilter> standardAssociationRpcFilter(
            StandardTermRpcProperties properties) {
        var registration = new FilterRegistrationBean<>(new StandardTermRpcFilter(properties::getPeers));
        registration.addUrlPatterns(StandardAssociationApi.PREFIX + "/*");
        registration.setOrder(WebFilterOrderEnum.TENANT_CONTEXT_FILTER - 3);
        registration.setName("standardAssociationRpcFilter");
        return registration;
    }
}
