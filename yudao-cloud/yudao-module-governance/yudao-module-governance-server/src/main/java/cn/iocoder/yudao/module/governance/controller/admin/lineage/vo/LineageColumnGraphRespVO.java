package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "管理后台 - 字段级血缘图 Response VO")
@Data
public class LineageColumnGraphRespVO {

    @Schema(description = "根节点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long rootNodeId;

    @Schema(description = "根字段名（小写规范化）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String rootColumn;

    @Schema(description = "字段节点（按层数、节点编号、字段名排序，最多 500 个）", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<LineageColumnGraphNodeRespVO> nodes = new ArrayList<>();

    @Schema(description = "字段节点之间的全部边", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<LineageColumnGraphEdgeRespVO> edges = new ArrayList<>();

    @Schema(description = "是否因字段节点或边数量上限被截断", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean truncated;

}
