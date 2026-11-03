package cn.iocoder.yudao.module.dataforge.dal.dataobject.execution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 控制面聚合结果，不含租户、任务、配置或凭据；不是持久化实体。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunMetricsSnapshot {

    private long queued;

    private double queueOldestAge;

    private long slots;

    private long unknown;

    private double unknownOldestRunAge;

    private long review;

    private long stopPending;

    private double stopOldestAge;

    private long expired;

    private long qualityPending;

    private long due;

    private double overdueAge;
}

