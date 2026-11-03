package cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据源精简 Response VO（不含连接凭据）")
@Data
public class SupplyDatabaseSimpleRespVO {

    @Schema(description = "数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "数据源名称", example = "人口库")
    private String name;

    @Schema(description = "连接类型模板编号；同一数据库产品可对应多个模板", example = "3")
    private Long databaseType;

    @Schema(description = "连接类型名称", example = "Oracle SID")
    private String databaseTypeName;

    @Schema(description = "连接分类", example = "RELATIONAL")
    private String categoryCode;

    @Schema(description = "产品类型", example = "POSTGRESQL")
    private String productType;

    @Schema(description = "图标路径")
    private String iconPath;
}
