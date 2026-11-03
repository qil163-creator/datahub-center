package cn.iocoder.yudao.module.dataforge.controller.admin.fullsync.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 整库同步向导步骤保存请求。
 */
@Schema(description = "管理后台 - 整库同步向导步骤保存 Request VO")
@Data
public class FullSyncWizardSaveReqVO {

    @Schema(description = "向导当前编号，更新时必填", example = "1024")
    private Long id;

    @Schema(description = "要保存的步骤：1 基础信息，2 来源表，3 多表映射，4 属性配置",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "向导步骤不能为空")
    @Min(value = 1, message = "向导步骤只能为 1 至 4")
    @Max(value = 4, message = "向导步骤只能为 1 至 4")
    private Integer step;

    @Schema(description = "并发版本；更新时必填", example = "1")
    @Min(value = 0, message = "并发版本不能为负数")
    private Long expectedRevision;

    @Schema(description = "步骤配置", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "向导配置不能为空")
    @Valid
    private FullSyncWizardConfigVO config;
}

