package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "当前平台任务执行实例统计")
public class DevRecordStatisticsRespVO {

    private Long total;

    private Long running;

    private Long success;

    private Long failed;

    private LocalDateTime refreshedAt;
}
