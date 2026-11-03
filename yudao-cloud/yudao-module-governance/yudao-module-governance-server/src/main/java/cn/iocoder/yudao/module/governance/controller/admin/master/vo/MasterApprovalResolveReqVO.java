package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - MasterApprovalResolveReqVO")
@Data
public class MasterApprovalResolveReqVO {

    @NotNull
    private Long id;

    @NotBlank
    @Pattern(regexp = "APPLIED|NO_EFFECT")
    private String outcome;

    @NotBlank
    @Size(max = 500)
    private String reason;

    @NotBlank
    @Size(max = 200)
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_./:-]{0,199}")
    private String evidenceRef;
}
