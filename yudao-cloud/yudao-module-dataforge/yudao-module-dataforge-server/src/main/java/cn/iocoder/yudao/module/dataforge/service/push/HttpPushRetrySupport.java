package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;
import cn.iocoder.yudao.module.dataforge.framework.push.config.DataPushProperties;


/**
 * HTTP 超时配置与兼容重试参数。未具备接收方幂等契约，发送异常一律不自动重投。
 *
 * @author Li
 */
public final class HttpPushRetrySupport {

    private static final int DEFAULT_TIMEOUT_MS = 30_000;

    private static final int DEFAULT_RETRY_TIMES = 2;

    private static final int DEFAULT_RETRY_INTERVAL_MS = 1_000;

    private static final int MAX_TIMEOUT_MS = 300_000;

    private static final int MAX_RETRY_TIMES = 10;

    private static final int MAX_RETRY_INTERVAL_MS = 60_000;

    /**
     * 限制HTTP推送重试支持工具的实例化，统一通过静态能力使用。
     */
    private HttpPushRetrySupport() {
    }

    /**
     * 解析超时毫秒。
     *
     * @param http HTTP
     * @param defaults 默认值
     * @return 解析超时毫秒数值
     */
    public static int resolveTimeoutMs(DataPushChannelConfigVO.HttpConfig http,
            DataPushProperties.Http defaults) {
        Integer configured = http == null ? null : http.getTimeoutMs();
        if (configured != null && configured > 0) {
            return Math.min(configured, MAX_TIMEOUT_MS);
        }
        return defaults == null ? DEFAULT_TIMEOUT_MS : Math.max(1, defaults.getTimeoutMs());
    }

    /**
     * 解析重试时间。
     *
     * @param http HTTP
     * @param defaults 默认值
     * @return 解析重试时间数值
     */
    public static int resolveRetryTimes(DataPushChannelConfigVO.HttpConfig http,
            DataPushProperties.Http defaults) {
        Integer configured = http == null ? null : http.getRetryTimes();
        if (configured != null && configured >= 0) {
            return Math.min(configured, MAX_RETRY_TIMES);
        }
        return defaults == null ? DEFAULT_RETRY_TIMES : Math.max(0, defaults.getRetryTimes());
    }

    /**
     * 解析重试间隔毫秒。
     *
     * @param http HTTP
     * @param defaults 默认值
     * @return 解析重试间隔毫秒数值
     */
    public static int resolveRetryIntervalMs(DataPushChannelConfigVO.HttpConfig http,
            DataPushProperties.Http defaults) {
        Integer configured = http == null ? null : http.getRetryIntervalMs();
        if (configured != null && configured >= 0) {
            return Math.min(configured, MAX_RETRY_INTERVAL_MS);
        }
        return defaults == null ? DEFAULT_RETRY_INTERVAL_MS : Math.max(0, defaults.getRetryIntervalMs());
    }

    /**
     * 判断是否满足可重试条件。
     *
     * @param ex 异常信息
     * @return 判断结果
     */
    public static boolean isRetryable(Throwable ex) {
        // 超时/5xx/连接异常都不能证明业务没有处理；不能按异常文字推断可重试。
        return false;
    }

}
