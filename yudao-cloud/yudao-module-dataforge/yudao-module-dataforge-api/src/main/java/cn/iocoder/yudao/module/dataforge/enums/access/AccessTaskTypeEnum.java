package cn.iocoder.yudao.module.dataforge.enums.access;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据同步-任务类型枚举
 *
 * 与 srt-cloud 的 TaskType 编码保持一致，便于前端复用
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum AccessTaskTypeEnum {

    /**
     * 实时同步（暂不支持，预留）
     */
    REAL_TIME_SYNC(1, "实时同步"),
    /**
     * 发布后立即执行一次全量同步
     */
    ONE_TIME_FULL_SYNC(2, "一次性全量同步"),
    /**
     * 首次全量，之后按 cron 周期执行（可配增量策略）
     */
    ONE_TIME_FULL_PERIODIC_INCR_SYNC(3, "一次全量周期增量");

    private final Integer type;

    private final String name;

}
