package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Schema(description = "管理后台 - 主数据域状态 Request VO")
@Data
public class MasterDomainUpdateStatusReqVO {

    @NotNull
    private Long id;

    @NotNull
    @Min(0)
    @Max(1)
    private Integer status;
}
