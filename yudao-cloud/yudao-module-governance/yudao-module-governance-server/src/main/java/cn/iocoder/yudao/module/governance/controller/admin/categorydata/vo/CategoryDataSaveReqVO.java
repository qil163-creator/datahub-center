package cn.iocoder.yudao.module.governance.controller.admin.categorydata.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.Data;

@Schema(description = "管理后台 - 数据分类 类目管理新增/修改 Request VO")
@Data
public class CategoryDataSaveReqVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "14259")
    private Long id;

    @Positive
    private Long revision;

    @Schema(description = "父级ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "21518")
    @NotNull(message = "父级ID不能为空")
    private Long parentId;

    @Schema(description = "类目名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "李四")
    @NotBlank(message = "类目名称不能为空")
    @Size(max = 50)
    private String categoryName;

    @Schema(description = "层级编码（后端自动生成，无需传入）")
    private String categoryCode;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer sort;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @Min(0)
    @Max(1)
    private Short status;

    @Schema(description = "备注", example = "随便")
    @Size(max = 256)
    private String remark;

}
