package cn.iocoder.yudao.module.dataforge.controller.admin.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 来源系统新增/修改 Request VO")
@Data
public class SystemSourceSaveReqVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "20386")
    private Long id;

    @Schema(description = "系统名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "芋艿")
    @NotEmpty(message = "系统名称不能为空")
    private String name;

    @Schema(description = "系统图标")
    private String icon;

    @Schema(description = "系统地址", example = "https://www.iocoder.cn")
    private String url;

    @Schema(description = "系统类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotEmpty(message = "系统类型不能为空")
    private String type;

    @Schema(description = "负责人ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "22442")
    @NotNull(message = "负责人ID不能为空")
    private Long ownerUserId;

    @Schema(description = "说明", example = "随便")
    private String description;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "排序不能为空")
    private Integer sortOrder;

    @Schema(description = "所属部门id", requiredMode = Schema.RequiredMode.REQUIRED, example = "25154")
    @NotNull(message = "所属部门id不能为空")
    private Short deptId;

    @Schema(description = "状态：0停用 1启用", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "状态：0停用 1启用不能为空")
    private Short status;

    @Schema(description = "备注", example = "你说的对")
    private String remark;

}