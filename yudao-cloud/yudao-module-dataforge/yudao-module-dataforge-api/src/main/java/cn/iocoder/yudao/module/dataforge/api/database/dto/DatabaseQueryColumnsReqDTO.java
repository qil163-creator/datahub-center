package cn.iocoder.yudao.module.dataforge.api.database.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "RPC 服务 - 精确表列读取；调用方先完成领域授权")
public class DatabaseQueryColumnsReqDTO {

    @NotNull
    private Long databaseId;

    @NotBlank
    @Size(max = 256)
    private String schemaName;

    @NotBlank
    @Size(max = 256)
    private String tableName;

    @Size(max = 128)
    @Schema(description = "可选期望的原生表代际 ID；存在时不得忽略，TiDB 权限准备时必须给出")
    private String expectedObjectId;

    @Size(max = 2000)
    @Schema(description = "物理列投影；null 表示独立授权下全列，空集合必须拒绝")
    private List<@NotBlank String> columns;
}
