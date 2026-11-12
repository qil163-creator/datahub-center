package cn.iocoder.yudao.module.governance.api.metadata.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 按数据源、schema、表名定位元数据表的键。
 */
@Schema(description = "RPC 服务 - 元数据表定位键")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetadataTableKeyDTO {

    @Schema(description = "数据源编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "数据源编号不能为空")
    private Long datasourceId;

    @Schema(description = "schema 名；为空时仅在该数据源下表名唯一时命中")
    @Size(max = 128, message = "schema 名不能超过 128 个字符")
    private String schemaName;

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "表名不能为空")
    @Size(max = 255, message = "表名不能超过 255 个字符")
    private String tableName;

}
