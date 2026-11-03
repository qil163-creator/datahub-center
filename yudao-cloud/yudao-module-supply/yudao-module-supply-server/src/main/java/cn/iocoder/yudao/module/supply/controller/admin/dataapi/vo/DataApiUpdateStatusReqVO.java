package cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.Data;

@Schema(description = "管理后台 - API 服务状态更新 Request VO")
@Data
public class DataApiUpdateStatusReqVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "编号不能为空")
    private Long id;

    @Schema(description = "状态：0-下线 1-上线", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "状态不能为空")
    @Min(0)
    @Max(1)
    private Integer status;

    @Schema(description = "操作时看到的控制版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请刷新后提交当前版本")
    @Min(0)
    private Integer expectedRowVersion;

}
