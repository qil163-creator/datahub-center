package cn.iocoder.yudao.module.governance.framework.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 异常访问识别阈值。按用户、按统计窗口聚合敏感数据访问审计，超过阈值即生成告警。
 */
@Data
@Component
@ConfigurationProperties(prefix = "yudao.governance.access-alert")
public class SafetyAccessAlertProperties {

    /** 统计窗口（分钟），按整点对齐。 */
    private int windowMinutes = 60;
    /** 窗口内敏感数据访问次数上限。 */
    private int volumeThreshold = 200;
    /** 窗口内因白名单豁免看到原值的次数上限。 */
    private int plaintextThreshold = 50;
    /** 窗口内在非工作时段访问敏感数据的次数上限。 */
    private int offHoursThreshold = 10;
    /** 窗口内访问的不同表数量上限。 */
    private int tableSpreadThreshold = 30;
    /** 非工作时段开始（时，含）。 */
    private int offHoursStart = 22;
    /** 非工作时段结束（时，不含）。 */
    private int offHoursEnd = 6;
}
