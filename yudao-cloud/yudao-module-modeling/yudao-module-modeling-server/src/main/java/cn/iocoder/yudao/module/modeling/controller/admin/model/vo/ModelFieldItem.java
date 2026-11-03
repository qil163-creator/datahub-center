package cn.iocoder.yudao.module.modeling.controller.admin.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 模型字段定义项（当前模型字段及部署任务输入结构）
 *
 * @author Li
 */
@Schema(description = "模型字段定义项")
@Data
public class ModelFieldItem {

    @Schema(description = "字段稳定标识（UUID，创建后不可变；改名保留，复制字段生成新值）", example = "3f2b8c1e-…")
    private String fieldUid;

    @Schema(description = "历史来源属性稳定标识，仅用于兼容旧字段快照")
    private String sourceAttrUid;

    @Schema(description = "中文名称", example = "姓名")
    private String cnName;

    @Schema(description = "字段名称", example = "name")
    private String fieldName;

    @Schema(description = "数据类型", example = "varchar")
    private String dataType;

    @Schema(description = "字符长度（不用于定点数总精度）", example = "255")
    private Integer dataLength;

    @Schema(description = "定点数总精度", example = "18")
    private Integer dataPrecision;

    @Schema(description = "小数位数", example = "2")
    private Integer dataScale;

    @Schema(description = "数据格式，如 yyyy-MM-dd", example = "yyyy-MM-dd")
    private String dataFormat;

    @Schema(description = "计量单位", example = "元")
    private String measureUnit;

    @Schema(description = "值域范围说明", example = "[0,150]")
    private String valueRange;

    @Schema(description = "默认值")
    private String defaultValue;

    @Schema(description = "排序", example = "1")
    private Integer sort;

    @Schema(description = "注释")
    private String comment;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "是否主键", example = "true")
    private Boolean pkFlag;

    @Schema(description = "是否可空（默认可空）", example = "true")
    private Boolean nullableFlag;

    @Schema(description = "聚合类型（仅 Doris 聚合模型的 Value 列）：SUM/MAX/MIN/REPLACE/REPLACE_IF_NOT_NULL",
            example = "SUM")
    private String aggType;

    @Schema(description = "关联数据标准编号（物理模型字段）", example = "1")
    private Long standardId;

    @Schema(description = "关联数据标准编码", example = "age")
    private String standardCode;

    @Schema(description = "关联数据标准名称", example = "年龄")
    private String standardName;

    @Schema(description = "关联数据标准字段类型（绑定时快照，用于比对标准变更）", example = "VARCHAR")
    private String standardColumnType;

    @Schema(description = "字段类别：PRIMARY_KEY/ATTRIBUTE/DIMENSION/MEASURE/TIME/GRAIN/INDICATOR/NORMAL")
    private String fieldCategory;

    @Schema(description = "关联维度业务对象编号")
    private Long dimensionId;

    @Schema(description = "关联维度名称")
    private String dimensionName;

    @Schema(description = "数据分类编号")
    private Long dataClassificationId;

    @Schema(description = "数据分类名称")
    private String dataClassificationName;

    @Schema(description = "数据分级编码")
    private String dataGrade;

    @Schema(description = "关联标准代码表编号")
    private Long codeTableId;

    @Schema(description = "关联标准代码表名称")
    private String codeTableName;

    @Schema(description = "是否具有唯一性约束")
    private Boolean uniqueFlag;

    @Schema(description = "派生指标编号（自动字段的来源快照）")
    private Long indicatorId;

    @Schema(description = "原子指标名称快照")
    private String atomicIndicatorName;

    @Schema(description = "业务限定快照，保留指标实际口径文本")
    private String businessCondition;

    @Schema(description = "统计周期快照")
    private String statisticsPeriod;

    @Schema(description = "来源逻辑模型名称快照")
    private String sourceModelName;

}
