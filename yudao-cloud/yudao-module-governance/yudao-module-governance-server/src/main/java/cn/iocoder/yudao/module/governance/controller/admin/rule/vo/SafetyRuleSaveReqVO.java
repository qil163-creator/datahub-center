package cn.iocoder.yudao.module.governance.controller.admin.rule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 脱敏规则（主表）；参数演进由版本表承载新增/修改 Request VO")
@Data
public class SafetyRuleSaveReqVO {

    @Schema(description = "脱敏规则编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "30410")
    private Long id;

    @Schema(description = "可选的期望规则版本；分类配置入口须携带，普通规则管理沿用原协议")
    @Positive
    private Long expectedVersionId;

    @Schema(description = "可选的期望规则元信息摘要；分类配置入口须携带，客户端只回传不解析")
    private String expectedRevisionToken;

    @Schema(description = "规则名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "王五")
    @NotEmpty(message = "规则名称不能为空")
    private String ruleName;

    @Schema(description = "脱敏方式", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "脱敏方式不能为空")
    private String methodCode;

    @Schema(description = "规则来源", example = "CUSTOM")
    private String sourceType;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "状态不能为空")
    private Short status;

    @Schema(description = "显示顺序", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "显示顺序不能为空")
    private Integer sort;

    @Schema(description = "规则描述", example = "你猜")
    private String description;

    @Schema(description = "脱敏参数，按脱敏方式约定结构。如 MASK：{\"maskStart\":3,\"maskEnd\":8,\"maskChar\":\"*\"}；RANDOM_OFFSET：{\"maxOffsetDigits\":3,\"allowNegative\":true}")
    private Map<String, Object> config;

    @Schema(description = "替换明细列表，仅内容替换（REPLACE/CONTENT_REPLACE）方式使用")
    @Valid
    private List<SafetyReplaceItemVO> replaceItems;

}
