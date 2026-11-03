package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据推送增量水位")
@Data
public class DataPushWatermarkRespVO {

    @Schema(description = "推送任务编号")
    private Long pushTaskId;

    @Schema(description = "是否增量任务")
    private Boolean increment;

    @Schema(description = "是否已有水位记录")
    private Boolean exists;

    @Schema(description = "增量字段")
    private String incrementColumn;

    @Schema(description = "本次下界（不含）；首次为空")
    private String startVal;

    @Schema(description = "本次上界（含）")
    private String endVal;

    @Schema(description = "水位更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "任务配置的首次起始水位")
    private String initStartVal;

    @Schema(description = "最近重置操作人编号")
    private Long resetUserId;

    @Schema(description = "最近重置操作人")
    private String resetUserName;

    @Schema(description = "最近重置时间")
    private LocalDateTime resetTime;

    @Schema(description = "最近重置前的水位上界")
    private String resetOldEnd;

}
