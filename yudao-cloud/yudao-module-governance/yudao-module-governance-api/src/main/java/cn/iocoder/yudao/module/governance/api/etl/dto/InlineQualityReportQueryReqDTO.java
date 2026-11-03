package cn.iocoder.yudao.module.governance.api.etl.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class InlineQualityReportQueryReqDTO {

    @NotNull
    private Long reportId;
}
