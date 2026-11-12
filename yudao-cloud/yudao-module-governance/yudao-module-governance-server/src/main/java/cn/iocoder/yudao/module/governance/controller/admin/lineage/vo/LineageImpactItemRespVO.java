package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "管理后台 - 下游影响项 Response VO")
@Data
public class LineageImpactItemRespVO {

    @Schema(description = "受影响节点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long nodeId;

    @Schema(description = "受影响字段名；表级分析时为空")
    private String column;

    @Schema(description = "距起点的层数（从 1 开始）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer depth;

    @Schema(description = "首次触达该项的边所属来源", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<LineageSourceBriefRespVO> sources = new ArrayList<>();

    @Schema(description = "节点信息", requiredMode = Schema.RequiredMode.REQUIRED)
    private LineageNodeRespVO node;

}
