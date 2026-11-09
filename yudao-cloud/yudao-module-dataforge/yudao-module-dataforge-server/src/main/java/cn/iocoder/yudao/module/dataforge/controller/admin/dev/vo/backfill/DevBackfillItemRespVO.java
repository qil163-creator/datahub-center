package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据开发补数据日期明细 Response VO")
@Data
public class DevBackfillItemRespVO {

    @Schema(description = "明细编号")
    private Long id;

    @Schema(description = "执行顺序")
    private Integer seq;

    @Schema(description = "业务日期")
    private LocalDate businessDate;

    @Schema(description = "状态：PENDING/RUNNING/SUCCESS/FAILED/CANCELLED")
    private String status;

    @Schema(description = "运行实例编号")
    private Long recordId;

    @Schema(description = "失败原因")
    private String lastError;

    @Schema(description = "提交时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

}
