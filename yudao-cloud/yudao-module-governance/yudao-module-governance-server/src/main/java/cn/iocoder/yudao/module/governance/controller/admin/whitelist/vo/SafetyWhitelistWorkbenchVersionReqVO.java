package cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class SafetyWhitelistWorkbenchVersionReqVO {

    @NotNull
    @Positive
    private Long id;

    @NotNull
    @Positive
    private Long revision;
}
