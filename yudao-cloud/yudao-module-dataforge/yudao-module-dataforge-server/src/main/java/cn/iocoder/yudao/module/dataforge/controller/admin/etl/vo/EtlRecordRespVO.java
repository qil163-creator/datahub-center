package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - ETL 执行记录 Response VO")
@Data
public class EtlRecordRespVO {

    private Integer protocolVersion;

    private Long rowVersion;

    private Boolean stopRequested;

    private Boolean occupiesSlot;

    private Boolean reviewRequired;

    private String executionStage;

    private String effectState;

    private Integer attemptCount;

    private LocalDateTime scheduledTime;

    private LocalDateTime updateTime;

    private LocalDateTime nextAttemptAt;

    private LocalDateTime nextReconcileAt;

    private Integer pipelineRevision;

    private Long executionUserId;

    private String qualityStatus;

    private Long qualityInstanceId;


    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "流程编号", example = "1")
    private Long pipelineId;

    @Schema(description = "流程名称", example = "用户数据清洗入仓")
    private String name;

    @Schema(description = "运行状态：1-等待 2-运行中 3-成功 4-失败 5-停止 6-未知；是否可重跑还须检查占位及核查状态", example = "3")
    private Integer runStatus;

    @Schema(description = "执行方式：1-手动 2-自动调度", example = "1")
    private Integer executeType;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "流程级日志（仅日志接口返回）")
    private String log;

    @Schema(description = "是否已结束（仅日志接口返回）", example = "true")
    private Boolean end;

    @Schema(description = "运行观测快照（仅日志接口返回）：节点实时读写行数、连线队列与等待时间、有界吞吐趋势；历史运行为空")
    private cn.iocoder.yudao.framework.etl.core.runtime.EtlRuntimeSample runtimeMetrics;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
