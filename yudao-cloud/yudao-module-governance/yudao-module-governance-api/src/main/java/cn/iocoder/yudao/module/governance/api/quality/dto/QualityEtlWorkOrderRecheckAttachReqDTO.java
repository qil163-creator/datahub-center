package cn.iocoder.yudao.module.governance.api.quality.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 仅关联已持久化的 Governance 批次运行编号；绝不接受调用方提供的 SQL 范围或结果摘要。 */
@Data
public class QualityEtlWorkOrderRecheckAttachReqDTO {

    @NotNull
    @Positive
    private Long executionUserId;

    @NotNull
    @Positive
    private Long workOrderId;

    @NotNull
    @Positive
    private Long submissionId;

    @NotBlank
    @Size(max = 64)
    private String requestId;

    @NotEmpty
    @Size(max = 32)
    private List<@NotNull @Positive Long> targetBatchIds;
}
