package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 质检简报生成 Request VO")
@Data
public class QualityBriefGenerateReqVO {

    @Schema(description = "模板编号")
    @NotNull(message = "简报模板不能为空")
    private Long templateId;

    @Schema(description = "简报标题")
    @NotBlank(message = "简报标题不能为空")
    @Size(max = 256)
    private String briefTitle;

    @Schema(description = "期号")
    @Size(max = 32)
    private String issueNo;

    @Schema(description = "评估开始（YYYYMM）")
    @NotBlank(message = "评估开始周期不能为空")
    private String assessStart;

    @Schema(description = "评估结束（YYYYMM）")
    @NotBlank(message = "评估结束周期不能为空")
    private String assessEnd;

}
