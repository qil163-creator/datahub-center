package cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

/** 基于当前任务及管理员显式授权资源读取 WSDL，不包含凭据或操作参数。 */
@Data
public class IntegrationWebServiceMetadataReqVO {
    @NotNull @Positive private Long id;
    @NotNull @Min(0) private Integer revision;
    @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,99}") private String resourceBindingId;
    @NotBlank @Size(max = 2048) private String apiUrl;
}
