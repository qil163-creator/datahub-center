package cn.iocoder.yudao.module.dataforge.controller.admin.database.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 数据源表属性 Response VO")
@Data
public class DatabaseTableMetaRespVO {

    @Schema(description = "schema")
    private String schemaName;

    @Schema(description = "表名", example = "t_user")
    private String tableName;

    @Schema(description = "表类型", example = "BASE TABLE")
    private String tableType;

    @Schema(description = "表注释")
    private String remarks;

    @Schema(description = "主键字段")
    private List<String> primaryKeys;

    @Schema(description = "建表语句")
    private String createSql;

    @Schema(description = "字段列表")
    private List<DatabaseTableColumnRespVO> columns;

}
