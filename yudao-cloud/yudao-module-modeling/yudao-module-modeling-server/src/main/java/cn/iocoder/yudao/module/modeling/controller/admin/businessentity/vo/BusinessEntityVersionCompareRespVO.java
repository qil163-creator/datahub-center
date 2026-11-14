package cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.ModelDiffRespVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "管理后台 - 业务实体版本比对 Response VO")
@Data
public class BusinessEntityVersionCompareRespVO {

    @Schema(description = "比对基准的名称", example = "V1")
    private String fromLabel;

    @Schema(description = "比对目标的名称，如 V2 或 当前设计", example = "V2")
    private String toLabel;

    @Schema(description = "实体属性变化")
    private List<ModelDiffRespVO.AttributeChange> attributeChanges = new ArrayList<>();

    @Schema(description = "关系变化")
    private List<RelationChange> relationChanges = new ArrayList<>();

    @Schema(description = "关系变化")
    @Data
    public static class RelationChange {

        @Schema(description = "关系类型：INHERITANCE、ASSOCIATION、PREDECESSOR、PROCESS_EVENT", example = "ASSOCIATION")
        private String relationType;

        @Schema(description = "关系类型名称", example = "关联")
        private String relationLabel;

        @Schema(description = "变化类型：ADDED、REMOVED", example = "ADDED")
        private String changeType;

        @Schema(description = "关联实体编号", example = "3")
        private Long entityId;

        @Schema(description = "关联实体名称", example = "客户")
        private String name;

        @Schema(description = "关联实体编码", example = "customer")
        private String code;
    }
}
