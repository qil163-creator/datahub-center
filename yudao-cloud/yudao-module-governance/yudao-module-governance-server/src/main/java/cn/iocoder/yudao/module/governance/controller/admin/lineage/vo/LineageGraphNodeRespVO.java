package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 表级血缘图节点 Response VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class LineageGraphNodeRespVO extends LineageNodeRespVO {

    @Schema(description = "相对根节点的层数：根为 0，上游为负、下游为正", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer depth;

    @Schema(description = "是否还有未返回的上游节点", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean upstreamExpandable;

    @Schema(description = "是否还有未返回的下游节点", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean downstreamExpandable;

}
