package cn.iocoder.yudao.module.governance.controller.admin.security.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理后台 - 动态脱敏策略预览 Response VO
 */
@Schema(description = "管理后台 - 动态脱敏策略预览 Response VO")
@Data
public class SafetyMaskPreviewRespVO {

    @Schema(description = "场景是否启用")
    private Boolean sceneEnabled;

    @Schema(description = "用户在该场景下是否全局豁免")
    private Boolean globallyExempt;

    @Schema(description = "策略指纹；没有分类列时为空")
    private String policyFingerprint;

    @Schema(description = "各列处理方式")
    private List<Column> columns = new ArrayList<>();

    /** 列处理方式。 */
    @Schema(description = "列处理方式")
    @Data
    public static class Column {

        @Schema(description = "列名", example = "phone")
        private String columnName;

        @Schema(description = "是否会被脱敏")
        private Boolean masked;

        @Schema(description = "原因：RULE/UNCLASSIFIED/UNTRACEABLE/UNVERIFIED；不脱敏时为空")
        private String reason;

        @Schema(description = "生效的分类名称")
        private List<String> categoryNames = new ArrayList<>();

        @Schema(description = "示例值的脱敏效果；未提供示例值或不脱敏时为空")
        private String sampleResult;
    }
}
