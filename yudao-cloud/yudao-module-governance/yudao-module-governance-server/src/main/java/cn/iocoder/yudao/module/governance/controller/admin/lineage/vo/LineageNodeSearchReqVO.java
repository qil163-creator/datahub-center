package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 血缘节点检索 Request VO")
@Data
public class LineageNodeSearchReqVO {

    @Schema(description = "关键字：匹配表名、schema、外部标识、展示名及元数据业务名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "关键字不能为空")
    @Size(max = 128, message = "关键字不能超过 128 个字符")
    private String keyword;

    @Schema(description = "数据源编号")
    private Long datasourceId;

    @Schema(description = "最多返回条数，默认 20，最大 50")
    @Min(value = 1, message = "返回条数不能小于 1")
    @Max(value = 50, message = "返回条数不能超过 50")
    private Integer limit = 20;

}
