package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 字段级血缘图节点（节点 + 字段）Response VO")
@Data
public class LineageColumnGraphNodeRespVO {

    @Schema(description = "节点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long nodeId;

    @Schema(description = "字段名（小写规范化）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String column;

    @Schema(description = "相对根字段的层数：根为 0，上游为负、下游为正", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer depth;

    @Schema(description = "是否还有未返回的上游字段", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean upstreamExpandable;

    @Schema(description = "是否还有未返回的下游字段", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean downstreamExpandable;

    @Schema(description = "节点信息", requiredMode = Schema.RequiredMode.REQUIRED)
    private LineageNodeRespVO node;

}
