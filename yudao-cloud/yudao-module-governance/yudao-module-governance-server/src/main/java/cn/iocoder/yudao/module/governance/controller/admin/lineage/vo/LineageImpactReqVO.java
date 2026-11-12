package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 下游影响分析 Request VO")
@Data
public class LineageImpactReqVO {

    @Schema(description = "起点节点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "节点编号不能为空")
    private Long nodeId;

    @Schema(description = "起点字段名；为空时按表级血缘分析")
    @Size(max = 255, message = "字段名不能超过 255 个字符")
    private String column;

    @Schema(description = "展开层数 1-10，默认 5")
    @Min(value = 1, message = "层数不能小于 1")
    @Max(value = 10, message = "层数不能超过 10")
    private Integer depth = 5;

}
