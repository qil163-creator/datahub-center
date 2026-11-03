package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 逻辑模型关系 Response VO")
@Data
public class LogicRelationRespVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    private Long revision;

    private java.util.List<ModelRelationFieldPair> fieldPairs;

    @Schema(description = "源逻辑模型编号", example = "1")
    private Long sourceModelId;

    @Schema(description = "源模型名称", example = "企业信息")
    private String sourceModelName;

    @Schema(description = "目标逻辑模型编号", example = "2")
    private Long targetModelId;

    @Schema(description = "目标模型名称", example = "电子证照")
    private String targetModelName;

    @Schema(description = "源模型字段名", example = "id")
    private String sourceField;

    @Schema(description = "目标模型字段名", example = "enterprise_id")
    private String targetField;

    @Schema(description = "关系名称", example = "企业-证照")
    private String name;

    @Schema(description = "关系类型：ONE_TO_ONE/ONE_TO_MANY/MANY_TO_MANY", example = "ONE_TO_MANY")
    private String relationType;

    @Schema(description = "描述")
    private String description;

}
