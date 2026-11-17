package cn.iocoder.yudao.framework.rpc.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.context.annotation.Bean;

/** 由 OpenFeign 在每个客户端子上下文中加载。 */
class FeignMessageConverterConfiguration {

    @Bean
    SmartInitializingSingleton initializeFeignMessageConverters(FeignHttpMessageConverters converters) {
        // OpenFeign 5.0.2 首次使用时先发布空列表。客户端对外可用前，先完成原生转换器及定制器的初始化。
        return () -> {
            synchronized (converters) {
                converters.getConverters();
            }
        };
    }
}
