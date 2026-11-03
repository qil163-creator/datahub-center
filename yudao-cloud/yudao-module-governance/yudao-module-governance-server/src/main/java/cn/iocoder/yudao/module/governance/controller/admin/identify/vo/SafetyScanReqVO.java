package cn.iocoder.yudao.module.governance.controller.admin.identify.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 敏感数据扫描执行 Request VO")
@Data
public class SafetyScanReqVO {

    @Schema(description = "数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "数据源不能为空")
    private Long databaseId;

    @Schema(description = "扫描表名列表（空=数据源下全部表）")
    private List<String> tableNames;

}
