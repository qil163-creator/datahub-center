package cn.iocoder.yudao.module.modeling.dal.dataobject.model;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据建模-逻辑模型字段（当前字段的唯一真相源）
 */
@TableName("dh_modeling_logic_model_column")
@KeySequence("dh_modeling_logic_model_column_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogicModelColumnDO extends BaseDO {

    @TableId
    private Long id;

    private Long modelId;

    /** = ModelFieldItem.fieldUid，创建后不可变 */
    private String fieldUid;

    private String sourceAttrUid;

    private String fieldName;

    private String cnName;

    private String dataType;

    private Integer dataLength;

    private Integer dataPrecision;

    private Integer dataScale;

    private String dataFormat;

    private String measureUnit;

    private String valueRange;

    private String defaultValue;

    /** 1=主键，0=否 */
    private Integer pkFlag;

    /** 1=可空，0=非空 */
    private Integer nullableFlag;

    private Integer sort;

    private String comment;

    private String remark;

    private String aggType;

    private Long standardId;

    private String standardCode;

    private String standardName;

    private String standardColumnType;

    private String fieldCategory;

    private Long dimensionId;

    private String dimensionName;

    private Long dataClassificationId;

    private String dataClassificationName;

    private String dataGrade;

    private Long codeTableId;

    private String codeTableName;

    private Integer uniqueFlag;

    /** 汇总指标来源及业务口径快照。 */
    private Long indicatorId;

    private String atomicIndicatorName;

    private String businessCondition;

    private String statisticsPeriod;

    private String sourceModelName;
}
