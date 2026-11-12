package cn.iocoder.yudao.module.governance.api.lineage.dto;

import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageContractConstants;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageExternalKindEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageNodeTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 血缘节点引用。
 *
 * <p>TABLE：datasourceId、tableName 必填，schemaName 可空；EXTERNAL：externalKind、externalKey 必填，
 * externalKey 是不含凭据的稳定标识（如 topic 名、去掉用户信息与查询参数的路径/地址）。
 * 节点身份由服务端规范化计算：TABLE 的 schema/表名按小写比较，EXTERNAL 的标识大小写敏感。</p>
 */
@Schema(description = "RPC 服务 - 血缘节点引用")
@Data
public class LineageNodeRefDTO {

    @Schema(description = "节点类型：TABLE/EXTERNAL", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "节点类型不能为空")
    @InEnum(value = LineageNodeTypeEnum.class, message = "节点类型必须是 {value}")
    private String nodeType;

    @Schema(description = "数据源编号（TABLE 必填）")
    @Positive(message = "数据源编号必须为正数")
    private Long datasourceId;

    @Schema(description = "schema 名（可空）")
    @Size(max = LineageContractConstants.MAX_SCHEMA_NAME_LENGTH, message = "schema 名不能超过 128 个字符")
    private String schemaName;

    @Schema(description = "表名（TABLE 必填）")
    @Size(max = LineageContractConstants.MAX_TABLE_NAME_LENGTH, message = "表名不能超过 255 个字符")
    private String tableName;

    @Schema(description = "外部数据集种类（EXTERNAL 必填）：KAFKA/FILE/HDFS/API/MONGODB/OTHER")
    @InEnum(value = LineageExternalKindEnum.class, message = "外部数据集种类必须是 {value}")
    private String externalKind;

    @Schema(description = "外部数据集标识（EXTERNAL 必填，不得含凭据）")
    @Size(max = LineageContractConstants.MAX_EXTERNAL_KEY_LENGTH, message = "外部数据集标识不能超过 512 个字符")
    private String externalKey;

    @Schema(description = "展示名（可空）")
    @Size(max = LineageContractConstants.MAX_DISPLAY_NAME_LENGTH, message = "节点展示名不能超过 255 个字符")
    private String displayName;

    /**
     * 构造 TABLE 节点引用。
     */
    public static LineageNodeRefDTO table(Long datasourceId, String schemaName, String tableName) {
        LineageNodeRefDTO ref = new LineageNodeRefDTO();
        ref.setNodeType(LineageNodeTypeEnum.TABLE.name());
        ref.setDatasourceId(datasourceId);
        ref.setSchemaName(schemaName);
        ref.setTableName(tableName);
        return ref;
    }

    /**
     * 构造 EXTERNAL 节点引用。
     */
    public static LineageNodeRefDTO external(String externalKind, String externalKey) {
        LineageNodeRefDTO ref = new LineageNodeRefDTO();
        ref.setNodeType(LineageNodeTypeEnum.EXTERNAL.name());
        ref.setExternalKind(externalKind);
        ref.setExternalKey(externalKey);
        return ref;
    }

}
