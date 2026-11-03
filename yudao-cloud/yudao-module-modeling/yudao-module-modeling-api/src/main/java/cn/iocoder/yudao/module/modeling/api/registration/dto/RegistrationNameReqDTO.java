package cn.iocoder.yudao.module.modeling.api.registration.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/** 真实选择编号由建模服务校验；来源表名和说明由资产服务查询提供方后填入。 */
@Data
public class RegistrationNameReqDTO {

    @Positive
    private Long dataDomainId;

    @Positive
    private Long subjectDomainId;

    @Positive
    private Long entityId;

    @Size(max = 32)
    private List<@NotNull @Positive Long> granularityEntityIds;

    @NotBlank
    @Pattern(regexp = "ODS|DIM|DWD|DWS|ADS")
    private String tableType;

    @Pattern(regexp = "1|2|3")
    private String timeEffect;

    @NotBlank
    @Pattern(regexp = "UPPER|LOWER|MIXED")
    private String namingCase;

    @NotBlank
    @Size(max = 256)
    private String sourceTableName;

    @Size(max = 2000)
    private String sourceTableComment;
}
