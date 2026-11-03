package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 在线填报-提交 Response VO
 *
 * 校验失败不抛异常，返回结构化问题清单供前端定位单元格
 *
 * @author Li
 */
@Schema(description = "管理后台 - 填报提交 Response VO")
@Data
public class FillSubmitRespVO {

    @Schema(description = "是否提交成功（false = 强校验未通过，实例已回到填报中/整改中）", example = "true")
    private Boolean success;

    @Schema(description = "提交快照编号（成功时返回）", example = "1")
    private Long submissionId;

    @Schema(description = "提交次数", example = "1")
    private Integer attemptNo;

    @Schema(description = "快照行数", example = "120")
    private Integer rowCount;

    @Schema(description = "校验问题清单（失败时返回）")
    private List<Issue> issues;

    @Schema(description = "管理后台 - 校验问题")
    @Data
    public static class Issue {

        @Schema(description = "规则编号", example = "REQUIRED")
        private String ruleCode;

        @Schema(description = "级别：1-错误 2-警告", example = "1")
        private Integer severity;

        @Schema(description = "问题行标识", example = "c-8f2a1b")
        private String rowId;

        @Schema(description = "问题字段码", example = "qty")
        private String fieldCode;

        @Schema(description = "显示行号（从 1 开始）", example = "3")
        private Integer displayRowNo;

        @Schema(description = "问题描述", example = "数量不能为空")
        private String message;

    }

}
