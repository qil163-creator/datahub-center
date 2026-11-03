package cn.iocoder.yudao.module.dataforge.framework.execution.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 数据同步/ETL 的租约和公平调度上限，不包含引擎技术配置。 */
@Data
@Validated
@ConfigurationProperties(prefix = "yudao.dataforge.execution")
public class RunExecutionProperties {

    @NotNull
    private Duration lease = Duration.ofSeconds(90);

    @NotNull
    private Duration heartbeat = Duration.ofSeconds(20);

    @NotNull
    private Duration stopPoll = Duration.ofSeconds(5);

    @Min(1)
    @Max(10_000)
    private int scanBatch = 100;

    @Min(1)
    @Max(100)
    private int perTaskCatchUp = 1;

    @Min(1)
    @Max(10_000)
    private int perTaskPending = 100;

    /** 平台在集群内共享的 ETL 与接入槽位；各节点必须使用相同配置。 */
    @Min(1)
    @Max(10_000)
    private int platformActive = 32;

    /** 同一平台数据源上的 ETL + 采集共享占位；所有节点必须使用相同配置。 */
    @Min(1)
    @Max(10_000)
    private int perDataSourceActive = 8;

    @Min(1)
    @Max(100)
    private int automaticFailureLimit = 10;

    @Min(1)
    @Max(128)
    private int workerConcurrency = 4;

    @Min(0)
    @Max(10_000)
    private int workerQueue = 32;

    /**
     * 判断是否满足计时有效条件。
     *
     * @return 判断结果
     */
    @AssertTrue(message = "租约须为正且不少于三个心跳周期，停止轮询须为正、不超过五秒且小于租约，时长不得超过一天")
    public boolean isTimingValid() {
        Duration maximum = Duration.ofDays(1);
        return validDuration(lease, maximum) && validDuration(heartbeat, maximum)
                && validDuration(stopPoll, maximum)
                && lease.compareTo(heartbeat.multipliedBy(3)) >= 0
                && stopPoll.compareTo(Duration.ofSeconds(5)) <= 0
                && stopPoll.compareTo(lease) < 0;
    }

    /**
     * 判断是否满足补齐向上有界条件。
     *
     * @return 判断结果
     */
    @AssertTrue(message = "单任务补发数量不得超过其等待队列容量")
    public boolean isCatchUpBounded() {
        return perTaskCatchUp <= perTaskPending;
    }

    /**
     * 判断有效耗时条件是否成立。
     *
     * @param duration 耗时
     * @param maximum 最大值
     * @return 处理是否成功
     */
    private static boolean validDuration(Duration duration, Duration maximum) {
        return duration != null && duration.compareTo(Duration.ofSeconds(1)) >= 0
                && duration.compareTo(maximum) <= 0 && duration.getNano() == 0;
    }
}
