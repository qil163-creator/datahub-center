package cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 数据查询执行 Request VO")
@Data
public class DataQueryExecuteReqVO {

    @Schema(description = "数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "请选择数据源")
    private Long datasourceId;

    @Schema(description = "查询 SQL（单条 SELECT / WITH 语句）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "查询 SQL 不能为空")
    @Size(max = 20000, message = "查询 SQL 过长")
    private String sql;

    @Schema(description = "最大返回行数（1-1000，默认 100）", example = "100")
    @Min(value = 1, message = "返回行数不能小于 1")
    @Max(value = 1000, message = "返回行数不能大于 1000")
    private Integer limit = 100;

}
