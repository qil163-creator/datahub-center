package cn.iocoder.yudao.module.governance.api.quality.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class QualityEtlWorkOrderReplayDescribeReqDTO {

    @NotNull
    @Positive
    private Long executionUserId;

    @NotNull
    @Positive
    private Long workOrderId;

    @NotNull
    @Positive
    private Long submissionId;
}
