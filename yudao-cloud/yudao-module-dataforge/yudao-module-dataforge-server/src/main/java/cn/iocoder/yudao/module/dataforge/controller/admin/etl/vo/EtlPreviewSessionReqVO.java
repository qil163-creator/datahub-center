package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class EtlPreviewSessionReqVO extends EtlPreviewReqVO {

    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Positive
    private Long issuedAt;

    @NotBlank
    @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private String requestId;

    @NotBlank
    @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private String instanceId;
}
