package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "管理后台 - 问题行处置；不修改源数据")
public class QualityIssueRowActionReqVO {

    @NotNull
    @Positive
    private Long auditLogId;

    @NotBlank
    @Pattern(regexp = "IGNORE|REMARK")
    private String action;

    @NotEmpty
    @Size(max = 100)
    @Valid
    private List<Row> rows;

    @Size(max = 500)
    private String remark;

    @Data
    public static class Row {

        @NotBlank
        @Pattern(regexp = "[a-f0-9]{64}")
        private String rowKey;

        @NotNull
        @Min(0)
        private Integer revision;
    }
}
