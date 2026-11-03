package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 数仓分层表前缀修改预览：按候选前缀计算分层下各物理模型的候选表名，并与当前表名比对。
 *
 * 纯只读预览，不改名、不写库；真正的前缀变更仍由更新接口按引用守卫与数据库触发器把关。
 *
 * @author Li
 */
@Schema(description = "管理后台 - 数仓分层表前缀修改预览 Response VO")
@Data
public class DwLayerPrefixPreviewRespVO {

    @Schema(description = "分层编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "6")
    private Long layerId;

    @Schema(description = "分层编码，分类节点为空", example = "DWD")
    private String layerCode;

    @Schema(description = "分层名称", example = "明细数据层")
    private String layerName;

    @Schema(description = "节点类型：CATEGORY分类节点/LAYER实际分层节点", example = "LAYER")
    private String nodeType;

    @Schema(description = "是否适用前缀预览：仅实际分层节点适用，分类节点为 false 并在 reason 说明",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean applicable;

    @Schema(description = "不适用或被阻断的原因文案，无需说明时为空")
    private String reason;

    @Schema(description = "当前表前缀（已归一化为小写），无前缀时为空串", example = "dwd_")
    private String currentPrefix;

    @Schema(description = "候选表前缀（已归一化为小写），空串表示去掉前缀", example = "dwd_v2_")
    private String candidatePrefix;

    @Schema(description = "候选前缀与当前前缀是否不同", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean prefixChanged;

    @Schema(description = "前缀变化且分层已被模型引用时为 true：当前策略下该变更会被引用守卫与数据库触发器拒绝",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean blocked;

    @Schema(description = "引用该分层的逻辑模型数量", example = "2")
    private Long logicCount;

    @Schema(description = "引用该分层的物理模型数量", example = "3")
    private Long physicalCount;

    @Schema(description = "物理模型超过 100 条时为 true，items 只包含前 100 条",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean truncated;

    @Schema(description = "物理模型的候选表名明细（只含有表名的物理模型）")
    private List<Item> items;

    @Schema(description = "管理后台 - 数仓分层表前缀修改预览明细")
    @Data
    public static class Item {

        @Schema(description = "物理模型编号", example = "1")
        private Long modelId;

        @Schema(description = "物理模型名称", example = "客户明细")
        private String modelName;

        @Schema(description = "物理模型编码", example = "DWD_CUSTOMER")
        private String code;

        @Schema(description = "设计状态：DRAFT/PUBLISHED", example = "PUBLISHED")
        private String status;

        @Schema(description = "部署状态：NOT_DEPLOYED/RUNNING/SUCCEEDED/FAILED/UNKNOWN", example = "SUCCEEDED")
        private String deployStatus;

        @Schema(description = "创建方式：NEW/GENERATE/REVERSE/COPY", example = "NEW")
        private String createMode;

        @Schema(description = "当前完整表名", example = "dwd_customer")
        private String currentTableName;

        @Schema(description = "按候选前缀计算出的候选表名，计算失败（如超长、英文名非法）时为空")
        private String candidateTableName;

        @Schema(description = "候选表名与当前表名是否不同；逆向接入模型与计算失败时恒为 false",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean changed;

        @Schema(description = "说明：逆向接入提示或候选名计算失败原因")
        private String note;

    }

}
