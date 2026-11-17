package cn.iocoder.yudao.framework.rpc.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;

@AutoConfiguration(before = FeignAutoConfiguration.class)
@EnableFeignClients(basePackageClasses = YudaoFeignAutoConfiguration.class,
        defaultConfiguration = FeignMessageConverterConfiguration.class)
public class YudaoFeignAutoConfiguration {
}
