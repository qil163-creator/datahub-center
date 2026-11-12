package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Schema(description = "管理后台 - 血缘上报来源 Response VO")
@Data
public class LineageSourceRespVO {

    @Schema(description = "来源记录编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "来源类型", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sourceType;

    @Schema(description = "来源对象编号（dataforge 内编号）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long sourceId;

    @Schema(description = "来源名称")
    private String sourceName;

    @Schema(description = "已接受的来源修订号")
    private Long sourceRevision;

    @Schema(description = "已接受的解析器版本")
    private Integer analyzerVersion;

    @Schema(description = "解析状态")
    private String status;

    @Schema(description = "解析问题")
    private List<LineageIssueRespVO> issues = new ArrayList<>();

    @Schema(description = "当前表级边数量")
    private Integer tableEdgeCount;

    @Schema(description = "当前字段级边数量")
    private Integer columnEdgeCount;

    @Schema(description = "最近一次接受上报的时间（默认毫秒时间戳）")
    private LocalDateTime reportedTime;

}
