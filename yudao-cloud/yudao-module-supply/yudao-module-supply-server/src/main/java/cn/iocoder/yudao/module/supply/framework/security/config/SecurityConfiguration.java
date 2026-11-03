package cn.iocoder.yudao.module.supply.framework.security.config;

import cn.iocoder.yudao.framework.security.config.AuthorizeRequestsCustomizer;
import cn.iocoder.yudao.module.infra.enums.ApiConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * 数据服务 模块的 Security 配置
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /**
     * 注册授权请求定制器组件。
     *
     * @return 授权请求定制器
     */
    @Bean
    public AuthorizeRequestsCustomizer authorizeRequestsCustomizer() {
        return new AuthorizeRequestsCustomizer() {

            /**
             * 定制安全。
             *
             * @param registry 注册表
             */
            @Override
            public void customize(
                    AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
                // Swagger 接口文档
                registry.requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/webjars/**").permitAll()
                        .requestMatchers("/swagger-ui").permitAll()
                        .requestMatchers("/swagger-ui/**").permitAll();
                // Druid 监控
                registry.requestMatchers("/druid/**").permitAll();
                // Spring Boot Actuator 的安全配置
                registry.requestMatchers("/actuator").permitAll()
                        .requestMatchers("/actuator/**").permitAll();
                // RPC 服务的安全配置
                registry.requestMatchers(ApiConstants.PREFIX + "/**").permitAll();
                // 数据服务对外网关（应用凭证认证在网关内部完成）
                registry.requestMatchers("/supply/gateway/**").permitAll();
            }

        };
    }

}
