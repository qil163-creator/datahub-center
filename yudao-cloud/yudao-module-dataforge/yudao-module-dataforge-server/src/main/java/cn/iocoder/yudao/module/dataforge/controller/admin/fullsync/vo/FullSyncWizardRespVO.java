package cn.iocoder.yudao.module.dataforge.controller.admin.fullsync.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 整库同步向导状态响应。
 */
@Schema(description = "管理后台 - 整库同步向导状态 Response VO")
@Data
public class FullSyncWizardRespVO {

    @Schema(description = "向导编号", example = "1024")
    private Long id;

    @Schema(description = "当前步骤", example = "3")
    private Integer currentStep;

    @Schema(description = "并发版本", example = "3")
    private Long revision;

    @Schema(description = "状态：DRAFT、SUBMITTING、SUBMITTED、FAILED、UNKNOWN（外部结果待核对）", example = "DRAFT")
    private String status;

    @Schema(description = "平台 DataAccess 任务编号；DBSWITCH 模式返回")
    private Long dataAccessId;

    @Schema(description = "DolphinScheduler 工作流定义编号；DOLPHIN_DATAX 模式返回")
    private Long dsWorkflowCode;

    @Schema(description = "DolphinScheduler 调度编号；周期任务返回")
    private Integer dsScheduleId;

    @Schema(description = "DolphinScheduler 工作流实例编号；触发运行后返回")
    private Integer dsInstanceId;

    @Schema(description = "最近一次提交错误")
    private String lastError;

    @Schema(description = "校验提示")
    private List<String> validationMessages;

    @Schema(description = "向导配置（不含数据库密码）")
    private FullSyncWizardConfigVO config;

    @Schema(description = "创建人编号；平台范围内按接口权限可见")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}

