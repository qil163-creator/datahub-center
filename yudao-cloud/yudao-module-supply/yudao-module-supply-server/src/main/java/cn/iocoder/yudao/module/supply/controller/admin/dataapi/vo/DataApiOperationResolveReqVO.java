package cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 开放写操作人工核查结论；记录证据，不执行或重试SQL")
public class DataApiOperationResolveReqVO {

    @NotNull
    @Positive
    private Long id;

    @NotBlank
    @Pattern(regexp = "CONFIRMED_SUCCEEDED|CONFIRMED_NOT_APPLIED")
    private String decision;

    @NotBlank
    @Size(max = 1000)
    private String reason;

    @NotBlank
    @Size(max = 2000)
    private String evidence;
}
