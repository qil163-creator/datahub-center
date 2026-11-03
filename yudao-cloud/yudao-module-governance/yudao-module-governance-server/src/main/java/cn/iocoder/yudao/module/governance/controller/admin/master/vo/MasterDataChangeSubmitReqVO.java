package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MasterDataChangeSubmitReqVO extends MasterDataRowsReqVO {

    @NotBlank
    @Pattern(regexp = "CREATE|UPDATE|DELETE")
    private String operation;
}
