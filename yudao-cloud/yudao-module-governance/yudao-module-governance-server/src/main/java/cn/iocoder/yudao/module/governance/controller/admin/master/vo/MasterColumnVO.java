package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 主数据模型字段 VO")
@Data
public class MasterColumnVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "物理字段名", requiredMode = Schema.RequiredMode.REQUIRED, example = "person_code")
    @NotBlank(message = "字段名不能为空")
    @Size(max = 50, message = "字段名不能超过 50 个字符")
    private String name;

    @Schema(description = "字段注释", example = "人员编码")
    @Size(max = 200, message = "字段注释不能超过 200 个字符")
    private String commentStr;

    @Schema(description = "数据类型（dbswitch 逻辑类型）", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "数据类型不能为空")
    private Integer fieldType;

    @Schema(description = "字段长度", example = "64")
    private Integer fieldLength;

    @Schema(description = "小数位数", example = "2")
    private Integer fieldScale;

    @Schema(description = "是否可为空：0-否 1-是", example = "1")
    private Integer nullable;

    @Schema(description = "是否主键：0-否 1-是", example = "0")
    private Integer pk;

    @Schema(description = "字段顺序", example = "1")
    private Integer sort;

    @Schema(description = "绑定的标准数据元编号", example = "1")
    private Long standardElementId;

}
