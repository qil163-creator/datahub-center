package cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo;

import lombok.Data;

@Data
public class StandardCodeMappingSaveReqVO {

    private Long id;

    @jakarta.validation.constraints.NotNull
    private Integer codeSetId;

    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max = 100)
    private String originalValue;

    @jakarta.validation.constraints.NotNull
    private Integer codeItemId;

    private Long revision;
}
