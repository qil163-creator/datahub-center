package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 质检简报 Response VO")
@Data
public class QualityBriefRespVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "简报标题")
    private String briefTitle;

    @Schema(description = "期号")
    private String issueNo;

    @Schema(description = "评估周期")
    private String assessCycle;

    @Schema(description = "评估开始（YYYYMM）")
    private String assessStart;

    @Schema(description = "评估结束（YYYYMM）")
    private String assessEnd;

    @Schema(description = "模板编号")
    private Long templateId;

    @Schema(description = "模板名称")
    private String templateName;

    @Schema(description = "简报状态：DRAFT/PUBLISHED")
    private String briefStatus;

    @Schema(description = "简报内容JSON（渲染后）")
    private String briefContent;

    @Schema(description = "生成时间")
    private LocalDateTime generateTime;

    @Schema(description = "生成人")
    private String generator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
