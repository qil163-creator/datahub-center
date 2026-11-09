package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 数据开发补数据批次 Response VO")
@Data
public class DevBackfillRespVO {

    @Schema(description = "批次编号")
    private Long id;

    @Schema(description = "开发任务编号")
    private Long taskId;

    @Schema(description = "任务名称")
    private String taskName;

    @Schema(description = "冻结的任务保存版本")
    private Long versionId;

    @Schema(description = "起始业务日期")
    private LocalDate startDate;

    @Schema(description = "截止业务日期")
    private LocalDate endDate;

    @Schema(description = "日期顺序")
    private String dateOrder;

    @Schema(description = "失败策略")
    private String failurePolicy;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "日期总数")
    private Integer totalCount;

    @Schema(description = "成功日期数")
    private Integer successCount;

    @Schema(description = "失败日期数")
    private Integer failedCount;

    @Schema(description = "取消日期数")
    private Integer cancelledCount;

    @Schema(description = "是否已请求取消")
    private Boolean cancelRequested;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "最近失败或终止原因")
    private String lastError;

    @Schema(description = "发起人")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "结束时间")
    private LocalDateTime finishTime;

    @Schema(description = "日期明细（仅详情返回）")
    private List<DevBackfillItemRespVO> items;

}
