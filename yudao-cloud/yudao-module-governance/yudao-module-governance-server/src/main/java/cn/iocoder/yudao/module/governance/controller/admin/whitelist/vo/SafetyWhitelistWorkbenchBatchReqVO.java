package cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

import lombok.Data;

@Data
public class SafetyWhitelistWorkbenchBatchReqVO {

    @NotNull
    @Size(min = 1, max = 100)
    private List<@NotNull @Valid SafetyWhitelistWorkbenchVersionReqVO> items;
}
