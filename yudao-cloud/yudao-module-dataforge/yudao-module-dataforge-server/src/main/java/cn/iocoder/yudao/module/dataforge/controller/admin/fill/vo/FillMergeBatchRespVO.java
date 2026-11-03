package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线填报-合并批次 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 合并批次 Response VO")
@Data
public class FillMergeBatchRespVO {

    @Schema(description = "批次编号", example = "1")
    private Long id;

    @Schema(description = "提交快照编号（幂等键）", example = "1")
    private Long submissionId;

    @Schema(description = "工作项编号", example = "1")
    private Long workItemId;

    @Schema(description = "任务编号", example = "1")
    private Long taskId;

    @Schema(description = "合并策略", example = "UPSERT")
    private String mergeStrategy;

    @Schema(description = "目标数据源编号", example = "1")
    private Long targetDatabaseId;

    @Schema(description = "目标物理表名", example = "ms_project_monthly")
    private String targetTableName;

    @Schema(description = "状态：0-待执行 1-执行中 2-成功 3-失败", example = "2")
    private Integer status;

    @Schema(description = "应写入总条数", example = "120")
    private Integer totalCount;

    @Schema(description = "成功写入条数", example = "120")
    private Integer successCount;

    @Schema(description = "失败原因")
    private String errorMessage;

    @Schema(description = "已重试次数", example = "0")
    private Integer retryCount;

    @Schema(description = "下次重试时间")
    private LocalDateTime nextRetryTime;

    @Schema(description = "本次执行开始时间")
    private LocalDateTime startedAt;

    @Schema(description = "本次执行结束时间")
    private LocalDateTime finishedAt;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
