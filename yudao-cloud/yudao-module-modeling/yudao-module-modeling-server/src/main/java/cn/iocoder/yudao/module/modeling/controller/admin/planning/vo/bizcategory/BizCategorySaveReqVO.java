package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.bizcategory;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 业务分类保存 Request VO")
@Data
public class BizCategorySaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "父级编号，0 表示顶级节点", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "上级分类不能为空")
    private Long parentId;

    @Schema(description = "业务分类名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "水利工程管理业务")
    @NotBlank(message = "业务分类名称不能为空")
    @Size(max = 100, message = "业务分类名称不能超过 100 个字符")
    private String name;

    @Schema(description = "英文缩写", requiredMode = Schema.RequiredMode.REQUIRED, example = "WATEN")
    @NotBlank(message = "英文缩写不能为空")
    @Size(max = 50, message = "英文缩写不能超过 50 个字符")
    private String code;

    @Schema(description = "负责人用户编号", example = "1")
    private Long leaderUserId;

    @Schema(description = "负责人电话（随负责人自动带出）", example = "15365128327")
    @Size(max = 20, message = "负责人电话不能超过 20 个字符")
    private String leaderPhone;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

    @Schema(description = "责任范围说明，不作为授权依据")
    @Size(max = 2000, message = "责任范围不能超过 2000 个字符")
    private String responsibilityScope;

    @Schema(description = "描述")
    @Size(max = 500, message = "描述不能超过 500 个字符")
    private String description;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

    @Schema(description = "关联数据域编号列表（至少 1 个，顺序即 sort_order）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "关联数据域不能为空")
    private List<Long> dataDomainIds;

}
