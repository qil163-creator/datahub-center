package cn.iocoder.yudao.module.governance.controller.admin.permission.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据权限表节点 Response VO")
@Data
public class SafetyPermissionTableRespVO {

    @Schema(description = "表名", example = "ods_user")
    private String tableName;

    private String schemaName;

    private String tableReference;

    @Schema(description = "表注释", example = "用户表")
    private String remarks;

    @Schema(description = "是否视图", example = "false")
    private Boolean viewTable;

}
