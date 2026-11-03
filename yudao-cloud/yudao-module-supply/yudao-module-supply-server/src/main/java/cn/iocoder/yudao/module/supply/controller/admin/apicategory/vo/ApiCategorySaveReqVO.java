package cn.iocoder.yudao.module.supply.controller.admin.apicategory.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 服务分类保存 Request VO")
@Data
public class ApiCategorySaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "父级编号，0 表示顶级节点", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "上级类目不能为空")
    private Long parentId;

    @Schema(description = "数据服务类目名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "业务数据")
    @NotBlank(message = "数据服务类目名称不能为空")
    @Size(max = 100, message = "数据服务类目名称不能超过 100 个字符")
    private String name;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

    @Schema(description = "排序", example = "0")
    private Integer sort;

    @Schema(description = "描述")
    @Size(max = 500, message = "描述不能超过 500 个字符")
    private String description;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

}
