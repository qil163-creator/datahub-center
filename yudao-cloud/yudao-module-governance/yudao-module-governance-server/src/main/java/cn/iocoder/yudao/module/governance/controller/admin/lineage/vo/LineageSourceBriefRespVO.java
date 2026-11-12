package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 血缘边所属来源摘要 Response VO")
@Data
public class LineageSourceBriefRespVO {

    @Schema(description = "来源记录编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "来源类型", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sourceType;

    @Schema(description = "来源对象编号（dataforge 内编号）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long sourceId;

    @Schema(description = "来源名称")
    private String sourceName;

    @Schema(description = "解析状态")
    private String status;

}
