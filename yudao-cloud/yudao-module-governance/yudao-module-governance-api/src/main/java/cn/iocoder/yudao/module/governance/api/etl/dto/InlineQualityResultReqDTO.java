package cn.iocoder.yudao.module.governance.api.etl.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

@Data
public class InlineQualityResultReqDTO {

    @NotNull
    private Long executionUserId;

    @NotBlank
    @Size(max = 64)
    private String runId;

    @NotBlank
    @Size(max = 128)
    private String nodeId;

    @NotBlank
    @Size(max = 128)
    private String batchId;

    @NotBlank
    @Size(min = 64, max = 64)
    private String planHash;

    @NotBlank
    @Size(max = 180)
    private String idempotencyKey;

    @NotNull
    @Min(0)
    private Long totalCount;

    @NotNull
    @Min(0)
    private Long passCount;

    @NotNull
    @Min(0)
    private Long warnCount;

    @NotNull
    @Min(0)
    private Long rejectCount;

    @Size(max = 1000)
    private List<@NotBlank @Size(max = 512) String> issueReferences;

    /** 可选的完整数据集合标识凭据；关联受管 ETL 目标批次时必填。 */
    @Size(max = 16)
    private List<@NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*") String> identityFields;

    @Pattern(regexp = "[a-f0-9]{64}")
    private String identityHash;
}
