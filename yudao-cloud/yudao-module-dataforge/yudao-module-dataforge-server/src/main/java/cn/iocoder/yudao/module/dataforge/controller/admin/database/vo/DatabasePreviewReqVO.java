package cn.iocoder.yudao.module.dataforge.controller.admin.database.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 数据源表样例预览 Request VO")
@Data
public class DatabasePreviewReqVO {

    @Schema(description = "数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "数据源编号不能为空")
    private Long databaseId;

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "t_user")
    @NotBlank(message = "表名不能为空")
    @Size(max = 200, message = "表名过长")
    private String tableName;

    @Schema(description = "预览行数，默认 20，最大 100", example = "20")
    @Min(value = 1, message = "预览行数不能小于 1")
    @Max(value = 100, message = "预览行数不能大于 100")
    private Integer limit;

}
