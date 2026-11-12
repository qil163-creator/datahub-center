package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "管理后台 - 下游影响分析 Response VO")
@Data
public class LineageImpactRespVO {

    @Schema(description = "下游影响项（按层数、节点编号、字段名排序，最多 500 条，不含起点）", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<LineageImpactItemRespVO> items = new ArrayList<>();

    @Schema(description = "是否因数量上限被截断", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean truncated;

}
