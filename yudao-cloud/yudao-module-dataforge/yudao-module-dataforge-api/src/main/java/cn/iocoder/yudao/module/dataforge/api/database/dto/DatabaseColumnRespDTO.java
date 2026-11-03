package cn.iocoder.yudao.module.dataforge.api.database.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "RPC 服务 - 数据表字段信息")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseColumnRespDTO {

    @Schema(description = "字段名", example = "station_id")
    private String columnName;

    @Schema(description = "数据库类型名", example = "bigint")
    private String typeName;

}
