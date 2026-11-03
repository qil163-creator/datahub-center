package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 解析推送 SQL 列 Request VO")
@Data
public class DataPushParseSqlReqVO {

    @Schema(description = "源数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "源数据源不能为空")
    private Long sourceDatabaseId;

    @Schema(description = "自定义 SELECT", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "SQL 不能为空")
    private String sqlText;

}
