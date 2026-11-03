package cn.iocoder.yudao.module.governance.controller.admin.category.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Schema(description = "管理后台 - 数据分类信息新增/修改 Request VO")
@Data
public class SafetyCategorySaveReqVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "9062")
    private Long id;

    @Positive
    private Long revision;

    @Schema(description = "所属类目ID", example = "9061")
    @NotNull
    @Positive
    private Long categoryId;

    @Schema(description = "分类名称", example = "王五")
    @NotBlank
    @Size(max = 50)
    private String name;

    @Schema(description = "分类缩写", example = "芋艿")
    @Size(max = 255)
    private String shortName;

    @Schema(description = "数据分级", example = "25576")
    @NotNull
    @Positive
    private Integer dataLevelId;

    @Schema(description = "优先级")
    @NotBlank
    @Pattern(regexp = "HIGHEST|HIGH|MEDIUM|LOW|LOWEST")
    private String priority;

    @Schema(description = "脱敏配置", example = "29537")
    private Long ruleId;

    @Schema(description = "备注", example = "你猜")
    private String remark;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "状态不能为空")
    @Min(0)
    @Max(1)
    private Short status;

    @Schema(description = "显示顺序", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer sort;

    @Schema(description = "规则描述", example = "随便")
    @Size(max = 256)
    private String description;

}
