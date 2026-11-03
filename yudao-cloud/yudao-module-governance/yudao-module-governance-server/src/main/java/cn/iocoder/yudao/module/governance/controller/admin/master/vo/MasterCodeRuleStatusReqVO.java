package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - MasterCodeRuleStatusReqVO")
@Data
public class MasterCodeRuleStatusReqVO {

    @NotNull
    private Long id;

    @NotNull
    @Min(0)
    @Max(1)
    private Integer status;
}
