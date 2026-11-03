package cn.iocoder.yudao.module.dataforge.api.database.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "RPC 服务 - 已固定物理身份的查询表，不代表已授权")
public class DatabaseQueryTableRespDTO {

    private String schemaName;

    private String tableName;

    @Schema(description = "可选原生表代际 ID，字符串传输；不是授权凭证")
    private String relationObjectId;

    @Schema(description = "安全引用的 schema.table，前端原样用于列表、列请求和 SQL 插入")
    private String tableReference;

    @Schema(description = "该关系的固定 SELECT，用于治理权限检查")
    private String normalizedSql;
}
