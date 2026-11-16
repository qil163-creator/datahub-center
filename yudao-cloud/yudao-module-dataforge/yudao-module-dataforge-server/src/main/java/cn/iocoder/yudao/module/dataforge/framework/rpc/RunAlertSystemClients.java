package cn.iocoder.yudao.module.dataforge.framework.rpc;

import cn.iocoder.yudao.module.system.api.mail.MailSendApi;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.sms.SmsSendApi;
import cn.iocoder.yudao.module.system.enums.ApiConstants;
import feign.Request;
import feign.Retryer;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * 任务运行失败通知专用的 system 发送客户端。
 *
 * <p>继承提供方接口，只隔离超时与重试：Feign 层不重试，是否重发由通知记录的状态机决定（短信、邮件没有去重契约，
 * 底层自动重试会造成重复发送）。</p>
 */
public final class RunAlertSystemClients {

    private RunAlertSystemClients() {
    }

    /** 站内信 */
    @FeignClient(name = ApiConstants.NAME, contextId = "dataforge-run-alert-notify", primary = false,
            configuration = Configuration.class)
    public interface NotifyClient extends NotifyMessageSendApi {
    }

    /** 短信 */
    @FeignClient(name = ApiConstants.NAME, contextId = "dataforge-run-alert-sms", primary = false,
            configuration = Configuration.class)
    public interface SmsClient extends SmsSendApi {
    }

    /** 邮件 */
    @FeignClient(name = ApiConstants.NAME, contextId = "dataforge-run-alert-mail", primary = false,
            configuration = Configuration.class)
    public interface MailClient extends MailSendApi {
    }

    /** 每个客户端的独立 Feign 配置：连接 5 秒、读取 10 秒、不重试 */
    public static class Configuration {

        /**
         * 失败通知发送超时。
         *
         * @return Feign 请求选项
         */
        @Bean
        public Request.Options runAlertOptions() {
            return new Request.Options(5, TimeUnit.SECONDS, 10, TimeUnit.SECONDS, false);
        }

        /**
         * 失败通知不在 Feign 层重试。
         *
         * @return 重试策略
         */
        @Bean
        public Retryer runAlertRetryer() {
            return Retryer.NEVER_RETRY;
        }
    }

}
