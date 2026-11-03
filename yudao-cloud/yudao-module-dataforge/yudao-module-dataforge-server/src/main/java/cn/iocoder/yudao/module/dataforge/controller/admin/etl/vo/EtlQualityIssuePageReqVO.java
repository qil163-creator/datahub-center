package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@EqualsAndHashCode(callSuper = true)
public class EtlQualityIssuePageReqVO extends PageParam {

    @NotNull
    private Long batchId;
}
