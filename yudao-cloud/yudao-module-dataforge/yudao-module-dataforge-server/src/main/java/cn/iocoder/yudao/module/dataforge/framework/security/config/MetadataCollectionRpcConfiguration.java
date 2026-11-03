package cn.iocoder.yudao.module.dataforge.framework.security.config;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.module.dataforge.api.metadata.MetadataCollectionApi;
import cn.iocoder.yudao.module.dataforge.framework.security.MetadataCollectionRpcFilter;
import cn.iocoder.yudao.module.dataforge.framework.security.MetadataCollectionRpcProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 门禁仅覆盖元数据采集 RPC，不修改其他业务入口。 */
@Configuration(proxyBeanMethods = false)
public class MetadataCollectionRpcConfiguration {

    /**
     * 注册筛选登记组件元数据集合RPC筛选组件。
     *
     * @param properties 组件配置
     * @return 筛选登记组件元数据集合RPC筛选
     */
    @Bean
    public FilterRegistrationBean<MetadataCollectionRpcFilter> metadataCollectionRpcFilter(
            MetadataCollectionRpcProperties properties) {
        var registration = new FilterRegistrationBean<>(new MetadataCollectionRpcFilter(properties::getPeers));
        registration.addUrlPatterns(MetadataCollectionApi.PREFIX + "/*");
        registration.setOrder(WebFilterOrderEnum.TENANT_CONTEXT_FILTER - 3);
        registration.setName("metadataCollectionRpcFilter");
        return registration;
    }
}
