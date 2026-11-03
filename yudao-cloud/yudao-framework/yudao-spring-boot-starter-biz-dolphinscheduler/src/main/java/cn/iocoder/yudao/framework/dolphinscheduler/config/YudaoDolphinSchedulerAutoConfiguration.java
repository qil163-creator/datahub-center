package cn.iocoder.yudao.framework.dolphinscheduler.config;

import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * DolphinScheduler REST 自动配置。
 */
@AutoConfiguration
@EnableConfigurationProperties(DolphinSchedulerProperties.class)
public class YudaoDolphinSchedulerAutoConfiguration {

    /**
     * 根据当前输入构建并返回 {@code DolphinSchedulerClient}。
     */
    @Bean
    @ConditionalOnMissingBean
    public DolphinSchedulerClient dolphinSchedulerClient(DolphinSchedulerProperties properties) {
        return new DolphinSchedulerClient(properties);
    }

}
