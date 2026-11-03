package cn.iocoder.yudao.module.dataforge.framework.rpc;

import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.enums.ApiConstants;
import feign.Request;
import feign.Retryer;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/** 继承提供方接口，仅隔离此告警消费者的超时与重试配置，不复制RPC协议。 */
@FeignClient(name = ApiConstants.NAME, contextId = "dataforge-push-alert", primary = false,
        configuration = PushAlertNotifyClient.Configuration.class)
public interface PushAlertNotifyClient extends NotifyMessageSendApi {

    class Configuration {

        /**
         * 注册选项组件。
         *
         * @return 选项
         */
        @Bean
        public Request.Options pushAlertOptions() {
            return new Request.Options(5, TimeUnit.SECONDS, 10, TimeUnit.SECONDS, false);
        }

        /**
         * 注册重试器组件。
         *
         * @return 重试器
         */
        @Bean
        public Retryer pushAlertRetryer() {
            return Retryer.NEVER_RETRY;
        }
    }
}
