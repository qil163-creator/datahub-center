package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import cn.iocoder.yudao.framework.excel.core.annotations.DictFormat;
import cn.iocoder.yudao.framework.excel.core.convert.DictConvert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数仓分层 Response VO")
@Data
@ExcelIgnoreUnannotated
public class DwLayerRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "20538")
    @ExcelProperty("主键")
    private Long id;

    @Schema(description = "父级ID，0表示一级分类节点", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @ExcelProperty("父级ID")
    private Long parentId;

    @Schema(description = "祖级列表，如 0 或 0,1", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("祖级列表")
    private String ancestors;

    @Schema(description = "层级：1分类节点，2实际分层", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("层级")
    private Short levelNo;

    @Schema(description = "分层名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "芋艿")
    @ExcelProperty("分层名称")
    private String layerName;

    @Schema(description = "分层编码：ODS/DIM/DWD/DWS/ADS，分类节点为空")
    @ExcelProperty("分层编码：ODS/DIM/DWD/DWS/ADS，分类节点为空")
    private String layerCode;

    @Schema(description = "节点类型：CATEGORY分类节点/LAYER实际分层节点", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @ExcelProperty("节点类型：CATEGORY分类节点/LAYER实际分层节点")
    private String nodeType;

    @Schema(description = "默认表前缀，仅实际分层节点填写")
    @ExcelProperty("默认表前缀，仅实际分层节点填写")
    private String tablePrefix;

    @Schema(description = "默认表类型：SOURCE贴源/DETAIL明细/DIMENSION维度/SUMMARY汇总/APPLICATION应用", example = "2")
    @ExcelProperty(value = "默认表类型：SOURCE贴源/DETAIL明细/DIMENSION维度/SUMMARY汇总/APPLICATION应用", converter = DictConvert.class)
    @DictFormat("default_table_type") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private String defaultTableType;

    @Schema(description = "负责人ID", example = "13001")
    @ExcelProperty("负责人ID")
    private Long ownerUserId;

    @Schema(description = "负责人姓名")
    private String ownerName;

    @Schema(description = "当前未废弃的逻辑模型数量；无查询权限或分类节点为空")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    private Long logicModelCount;

    @Schema(description = "是否可查看逻辑模型数量")
    private boolean logicModelCountAvailable;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("排序")
    private Integer sortOrder;

    @Schema(description = "是否内置：0否 1是，内置节点不允许删除", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty(value = "是否内置：0否 1是，内置节点不允许删除", converter = DictConvert.class)
    @DictFormat("is_builtin")
    private Short isBuiltin;

    @Schema(description = "状态：0停用 1启用", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @ExcelProperty(value = "状态：0停用 1启用", converter = DictConvert.class)
    @DictFormat("common_status")
    private Short status;

    @Schema(description = "说明", example = "随便")
    @ExcelProperty("说明")
    private String description;

    @Schema(description = "备注", example = "你说的对")
    @ExcelProperty("备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("创建时间")
    private LocalDateTime createTime;

}
