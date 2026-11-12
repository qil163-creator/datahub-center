package cn.iocoder.yudao.module.governance.api.lineage.dto;

import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageEdgeOriginEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 表级血缘边：数据从 upstream 流向 downstream。同一报告内重复的边由服务端合并。
 */
@Schema(description = "RPC 服务 - 表级血缘边")
@Data
public class LineageTableEdgeDTO {

    @Schema(description = "上游节点", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "上游节点不能为空")
    @Valid
    private LineageNodeRefDTO upstream;

    @Schema(description = "下游节点", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "下游节点不能为空")
    @Valid
    private LineageNodeRefDTO downstream;

    @Schema(description = "边来源：PARSED/MANUAL/RUNTIME", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "边来源不能为空")
    @InEnum(value = LineageEdgeOriginEnum.class, message = "边来源必须是 {value}")
    private String origin;

    @Schema(description = "所在语句序号（从 0 开始，可空）")
    @Min(value = 0, message = "语句序号不能小于 0")
    private Integer statementIndex;

}
