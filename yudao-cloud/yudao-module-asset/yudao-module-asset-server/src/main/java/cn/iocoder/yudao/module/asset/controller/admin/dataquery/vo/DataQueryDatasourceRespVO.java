package cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据查询可用数据源 Response VO")
@Data
public class DataQueryDatasourceRespVO {

    @Schema(description = "数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "数据源名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "数据中台主题库")
    private String name;

    @Schema(description = "用途类型（1 业务库 2 中台库）", example = "2")
    private Integer usageType;

    @Schema(description = "数据库产品类型", example = "POSTGRESQL")
    private String productType;

    @Schema(description = "数据库类型图标路径", example = "/static/db/postgresql.png")
    private String iconPath;

}
