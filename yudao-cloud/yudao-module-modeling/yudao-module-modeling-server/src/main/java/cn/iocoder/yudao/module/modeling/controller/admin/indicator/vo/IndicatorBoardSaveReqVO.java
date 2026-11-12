package cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 管理后台 - 保存指标看板 Request VO
 */
@Schema(description = "管理后台 - 保存指标看板 Request VO")
@Data
public class IndicatorBoardSaveReqVO {

    @Schema(description = "编号；新建时为空")
    private Long id;

    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "经营日报")
    @NotBlank(message = "看板名称不能为空")
    @Size(max = 100, message = "看板名称不能超过 100 个字符")
    private String name;

    @Schema(description = "说明")
    @Size(max = 500, message = "说明不能超过 500 个字符")
    private String description;

    @Schema(description = "组件，最多 12 个", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "请至少添加一个组件")
    @Size(max = 12, message = "组件最多 12 个")
    private List<@Valid @NotNull Widget> widgets;

    @Schema(description = "期望修订号（修改时必填）", example = "0")
    @Min(value = 0, message = "修订号不能小于 0")
    private Integer expectedRevision;

    /** 看板组件。 */
    @Data
    public static class Widget {

        @Schema(description = "组件标识（看板内唯一）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "组件标识不能为空")
        @Pattern(regexp = "[A-Za-z0-9_-]{1,32}", message = "组件标识只能含字母、数字、下划线或短横线")
        private String key;

        @Schema(description = "标题", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "组件标题不能为空")
        @Size(max = 60, message = "组件标题不能超过 60 个字符")
        private String title;

        @Schema(description = "展示方式：LINE/BAR/TABLE/STAT", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "展示方式不能为空")
        @Pattern(regexp = "LINE|BAR|TABLE|STAT", message = "展示方式不合法")
        private String chartType;

        @Schema(description = "指标查询", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "组件查询不能为空")
        @Valid
        private IndicatorQueryReqVO query;
    }
}
