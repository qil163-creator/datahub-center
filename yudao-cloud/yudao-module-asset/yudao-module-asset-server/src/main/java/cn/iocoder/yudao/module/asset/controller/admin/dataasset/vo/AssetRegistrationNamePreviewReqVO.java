package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/** 来源名称由真实候选提供，不接受客户端伪造提供方表名。 */
@Data
public class AssetRegistrationNamePreviewReqVO {

    @NotNull
    @Positive
    private Long candidateId;

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
}
