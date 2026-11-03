package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 标准数据元设计态落标（模型字段）Response VO")
@Data
public class StandardElementModelFieldRespVO {

    @Schema(description = "物理模型编号", example = "1")
    private Long modelId;

    @Schema(description = "物理模型名称", example = "人员信息模型")
    private String modelName;

    private String description;
    private String dataDomainName;
    private String subjectDomainName;
    private String sourceInfo;
    private String creator;
    private java.time.LocalDateTime createTime;
    private Integer enabled;
    private String tableType;

    @Schema(description = "物理表名", example = "t_person")
    private String tableName;

    @Schema(description = "当前模型状态：DRAFT/PUBLISHED/DISCARDED", example = "PUBLISHED")
    private String modelStatus;

    @Schema(description = "字段稳定标识", example = "3f2b8c1e-…")
    private String fieldUid;

    @Schema(description = "字段名称", example = "person_name")
    private String fieldName;

    @Schema(description = "字段中文名称", example = "姓名")
    private String cnName;

    @Schema(description = "数据类型", example = "varchar")
    private String dataType;

    @Schema(description = "数据长度", example = "64")
    private Integer dataLength;

    @Schema(description = "落标映射状态（fieldUid 缺失的旧快照不建事实）", example = "CONFIRMED")
    private String mappingStatus;

}
