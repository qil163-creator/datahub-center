package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 血缘节点；metaTableId/businessName 在查询时按元数据匹配规则关联，未唯一命中时为空。
 */
@Schema(description = "管理后台 - 血缘节点 Response VO")
@Data
public class LineageNodeRespVO {

    @Schema(description = "节点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "节点类型：TABLE/EXTERNAL", requiredMode = Schema.RequiredMode.REQUIRED)
    private String nodeType;

    @Schema(description = "数据源编号（TABLE）")
    private Long datasourceId;

    @Schema(description = "schema 名（TABLE，可空）")
    private String schemaName;

    @Schema(description = "表名（TABLE）")
    private String tableName;

    @Schema(description = "外部数据集种类（EXTERNAL）：KAFKA/FILE/HDFS/API/MONGODB/OTHER")
    private String externalKind;

    @Schema(description = "外部数据集标识（EXTERNAL）")
    private String externalKey;

    @Schema(description = "展示名")
    private String displayName;

    @Schema(description = "关联的元数据表编号（查询时匹配，可空）")
    private Long metaTableId;

    @Schema(description = "元数据表业务名称（可空）")
    private String businessName;

}
