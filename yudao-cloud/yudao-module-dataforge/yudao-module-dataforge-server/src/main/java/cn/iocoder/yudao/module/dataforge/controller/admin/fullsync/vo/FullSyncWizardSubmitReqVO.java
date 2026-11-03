package cn.iocoder.yudao.module.dataforge.controller.admin.fullsync.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 整库同步向导提交请求。
 */
@Schema(description = "管理后台 - 整库同步向导提交 Request VO")
@Data
public class FullSyncWizardSubmitReqVO {

    @Schema(description = "并发版本；省略时使用当前版本", example = "4")
    @Min(value = 0, message = "并发版本不能为负数")
    private Long expectedRevision;

    @Schema(description = "是否提交后立即触发一次任务", example = "true")
    private Boolean trigger = Boolean.TRUE;
}

