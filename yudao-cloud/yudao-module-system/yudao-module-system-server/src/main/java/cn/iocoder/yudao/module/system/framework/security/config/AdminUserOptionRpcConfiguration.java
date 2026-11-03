package cn.iocoder.yudao.module.system.framework.security.config;
import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.module.system.api.user.AdminUserOptionApi;
import cn.iocoder.yudao.module.system.framework.security.core.AdminUserOptionRpcFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
public class AdminUserOptionRpcConfiguration {
    @Bean public FilterRegistrationBean<AdminUserOptionRpcFilter> adminUserOptionRpcFilter(AdminUserOptionRpcProperties properties){
        var registration=new FilterRegistrationBean<>(new AdminUserOptionRpcFilter(properties::getPeers));
        registration.addUrlPatterns(AdminUserOptionApi.PREFIX+"/page");
        registration.setOrder(WebFilterOrderEnum.TENANT_CONTEXT_FILTER-3);
        registration.setName("adminUserOptionRpcFilter");return registration;
    }
}
