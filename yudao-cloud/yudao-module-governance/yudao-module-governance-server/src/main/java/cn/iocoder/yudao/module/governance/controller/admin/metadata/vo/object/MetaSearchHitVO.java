package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 元数据检索命中项（表或字段）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据检索命中项")
@Data
public class MetaSearchHitVO {

    @Schema(description = "对象类型：2-表 3-字段", example = "2")
    private Integer objectType;

    @Schema(description = "对象编号", example = "100")
    private Long objectId;

    @Schema(description = "表编号", example = "100")
    private Long tableId;

    @Schema(description = "库名", example = "ods")
    private String dbName;

    @Schema(description = "表名", example = "ods_person")
    private String tableName;

    @Schema(description = "字段名（字段命中时有值）", example = "id_card")
    private String columnName;

    @Schema(description = "业务名称", example = "身份证号")
    private String businessName;

    @Schema(description = "展示注释")
    private String comment;

    @Schema(description = "命中字段说明", example = "表名")
    private String hitField;

    @Schema(description = "结构版本", example = "v1.0.0")
    private String structVersion;

    @Schema(description = "发布状态", example = "1")
    private Integer publishStatus;

    @Schema(description = "来源系统名称")
    private String sourceSystemName;

}
