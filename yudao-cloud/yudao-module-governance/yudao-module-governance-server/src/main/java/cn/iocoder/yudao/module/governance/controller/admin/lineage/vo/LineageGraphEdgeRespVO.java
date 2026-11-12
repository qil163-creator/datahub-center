package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "管理后台 - 表级血缘图边（同一上下游对的多来源合并）Response VO")
@Data
public class LineageGraphEdgeRespVO {

    @Schema(description = "上游节点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long upstreamNodeId;

    @Schema(description = "下游节点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long downstreamNodeId;

    @Schema(description = "边来源集合：PARSED/MANUAL/RUNTIME", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> origins = new ArrayList<>();

    @Schema(description = "产生该边的来源", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<LineageSourceBriefRespVO> sources = new ArrayList<>();

}
