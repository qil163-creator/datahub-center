package cn.iocoder.yudao.module.dataforge.service.runalert;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 任务运行失败通知配置（Nacos：dataforge.run-alert.*）。
 *
 * <p>站内信总是发送；短信、邮件只有列入 {@link #extraChannels} 且管理员启用了对应模板后才会真正发出。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "dataforge.run-alert")
public class RunAlertProperties {

    /** 附加渠道取值：短信 */
    public static final String CHANNEL_SMS = "SMS";
    /** 附加渠道取值：邮件 */
    public static final String CHANNEL_MAIL = "MAIL";

    /** 是否启用失败通知；关闭后调度任务直接返回，不扫描、不发送 */
    private boolean enabled = true;

    /** 站内信之外的附加渠道：SMS、MAIL；默认不开启 */
    private Set<String> extraChannels = Set.of();

    /** 只扫描失败时间在最近多少小时内的运行，1～168；首次启用时更早的失败不会补发 */
    private int lookbackHours = 24;

    /** 每轮最多登记的失败运行数，1～1000 */
    private int scanLimit = 200;

    /** 每轮最多尝试发送的通知数，1～500 */
    private int dispatchLimit = 50;

    /** 每个运行最多通知的接收人数，1～10 */
    private int maxReceivers = 5;

    /** 每条通知最多认领发送的次数，1～10，用尽后转为死信 */
    private int maxAttempts = 5;

    /** 已送达、已关闭记录的保留天数，必须长于扫描窗口，2～3650 */
    private int retentionDays = 30;

    /**
     * 校验配置取值，越界时拒绝运行，避免静默按异常窗口扫描或过早清理导致重复通知。
     */
    void validate() {
        require(lookbackHours >= 1 && lookbackHours <= 168, "lookbackHours 须在 1～168 之间");
        require(scanLimit >= 1 && scanLimit <= 1000, "scanLimit 须在 1～1000 之间");
        require(dispatchLimit >= 1 && dispatchLimit <= 500, "dispatchLimit 须在 1～500 之间");
        require(maxReceivers >= 1 && maxReceivers <= 10, "maxReceivers 须在 1～10 之间");
        require(maxAttempts >= 1 && maxAttempts <= 10, "maxAttempts 须在 1～10 之间");
        require(retentionDays >= 2 && retentionDays <= 3650, "retentionDays 须在 2～3650 之间");
        require((long) retentionDays * 24 > lookbackHours, "retentionDays 必须长于扫描窗口 lookbackHours");
        for (String channel : extraChannels) {
            require(CHANNEL_SMS.equals(channel) || CHANNEL_MAIL.equals(channel), "extraChannels 只支持 SMS、MAIL");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("dataforge.run-alert 配置无效：" + message);
        }
    }

}
