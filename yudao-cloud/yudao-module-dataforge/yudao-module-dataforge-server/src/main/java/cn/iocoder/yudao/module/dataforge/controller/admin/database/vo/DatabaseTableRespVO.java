package cn.iocoder.yudao.module.dataforge.controller.admin.database.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据源下的表信息 Response VO")
@Data
public class DatabaseTableRespVO {

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "t_user")
    private String tableName;

    @Schema(description = "表注释", example = "用户表")
    private String remarks;

    @Schema(description = "是否视图", example = "false")
    private Boolean viewTable;

}
