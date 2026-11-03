package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 首次发布模型 Request VO")
@Data
public class ModelMaterializedPublishReqVO {

    @NotNull
    @Schema(description = "逻辑模型编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long modelId;

    @NotNull
    @Schema(description = "目标数据源编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long datasourceId;

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
