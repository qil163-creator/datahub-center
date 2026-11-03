package cn.iocoder.yudao.module.supply.controller.admin.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 应用保存 Request VO")
@Data
public class SupplyAppSaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "应用名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "某县灌区系统")
    @NotBlank(message = "应用名称不能为空")
    @Size(max = 100, message = "应用名称不能超过 100 个字符")
    private String name;

    @Schema(description = "应用类型：Web/App/小程序", requiredMode = Schema.RequiredMode.REQUIRED, example = "Web")
    @NotBlank(message = "应用类型不能为空")
    private String appType;

    @Schema(description = "应用图标地址")
    @Size(max = 500, message = "应用图标地址不能超过 500 个字符")
    private String icon;

    @Schema(description = "是否公开：0-非公开 1-公开", example = "1")
    private Integer publicFlag;

    @Schema(description = "描述")
    @Size(max = 500, message = "描述不能超过 500 个字符")
    private String description;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

    @Schema(description = "使用部门业务系统编码")
    @Size(max = 64, message = "系统编码不能超过 64 个字符")
    private String systemCode;

    @Schema(description = "应用用途说明")
    @Size(max = 200, message = "用途不能超过 200 个字符")
    private String purpose;

    @Schema(description = "运行环境")
    @Size(max = 32, message = "运行环境不能超过 32 个字符")
    private String environment;

}
