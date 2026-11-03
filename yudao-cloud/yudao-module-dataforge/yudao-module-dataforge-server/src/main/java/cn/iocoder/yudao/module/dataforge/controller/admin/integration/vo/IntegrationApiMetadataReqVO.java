package cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.Map;

/** 当前任务获授权的无认证 GET 接口字段采样，不接收运行秘密引用。 */
@Data
public class IntegrationApiMetadataReqVO {
    @NotNull @Positive private Long id;
    @NotNull @Min(0) private Integer revision;
    @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,99}") private String resourceBindingId;
    @NotNull @Size(max = 32) private Map<String, Object> apiConfig;
}
