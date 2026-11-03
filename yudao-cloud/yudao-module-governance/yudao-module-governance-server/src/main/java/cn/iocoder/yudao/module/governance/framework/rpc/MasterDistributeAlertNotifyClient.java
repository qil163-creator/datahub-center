package cn.iocoder.yudao.module.governance.framework.rpc;

import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.enums.ApiConstants;
import feign.Request;
import feign.Retryer;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/** 继承提供方接口，仅隔离此告警消费者的超时与重试配置，不复制RPC协议。 */
@FeignClient(name = ApiConstants.NAME, contextId = "governance-master-distribute-alert", primary = false,
        configuration = MasterDistributeAlertNotifyClient.Configuration.class)
public interface MasterDistributeAlertNotifyClient extends NotifyMessageSendApi {

    class Configuration {

        /**
         * 创建并配置主数据分发告警选项组件。
         */
        @Bean
        public Request.Options masterDistributeAlertOptions() {
            return new Request.Options(5, TimeUnit.SECONDS, 10, TimeUnit.SECONDS, false);
        }

        /**
         * 创建并配置主数据分发告警重试策略组件。
         */
        @Bean
        public Retryer masterDistributeAlertRetryer() {
            return Retryer.NEVER_RETRY;
        }
    }
}
