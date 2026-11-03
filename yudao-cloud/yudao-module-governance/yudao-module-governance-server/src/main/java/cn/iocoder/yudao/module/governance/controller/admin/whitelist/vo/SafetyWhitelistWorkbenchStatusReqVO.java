package cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SafetyWhitelistWorkbenchStatusReqVO extends SafetyWhitelistWorkbenchVersionReqVO {

    @NotNull
    @Min(0)
    @Max(1)
    private Short status;
}
