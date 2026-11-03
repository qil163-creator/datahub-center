package cn.iocoder.yudao.module.governance.controller.admin.permission.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据权限字段 Response VO")
@Data
public class SafetyPermissionColumnRespVO {

    @Schema(description = "字段名", example = "mobile")
    private String fieldName;

    @Schema(description = "字段类型", example = "varchar")
    private String fieldType;

    @Schema(description = "字段说明", example = "手机号")
    private String fieldComment;

    @Schema(description = "是否主键", example = "false")
    private Boolean pkFlag;

    @Schema(description = "元数据上的数据分级编号", example = "1")
    private Long safetyLevelId;

}
