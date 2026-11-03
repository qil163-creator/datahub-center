package cn.iocoder.yudao.module.governance.controller.admin.identify.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 敏感字段打标（人工添加）Request VO")
@Data
public class SafetyFieldTagSaveReqVO {

    @Schema(description = "数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "数据源不能为空")
    private Long databaseId;

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "sys_user")
    @NotEmpty(message = "表名不能为空")
    private String tableName;

    @Schema(description = "字段名", requiredMode = Schema.RequiredMode.REQUIRED, example = "mobile")
    @NotEmpty(message = "字段名不能为空")
    private String columnName;

    @Schema(description = "数据分类编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "数据分类不能为空")
    private Long categoryId;

    @Schema(description = "数据分级编号（为空取分类上配置的分级）", example = "2")
    private Long levelId;

    @Schema(description = "备注")
    private String remark;

}
