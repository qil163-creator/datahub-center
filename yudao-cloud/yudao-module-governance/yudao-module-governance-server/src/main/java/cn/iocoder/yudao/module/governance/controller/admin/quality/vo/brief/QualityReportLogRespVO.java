package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 报告生成记录 Response VO")
@Data
public class QualityReportLogRespVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "报告周期开始")
    private String reportPeriodStart;

    @Schema(description = "报告周期结束")
    private String reportPeriodEnd;

    @Schema(description = "生成状态：RUNNING/SUCCESS/FAILED")
    private String generateStatus;

    @Schema(description = "生成进度（%）")
    private Integer generateProgress;

    @Schema(description = "生成信息")
    private String generateMsg;

    @Schema(description = "生成人员")
    private String generator;

    @Schema(description = "生成时间")
    private LocalDateTime generateTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
