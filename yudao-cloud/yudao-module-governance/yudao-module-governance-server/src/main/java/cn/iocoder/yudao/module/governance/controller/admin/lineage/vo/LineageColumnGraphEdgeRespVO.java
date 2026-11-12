package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "管理后台 - 字段级血缘图边（同一字段对与变换的多来源合并）Response VO")
@Data
public class LineageColumnGraphEdgeRespVO {

    @Schema(description = "上游节点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long upstreamNodeId;

    @Schema(description = "上游字段名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String upstreamColumn;

    @Schema(description = "下游节点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long downstreamNodeId;

    @Schema(description = "下游字段名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String downstreamColumn;

    @Schema(description = "变换大类：DIRECT/INDIRECT", requiredMode = Schema.RequiredMode.REQUIRED)
    private String transformType;

    @Schema(description = "变换子类", requiredMode = Schema.RequiredMode.REQUIRED)
    private String transformSubtype;

    @Schema(description = "任一来源标记为歧义", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean ambiguous;

    @Schema(description = "任一来源标记为遮蔽", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean masking;

    @Schema(description = "产生该边的来源", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<LineageSourceBriefRespVO> sources = new ArrayList<>();

}
