package cn.iocoder.yudao.module.dataforge.api.database.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "RPC 服务 - 受限 SELECT 解析结果")
@Data
public class RestrictedSelectRespDTO {

    private String schemaName;

    private String tableName;

    @Schema(description = "可选原生表代际 ID，字符串传输；TiDB 精确权限必须复验，不代表授权")
    private String relationObjectId;

    @Schema(description = "已固定物理 schema/table 的 SQL；消费方必须执行此 SQL，不得改回原始未限定 SQL")
    private String normalizedSql;

    private Boolean wildcard;

    private List<String> sourceColumns = new ArrayList<>();
}
