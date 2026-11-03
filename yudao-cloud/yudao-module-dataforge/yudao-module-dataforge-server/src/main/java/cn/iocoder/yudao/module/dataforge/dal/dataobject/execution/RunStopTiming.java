package cn.iocoder.yudao.module.dataforge.dal.dataobject.execution;

import java.time.LocalDateTime;

/** 只包含已提交运行的取消/结束时刻，不用于业务状态决策。 */
public record RunStopTiming(LocalDateTime requested, LocalDateTime confirmed) {

}
