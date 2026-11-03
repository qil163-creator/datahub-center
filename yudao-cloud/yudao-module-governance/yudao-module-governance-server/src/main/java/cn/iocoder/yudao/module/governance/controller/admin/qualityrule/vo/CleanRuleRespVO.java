package cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 清洗规则 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 清洗规则 Response VO")
@Data
public class CleanRuleRespVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "规则名称", example = "去除字段空格")
    private String name;

    @Schema(description = "清洗规则类目编号", example = "5")
    private Long categoryId;

    @Schema(description = "清洗规则类目名称", example = "格式标准化类")
    private String categoryName;

    @Schema(description = "规则级别：1-字段级 2-表级", example = "1")
    private Integer ruleLevel;

    @Schema(description = "规则编码", example = "009")
    private String code;

    @Schema(description = "策略标识", example = "REMOVE_WHITESPACE")
    private String strategyKey;

    @Schema(description = "持久化的函数参数对象，不含样例字段绑定")
    private Map<String, Object> config;

    @Schema(description = "是否已接入质量引擎", example = "true")
    private Boolean engineSupported;

    @Schema(description = "是否支持保持行数及字段集合的逐行清洗", example = "true")
    private Boolean rowTransformSupported;

    @Schema(description = "字段绑定方式：SINGLE-单字段 MULTIPLE-组合字段 ALL-整行")
    private String columnBinding;

    @Schema(description = "规则描述")
    private String description;

    @Schema(description = "使用场景")
    private String useCase;

    @Schema(description = "示例")
    private String example;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

    @Schema(description = "创建人编号", example = "1")
    private String creator;

    @Schema(description = "创建人名称", example = "芋道")
    private String creatorName;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
