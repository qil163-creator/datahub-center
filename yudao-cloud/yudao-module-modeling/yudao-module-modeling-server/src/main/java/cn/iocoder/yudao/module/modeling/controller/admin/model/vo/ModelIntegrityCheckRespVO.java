package cn.iocoder.yudao.module.modeling.controller.admin.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "模型归属完整性检测（只读）")
@Data
public class ModelIntegrityCheckRespVO {

    @Schema(description = "规则版本；归属检查不替代字段标准或实库部署门禁")
    private String ruleVersion = "modeling-ownership-v2";

    @Schema(description = "结构化归属问题")
    private List<Issue> issues;

    @Data
    public static class Issue {

        private String ruleCode;

        private String field;

        private String severity = "BLOCK";

        private String message;
    }

    @Schema(description = "归属是否完备；仅复用发布的归属检查，不包含字段、类型配置和数据标准")
    private Boolean complete;

    @Schema(description = "缺失项：LAYER/TABLE_TYPE/DATA_DOMAIN/SUBJECT_DOMAIN")
    private List<String> missingItems;

    @Schema(description = "按完备规则推导的 classificationStatus")
    private String classificationStatus;

    @Schema(description = "分层编码")
    private String layerCode;

    @Schema(description = "是否按 ODS 规则判定")
    private Boolean odsRule;

}
