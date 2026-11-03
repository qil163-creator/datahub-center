package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 逻辑模型影响分析 Response VO")
@Data
public class LogicImpactRespVO {

    @Schema(description = "逻辑模型编号", example = "1")
    private Long modelId;

    @Schema(description = "逻辑模型名称", example = "企业信息")
    private String modelName;

    @Schema(description = "关联的逻辑模型关系")
    private List<RelationImpact> relations;

    @Schema(description = "由本模型生成的物理模型")
    private List<PhysicalImpact> physicalModels;

    @Schema(description = "关联关系影响项")
    @Data
    public static class RelationImpact {

        @Schema(description = "关系编号", example = "1")
        private Long relationId;

        @Schema(description = "方向：SOURCE-本模型为源 TARGET-本模型为目标", example = "SOURCE")
        private String direction;

        @Schema(description = "对端模型编号", example = "2")
        private Long otherModelId;

        @Schema(description = "对端模型名称", example = "电子证照")
        private String otherModelName;

        @Schema(description = "源字段", example = "id")
        private String sourceField;

        @Schema(description = "目标字段", example = "enterprise_id")
        private String targetField;

        @Schema(description = "关系类型", example = "ONE_TO_MANY")
        private String relationType;

    }

    @Schema(description = "物理模型影响项")
    @Data
    public static class PhysicalImpact {

        @Schema(description = "物理模型编号", example = "1")
        private Long id;

        @Schema(description = "模型名称", example = "企业信息表")
        private String name;

        @Schema(description = "表名称", example = "dwd_enterprise_info")
        private String tableName;

        @Schema(description = "数据源名称", example = "数仓 PG")
        private String datasourceName;

        @Schema(description = "状态", example = "PUBLISHED")
        private String status;

        @Schema(description = "启用状态：0-停用 1-启用", example = "1")
        private Integer enabled;

    }

}
