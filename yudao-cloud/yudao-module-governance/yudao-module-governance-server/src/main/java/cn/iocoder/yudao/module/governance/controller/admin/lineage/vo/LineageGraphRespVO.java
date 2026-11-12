package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "管理后台 - 表级血缘图 Response VO")
@Data
public class LineageGraphRespVO {

    @Schema(description = "根节点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long rootNodeId;

    @Schema(description = "节点（按层数、编号排序，最多 300 个）", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<LineageGraphNodeRespVO> nodes = new ArrayList<>();

    @Schema(description = "节点之间的全部边", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<LineageGraphEdgeRespVO> edges = new ArrayList<>();

    @Schema(description = "是否因节点或边数量上限被截断", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean truncated;

}
