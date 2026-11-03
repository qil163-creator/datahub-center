package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据同步执行记录 Response VO")
@Data
public class DataAccessTaskRespVO {

    @Schema(description = "主键id", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "数据同步任务id", example = "1024")
    private Long dataAccessId;

    @Schema(description = "任务名称", example = "人口库同步")
    private String taskName;

    @Schema(description = "运行状态：1-等待中 2-运行中 3-正常结束 4-异常结束 5-未知 6-已停止", example = "3")
    private Integer runStatus;

    @Schema(description = "触发方式：1-自动调度 2-手动执行", example = "2")
    private Integer runType;

    @Schema(description = "执行编号")
    private String executeNo;

    @Schema(description = "Seatunnel 作业编号")
    private String seatunnelJobId;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "实时日志")
    private String realTimeLog;

    @Schema(description = "错误信息")
    private String errorInfo;

    @Schema(description = "同步数据量（行数）", example = "10000")
    private Long dataCount;

    @Schema(description = "成功表数量", example = "10")
    private Long tableSuccessCount;

    @Schema(description = "失败表数量", example = "0")
    private Long tableFailCount;

    @Schema(description = "同步数据大小", example = "10.5MB")
    private String byteCount;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "运行协议版本；历史未知执行须人工核查")
    private Integer protocolVersion;

    @Schema(description = "执行阶段 PREPARE / EXECUTION_STARTED / SUBMIT_SENT")
    private String executionStage;

    @Schema(description = "输出证据 NONE / COMPLETE / PARTIAL / UNKNOWN")
    private String effectState;

    @Schema(description = "是否已持久受理停止请求，不表示已终止")
    private Boolean stopRequested;

    @Schema(description = "是否仍阻止同任务后续执行")
    private Boolean occupiesSlot;

    @Schema(description = "是否要求人工核查")
    private Boolean reviewRequired;

    @Schema(description = "核查命令所需行版本")
    private Long rowVersion;

    @Schema(description = "原计划时刻")
    private LocalDateTime scheduledTime;

    @Schema(description = "下次安全重试时刻")
    private LocalDateTime nextAttemptAt;

    @Schema(description = "下次对账时刻")
    private LocalDateTime nextReconcileAt;

}
