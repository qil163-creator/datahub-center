package cn.iocoder.yudao.module.governance.api.standard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "RPC 服务 - 当前标准落标字段")
public class StandardAssociationColumnRespDTO {

    private Long tableId;

    private String tableName;

    private Long columnId;

    private String columnName;
}
