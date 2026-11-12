package cn.iocoder.yudao.module.asset.controller.admin.dataexport.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - 提交数据导出申请 Request VO
 */
@Schema(description = "管理后台 - 提交数据导出申请 Request VO")
@Data
public class DataExportSubmitReqVO {

    @Schema(description = "数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "请选择数据源")
    private Long datasourceId;

    @Schema(description = "查询 SQL（单条受限 SELECT）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "查询 SQL 不能为空")
    @Size(max = 20000, message = "查询 SQL 过长")
    private String sql;

    @Schema(description = "导出行数上限（1-1000）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1000")
    @NotNull(message = "导出行数不能为空")
    @Min(value = 1, message = "导出行数不能小于 1")
    @Max(value = 1000, message = "导出行数不能大于 1000")
    private Integer limit;

    @Schema(description = "申请原因", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "申请原因不能为空")
    @Size(max = 500, message = "申请原因不能超过 500 个字符")
    private String reason;
}
