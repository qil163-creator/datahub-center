package cn.iocoder.yudao.module.dataforge.controller.admin.fullsync.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 整库同步向导校验响应。
 */
@Schema(description = "管理后台 - 整库同步向导校验 Response VO")
@Data
public class FullSyncWizardValidateRespVO {

    @Schema(description = "是否通过", example = "true")
    private Boolean valid;

    @Schema(description = "当前步骤")
    private Integer currentStep;

    @Schema(description = "校验消息")
    private List<String> messages;

    @Schema(description = "来源表数量")
    private Integer sourceTableCount;

    @Schema(description = "选中表数量")
    private Integer selectedTableCount;

    @Schema(description = "执行模式")
    private String executionMode;
}

