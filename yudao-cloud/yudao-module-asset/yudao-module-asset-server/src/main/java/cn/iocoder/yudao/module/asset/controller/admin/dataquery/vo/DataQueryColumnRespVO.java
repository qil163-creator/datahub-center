package cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据查询表字段 Response VO")
@Data
public class DataQueryColumnRespVO {

    @Schema(description = "字段名", requiredMode = Schema.RequiredMode.REQUIRED, example = "station_id")
    private String columnName;

    @Schema(description = "数据库类型名", example = "bigint")
    private String typeName;

}
