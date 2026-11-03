package cn.iocoder.yudao.module.governance.framework.security.config;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.module.governance.api.standardterm.StandardTermApi;
import cn.iocoder.yudao.module.governance.api.security.SafetyClassificationApi;
import cn.iocoder.yudao.module.governance.framework.security.StandardTermRpcFilter;
import cn.iocoder.yudao.module.governance.framework.security.StandardTermRpcProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class StandardTermRpcConfiguration {

    /**
     * 创建并配置标准术语RPC筛选组件。
     */
    @Bean
    public FilterRegistrationBean<StandardTermRpcFilter> standardTermRpcFilter(StandardTermRpcProperties properties) {
        var registration = new FilterRegistrationBean<>(new StandardTermRpcFilter(properties::getPeers));
        registration.addUrlPatterns(StandardTermApi.PREFIX + "/enabled-list", StandardTermApi.PREFIX + "/list",
                SafetyClassificationApi.PREFIX + "/list", SafetyClassificationApi.PREFIX + "/get");
        registration.setOrder(WebFilterOrderEnum.TENANT_CONTEXT_FILTER - 3);
        registration.setName("standardTermRpcFilter");
        return registration;
    }

}
