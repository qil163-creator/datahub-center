package cn.iocoder.yudao.module.modeling.framework.security.config;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.module.modeling.api.model.ModelRuleContextApi;
import cn.iocoder.yudao.module.modeling.framework.security.ModelRuleContextRpcFilter;
import cn.iocoder.yudao.module.modeling.framework.security.ModelRuleContextRpcProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ModelRuleContextRpcConfiguration {

    /**
     * 注册筛选登记组件模型规则上下文RPC筛选组件。
     *
     * @param properties 组件配置
     * @return 筛选登记组件模型规则上下文RPC筛选
     */
    @Bean
    public FilterRegistrationBean<ModelRuleContextRpcFilter> modelRuleContextRpcFilter(
            ModelRuleContextRpcProperties properties) {
        var registration = new FilterRegistrationBean<>(new ModelRuleContextRpcFilter(properties::getPeers));
        registration.addUrlPatterns(ModelRuleContextApi.PATH);
        registration.setOrder(WebFilterOrderEnum.ENV_TAG_FILTER - 1);
        registration.setName("modelRuleContextRpcFilter");
        return registration;
    }
}
