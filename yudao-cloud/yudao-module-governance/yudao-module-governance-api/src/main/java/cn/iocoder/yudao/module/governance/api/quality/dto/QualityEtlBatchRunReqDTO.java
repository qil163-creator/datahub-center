package cn.iocoder.yudao.module.governance.api.quality.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * DataForge 向 Governance 发出的内部请求，对一个已提交 ETL 批次执行已有质量任务。
 * 清单仅描述数据，绝不携带 SQL 或 WHERE 子句。
 */
@Data
public class QualityEtlBatchRunReqDTO {

    @NotNull
    @Positive
    private Long qualityTaskId;

    @NotNull
    @Positive
    private Long executionUserId;

    @NotNull
    @Positive
    private Long inlineReportId;

    @NotBlank
    @Size(max = 128)
    private String qualityNodeId;

    @NotBlank
    @Size(max = 128)
    private String qualityBatchId;

    @NotNull
    @Valid
    private Manifest manifest;

    @Data
    public static class Manifest {

        @NotNull
        private Integer schemaVersion;

        @NotNull
        @Positive
        private Long targetBatchId;

        @NotNull
        @Positive
        private Long recordId;

        @NotBlank
        @Size(max = 64)
        private String runId;

        /** 受管数据库输出节点，与上述 qualityNodeId 不同。 */
        @NotBlank
        @Size(max = 128)
        private String nodeId;

        @NotNull
        @Positive
        private Long databaseId;

        @NotBlank
        @Size(max = 256)
        private String schemaName;

        @NotBlank
        @Size(max = 256)
        private String tableName;

        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*")
        private String batchColumn;

        /** 稳定批次键，由 DataForge 根据运行与节点生成，包含 64 个十六进制字符。 */
        @NotBlank
        @Pattern(regexp = "[a-f0-9]{64}")
        private String batchValue;

        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*")
        private String rowColumn;

        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*")
        private String statusColumn;

        @NotBlank
        @Pattern(regexp = "[a-f0-9]{64}")
        private String payloadHash;

        @NotNull
        @Positive
        private Long rowCount;

        @NotNull
        @Positive
        @Max(1000000)
        private Long admittedRowCount;

        @NotNull
        private Boolean allowWarnings;

        @NotEmpty
        @Size(max = 16)
        private List<@NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*") String> identityFields;

        @NotBlank
        @Pattern(regexp = "[a-f0-9]{64}")
        private String identityHash;

        @Positive
        private Long replayOfBatchId;

        @Positive
        private Long replayOfRecordId;

        @Positive
        private Long workOrderSubmissionId;

        /**
         * 判断质量ETL批次运行请求是否回放绑定完成。
         */
        @jakarta.validation.constraints.AssertTrue(message = "ETL复检关联字段必须同时提供")
        public boolean isReplayBindingComplete() {
            boolean none = replayOfBatchId == null && replayOfRecordId == null && workOrderSubmissionId == null;
            boolean all = replayOfBatchId != null && replayOfRecordId != null && workOrderSubmissionId != null;
            return none || all;
        }
    }
}
