package cn.iocoder.yudao.module.modeling.controller.admin.businesscondition.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 业务限定保存请求；计算逻辑只存储，不执行")
public class BusinessConditionSaveReqVO {

    @Positive
    private Long id;

    @PositiveOrZero
    private Long expectedRevision;

    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "[a-zA-Z][a-zA-Z0-9_]*")
    private String code;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotNull
    @Positive
    private Long dataDomainId;

    @Positive
    private Long subjectDomainId;

    @NotNull
    @Positive
    private Long entityId;

    @Pattern(regexp = "DRAFT|ACTIVE")
    private String status;

    @Positive
    private Long ownerUserId;

    @Size(max = 2000)
    private String description;

    @Size(max = 100)
    private String businessEntity;

    @Size(max = 100)
    private String dataFreshness;

    @NotNull
    @Positive
    private Long sourceModelId;


    @NotBlank
    @Pattern(regexp = "BASIC|COMPOSITE")
    private String configType;

    @NotBlank
    @Pattern(regexp = "UNIFIED|SEGMENTED")
    private String logicMode;

    @NotBlank
    @Size(max = 4000)
    private String expression;
}
