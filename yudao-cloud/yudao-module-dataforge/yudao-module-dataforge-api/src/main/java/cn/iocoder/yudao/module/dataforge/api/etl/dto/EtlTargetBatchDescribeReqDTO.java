package cn.iocoder.yudao.module.dataforge.api.etl.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** executionUserId 仅由认证服务从其领域授权中选取，不能接受外部调用者任意覆盖。 */
@Data
public class EtlTargetBatchDescribeReqDTO {

    @NotNull
    @Positive
    private Long batchId;

    @NotNull
    @Positive
    private Long executionUserId;
}
