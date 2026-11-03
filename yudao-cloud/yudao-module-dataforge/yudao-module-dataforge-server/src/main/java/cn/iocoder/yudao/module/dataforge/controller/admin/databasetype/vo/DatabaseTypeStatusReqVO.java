package cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 数据库类型状态更新请求")
public class DatabaseTypeStatusReqVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long id;

    @Schema(description = "状态：0 启用，1 停用", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(0)
    @Max(1)
    private Short status;

}
