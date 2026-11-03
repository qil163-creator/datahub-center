package cn.iocoder.yudao.module.modeling.controller.admin.conceptmodel.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 概念模型定义请求")
public class ConceptModelSaveReqVO {

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{0,63}$")
    private String code;

    @NotNull
    @Positive
    private Long dataDomainId;

    @Positive
    private Long subjectDomainId;

    @Size(max = 2000)
    private String description;

    @NotNull
    @Valid
    private ConceptModelDesignVO design;
}
