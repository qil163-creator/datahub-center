package cn.iocoder.yudao.module.governance.api.permission.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "已授权且绑定物理对象的 SQL；消费方只能执行本返回 SQL，保留原绑定顺序")
public class SafetyWritePermissionRespDTO {

    private String sql;

    private String operation;

    private String schemaName;

    private String tableName;

    private String configurationRevision;

    private Integer parameterCount;
}
