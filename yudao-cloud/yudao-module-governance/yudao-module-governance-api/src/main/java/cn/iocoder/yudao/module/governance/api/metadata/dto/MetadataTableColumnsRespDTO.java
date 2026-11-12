package cn.iocoder.yudao.module.governance.api.metadata.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表当前有效字段名；与请求键一一对应、顺序一致，键原样回显。
 */
@Schema(description = "RPC 服务 - 表字段名")
@Data
public class MetadataTableColumnsRespDTO {

    @Schema(description = "数据源编号（回显请求）")
    private Long datasourceId;

    @Schema(description = "schema 名（回显请求）")
    private String schemaName;

    @Schema(description = "表名（回显请求）")
    private String tableName;

    @Schema(description = "是否唯一命中元数据表；未命中或存在歧义时为 false")
    private Boolean found;

    @Schema(description = "按字段序号排序的当前有效字段名；未命中时为空列表")
    private List<String> columnNames = new ArrayList<>();

}
