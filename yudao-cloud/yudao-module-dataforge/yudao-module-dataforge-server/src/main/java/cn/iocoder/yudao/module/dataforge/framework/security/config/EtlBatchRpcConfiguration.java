package cn.iocoder.yudao.module.dataforge.framework.security.config;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.module.dataforge.api.etl.EtlTargetBatchApi;
import cn.iocoder.yudao.module.dataforge.framework.security.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class EtlBatchRpcConfiguration {

    /**
     * 注册筛选登记组件ETL批次RPC筛选组件。
     *
     * @param properties 组件配置
     * @return 筛选登记组件ETL批次RPC筛选
     */
    @Bean
    public FilterRegistrationBean<EtlBatchRpcFilter> etlBatchRpcFilter(EtlBatchRpcProperties properties) {
        var registration = new FilterRegistrationBean<>(new EtlBatchRpcFilter(properties::getPeers));
        registration.addUrlPatterns(EtlTargetBatchApi.PREFIX + "/*");
        registration.setOrder(WebFilterOrderEnum.TENANT_CONTEXT_FILTER - 3);
        registration.setName("etlBatchRpcFilter");
        return registration;
    }
}
