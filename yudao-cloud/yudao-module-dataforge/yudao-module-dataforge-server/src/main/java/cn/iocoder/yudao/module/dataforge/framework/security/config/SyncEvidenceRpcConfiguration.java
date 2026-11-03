package cn.iocoder.yudao.module.dataforge.framework.security.config;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.module.dataforge.api.migration.SyncBatchEvidenceApi;
import cn.iocoder.yudao.module.dataforge.framework.security.SyncEvidenceRpcFilter;
import cn.iocoder.yudao.module.dataforge.framework.security.SyncEvidenceRpcProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SyncEvidenceRpcConfiguration {

    /**
     * 注册筛选登记组件同步证据RPC筛选组件。
     *
     * @param properties 组件配置
     * @return 筛选登记组件同步证据RPC筛选
     */
    @Bean
    public FilterRegistrationBean<SyncEvidenceRpcFilter> syncEvidenceRpcFilter(SyncEvidenceRpcProperties properties) {
        var registration = new FilterRegistrationBean<>(new SyncEvidenceRpcFilter(properties::getPeers));
        registration.addUrlPatterns(SyncBatchEvidenceApi.PREFIX + "/*");
        registration.setOrder(WebFilterOrderEnum.TENANT_CONTEXT_FILTER - 3);
        registration.setName("syncEvidenceRpcFilter");
        return registration;
    }
}

