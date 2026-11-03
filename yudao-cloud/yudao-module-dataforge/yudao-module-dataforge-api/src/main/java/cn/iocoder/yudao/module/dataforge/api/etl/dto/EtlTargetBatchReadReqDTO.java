package cn.iocoder.yudao.module.dataforge.api.etl.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@EqualsAndHashCode(callSuper = true)
public class EtlTargetBatchReadReqDTO extends EtlTargetBatchDescribeReqDTO {

    @NotBlank
    @Pattern(regexp = "[0-9a-f]{64}")
    private String expectedPayloadHash;

    @NotNull
    @Positive
    private Long expectedDatabaseId;

    @NotBlank
    @Size(max = 128)
    private String expectedSchemaName;

    @NotBlank
    @Size(max = 128)
    private String expectedTableName;

    @NotNull
    @Min(0)
    private Long afterRow;

    @NotNull
    @Min(1)
    @Max(200)
    private Integer limit;
}
