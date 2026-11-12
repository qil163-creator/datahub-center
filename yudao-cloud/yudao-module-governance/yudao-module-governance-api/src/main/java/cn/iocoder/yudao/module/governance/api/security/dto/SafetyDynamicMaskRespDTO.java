package cn.iocoder.yudao.module.governance.api.security.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 动态脱敏结果。被脱敏列的原值不会出现在返回中。
 */
@Schema(description = "RPC 服务 - 动态脱敏结果")
@Data
public class SafetyDynamicMaskRespDTO {

    /** 被脱敏的原因：命中分类规则。 */
    public static final String REASON_RULE = "RULE";
    /** 被脱敏的原因：字段标记敏感但未分类，失败关闭。 */
    public static final String REASON_UNCLASSIFIED = "UNCLASSIFIED";
    /** 被脱敏的原因：输出列来源无法追溯且涉及敏感表，失败关闭。 */
    public static final String REASON_UNTRACEABLE = "UNTRACEABLE";
    /** 被脱敏的原因：豁免或规则无法核验，失败关闭。 */
    public static final String REASON_UNVERIFIED = "UNVERIFIED";

    @Schema(description = "场景是否启用动态脱敏；未启用时原样返回")
    private Boolean enabled;

    @Schema(description = "处理后的结果行")
    private List<Map<String, Object>> rows = new ArrayList<>();

    @Schema(description = "被脱敏的列")
    private List<MaskedColumn> maskedColumns = new ArrayList<>();

    @Schema(description = "脱敏策略指纹（用于审计复核）；场景未启用或失败关闭时为空")
    private String policyFingerprint;

    /** 被脱敏的列。 */
    @Schema(description = "被脱敏的列")
    @Data
    public static class MaskedColumn {

        @Schema(description = "输出列名")
        private String outputName;

        @Schema(description = "原因：RULE/UNCLASSIFIED/UNTRACEABLE/UNVERIFIED")
        private String reason;

        @Schema(description = "命中的分类名称")
        private List<String> categoryNames = new ArrayList<>();
    }
}
