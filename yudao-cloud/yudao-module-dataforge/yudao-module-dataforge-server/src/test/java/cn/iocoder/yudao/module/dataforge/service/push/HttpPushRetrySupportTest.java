package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;
import cn.iocoder.yudao.module.dataforge.framework.push.config.DataPushProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpPushRetrySupportTest {

    @Test
    @DisplayName("缺少接收方幂等契约，超时、连接异常及5xx也不重投")
    void retryPolicy() {
        assertFalse(HttpPushRetrySupport.isRetryable(
                new IllegalStateException("推送失败，HTTP 400：bad request")));
        assertFalse(HttpPushRetrySupport.isRetryable(new InterruptedException("stop")));
        assertFalse(HttpPushRetrySupport.isRetryable(new TimeoutException("timeout")));
        assertFalse(HttpPushRetrySupport.isRetryable(new ConnectException("refused")));
        assertFalse(HttpPushRetrySupport.isRetryable(
                new IllegalStateException("推送失败，HTTP 503：unavailable")));
    }

    @Test
    @DisplayName("通道配置优先于服务默认")
    void resolvePrefersChannelConfig() {
        DataPushChannelConfigVO.HttpConfig http = new DataPushChannelConfigVO.HttpConfig();
        http.setTimeoutMs(12_000);
        http.setRetryTimes(1);
        http.setRetryIntervalMs(200);
        DataPushProperties.Http defaults = new DataPushProperties.Http();
        assertEquals(12_000, HttpPushRetrySupport.resolveTimeoutMs(http, defaults));
        assertEquals(1, HttpPushRetrySupport.resolveRetryTimes(http, defaults));
        assertEquals(200, HttpPushRetrySupport.resolveRetryIntervalMs(http, defaults));
        assertEquals(defaults.getTimeoutMs(), HttpPushRetrySupport.resolveTimeoutMs(null, defaults));
    }

}
