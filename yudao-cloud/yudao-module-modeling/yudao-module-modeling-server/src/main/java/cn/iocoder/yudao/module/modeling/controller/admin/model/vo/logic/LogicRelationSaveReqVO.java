package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 逻辑模型关系新增/修改 Request VO")
@Data
public class LogicRelationSaveReqVO {

    @Schema(description = "主键（修改时必填）", example = "1")
    private Long id;

    private Long expectedRevision;

    private Long sourceRevision;

    private Long targetRevision;

    @jakarta.validation.Valid
    private java.util.List<ModelRelationFieldPair> fieldPairs;

    @Schema(description = "源逻辑模型编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "源模型不能为空")
    private Long sourceModelId;

    @Schema(description = "目标逻辑模型编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "目标模型不能为空")
    private Long targetModelId;

    @Schema(description = "源模型字段名", requiredMode = Schema.RequiredMode.REQUIRED, example = "id")
    @NotEmpty(message = "源字段不能为空")
    @Size(max = 128, message = "源字段不能超过 128 个字符")
    private String sourceField;

    @Schema(description = "目标模型字段名", requiredMode = Schema.RequiredMode.REQUIRED, example = "enterprise_id")
    @NotEmpty(message = "目标字段不能为空")
    @Size(max = 128, message = "目标字段不能超过 128 个字符")
    private String targetField;

    @Schema(description = "关系名称", example = "企业-证照")
    @Size(max = 100, message = "关系名称不能超过 100 个字符")
    private String name;

    @Schema(description = "关系类型：ONE_TO_ONE/ONE_TO_MANY/MANY_TO_MANY",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "ONE_TO_MANY")
    @NotEmpty(message = "关系类型不能为空")
    private String relationType;

    @Schema(description = "描述")
    @Size(max = 500, message = "描述不能超过 500 个字符")
    private String description;

}
