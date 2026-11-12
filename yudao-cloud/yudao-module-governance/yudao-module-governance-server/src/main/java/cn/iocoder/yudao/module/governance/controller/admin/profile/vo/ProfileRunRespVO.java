package cn.iocoder.yudao.module.governance.controller.admin.profile.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据探查记录 Response VO")
@Data
public class ProfileRunRespVO {

    @Schema(description = "探查记录编号")
    private Long id;

    @Schema(description = "元数据表编号")
    private Long metaTableId;

    @Schema(description = "物理 schema 快照")
    private String schemaName;

    @Schema(description = "物理表名快照")
    private String tableName;

    @Schema(description = "状态：PENDING/RUNNING/SUCCESS/FAILED")
    private String status;

    @Schema(description = "抽样行数上限")
    private Integer sampleLimit;

    @Schema(description = "实际抽样行数")
    private Long sampleRows;

    @Schema(description = "全表总行数；统计失败时为空")
    private Long totalRows;

    @Schema(description = "探查字段数")
    private Integer columnCount;

    @Schema(description = "执行人编号")
    private Long executorUserId;

    @Schema(description = "执行人昵称")
    private String executorName;

    @Schema(description = "开始执行时间（毫秒时间戳）")
    private LocalDateTime startTime;

    @Schema(description = "结束时间（毫秒时间戳）")
    private LocalDateTime endTime;

    @Schema(description = "失败原因")
    private String failReason;

    @Schema(description = "已领取执行次数")
    private Integer attemptCount;

    @Schema(description = "发起时间（毫秒时间戳）")
    private LocalDateTime createTime;

}
