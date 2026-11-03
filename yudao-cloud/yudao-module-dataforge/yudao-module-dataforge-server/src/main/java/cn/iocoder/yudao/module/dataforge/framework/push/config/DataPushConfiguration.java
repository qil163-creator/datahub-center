package cn.iocoder.yudao.module.dataforge.framework.push.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataPushProperties.class)
public class DataPushConfiguration {

}
