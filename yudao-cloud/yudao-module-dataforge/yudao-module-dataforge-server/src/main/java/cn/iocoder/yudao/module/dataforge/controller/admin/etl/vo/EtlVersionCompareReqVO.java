package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class EtlVersionCompareReqVO {

    @NotNull
    private Long sourceVersionId;

    @NotNull
    private Long targetVersionId;
}
