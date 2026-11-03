package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 重新发布模型 Request VO")
@Data
public class ModelMaterializedRepublishReqVO {

    @NotNull
    @Schema(description = "物化记录编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @NotBlank
    @Schema(description = "发布模式 REBUILD/INCREMENT", requiredMode = Schema.RequiredMode.REQUIRED)
    private String publishMode;

    @Size(max = 500)
    @Schema(description = "描述")
    private String description;

    @Size(max = 500)
    @Schema(description = "备注")
    private String remark;
}
