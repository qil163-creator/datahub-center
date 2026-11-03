package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "管理后台 - 从 ETL 目标创建独立 SQL 质量任务；不触发执行")
public class QualityEtlTaskCreateReqVO {

    @NotBlank
    @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private String requestId;

    @NotBlank
    @Size(max = 100)
    private String taskName;

    @NotNull
    @Positive
    private Long databaseId;

    @NotBlank
    @Size(max = 63)
    private String schemaName;

    @NotBlank
    @Size(max = 512)
    private String tableName;

    @NotEmpty
    @Size(max = 100)
    private List<@NotNull @Positive Long> ruleIds;

    @NotNull
    private Boolean activate = false;
}
