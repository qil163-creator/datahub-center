package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 数仓分层新增/修改 Request VO")
@Data
public class DwLayerSaveReqVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "20538")
    private Long id;

    @Schema(description = "父级ID，0表示一级分类节点", requiredMode = Schema.RequiredMode.REQUIRED, example = "18408")
    @NotNull(message = "父级ID，0表示一级分类节点不能为空")
    private Long parentId;

    @Schema(description = "祖级列表，如 0 或 0,1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "祖级列表，如 0 或 0,1不能为空")
    private String ancestors;

    @Schema(description = "层级：1分类节点，2实际分层", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "层级：1分类节点，2实际分层不能为空")
    private Short levelNo;

    @Schema(description = "分层名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "芋艿")
    @NotEmpty(message = "分层名称不能为空")
    private String layerName;

    @Schema(description = "分层编码：ODS/DIM/DWD/DWS/ADS，分类节点为空")
    private String layerCode;

    @Schema(description = "节点类型：CATEGORY分类节点/LAYER实际分层节点", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotEmpty(message = "节点类型：CATEGORY分类节点/LAYER实际分层节点不能为空")
    private String nodeType;

    @Schema(description = "默认表前缀，仅实际分层节点填写")
    private String tablePrefix;

    @Schema(description = "默认表类型：SOURCE贴源/DETAIL明细/DIMENSION维度/SUMMARY汇总/APPLICATION应用", example = "2")
    private String defaultTableType;

    @Schema(description = "负责人ID", example = "13001")
    private Long ownerUserId;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "排序不能为空")
    private Integer sortOrder;

    @Schema(description = "是否内置：0否 1是，内置节点不允许删除", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "是否内置：0否 1是，内置节点不允许删除不能为空")
    private Short isBuiltin;

    @Schema(description = "状态：0停用 1启用", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "状态：0停用 1启用不能为空")
    private Short status;

    @Schema(description = "说明", example = "随便")
    private String description;

    @Schema(description = "备注", example = "你说的对")
    private String remark;

}