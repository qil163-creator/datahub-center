package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.bizcategory;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 业务分类状态更新 Request VO")
@Data
public class BizCategoryUpdateStatusReqVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "编号不能为空")
    private Long id;

    @Schema(description = "状态：0-启用 1-停用", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    private Integer status;

}
