package cn.iocoder.yudao.module.asset.framework.security.config;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.module.asset.api.sensitive.AssetSensitivePolicyApi;
import cn.iocoder.yudao.module.asset.framework.security.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssetSensitivePolicyRpcProperties.class)
public class AssetSensitivePolicyRpcConfiguration {

    /**
     * 创建并配置资产敏感策略RPC筛选组件。
     */
    @Bean
    public FilterRegistrationBean<AssetSensitivePolicyRpcFilter> assetSensitivePolicyRpcFilter(
            AssetSensitivePolicyRpcProperties properties) {
        var registration = new FilterRegistrationBean<>(new AssetSensitivePolicyRpcFilter(properties::getPeers));
        registration.addUrlPatterns(AssetSensitivePolicyApi.PREFIX + "/rule-page");
        registration.setOrder(WebFilterOrderEnum.TENANT_CONTEXT_FILTER - 3);
        return registration;
    }
}
