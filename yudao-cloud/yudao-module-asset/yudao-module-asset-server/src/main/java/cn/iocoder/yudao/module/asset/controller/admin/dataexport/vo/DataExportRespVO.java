package cn.iocoder.yudao.module.asset.controller.admin.dataexport.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台 - 数据导出申请 Response VO。不返回查询 SQL 之外的数据。
 */
@Schema(description = "管理后台 - 数据导出申请 Response VO")
@Data
public class DataExportRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "数据源编号", example = "1")
    private Long datasourceId;

    @Schema(description = "目标表（schema.table）", example = "ods.users")
    private String tableName;

    @Schema(description = "查询 SQL")
    private String sqlText;

    @Schema(description = "导出行数上限", example = "1000")
    private Integer rowLimit;

    @Schema(description = "申请原因")
    private String reason;

    @Schema(description = "状态：PENDING/APPROVED/REJECTED/CANCELED/DOWNLOADED/FAILED", example = "PENDING")
    private String status;

    @Schema(description = "BPM 流程实例编号")
    private String processInstanceId;

    @Schema(description = "审批说明")
    private String resultReason;

    @Schema(description = "批准时间")
    private LocalDateTime approvedTime;

    @Schema(description = "下载截止时间")
    private LocalDateTime expireTime;

    @Schema(description = "下载时间")
    private LocalDateTime downloadTime;

    @Schema(description = "下载行数")
    private Integer downloadRows;

    @Schema(description = "申请时间")
    private LocalDateTime createTime;
}
