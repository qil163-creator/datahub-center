package cn.iocoder.yudao.framework.seatunnel.config;

import cn.iocoder.yudao.framework.seatunnel.BusinessInitializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * SeaTunnel REST 自动配置。
 */
@AutoConfiguration
@EnableConfigurationProperties(SeatunnelProperties.class)
public class YudaoSeatunnelAutoConfiguration {

    /**
     * 根据当前输入构建并返回 {@code BusinessInitializer}。
     */
    @Bean("seatunnelRestInit")
    @ConditionalOnMissingBean(BusinessInitializer.class)
    public BusinessInitializer seatunnelRestInitializer(SeatunnelProperties properties) {
        return new BusinessInitializer(properties);
    }
}
