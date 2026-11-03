package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "当前平台数据开发运行统计")
public class DevTaskStatisticsRespVO {

    private Long runningTasks;

    private Long todayFailed;

    private Long todayExecutions;

    /** 展示用近似百分比，不作为精确业务金额或指标。 */
    private Double todaySuccessRate;

    private LocalDateTime refreshedAt;
}
