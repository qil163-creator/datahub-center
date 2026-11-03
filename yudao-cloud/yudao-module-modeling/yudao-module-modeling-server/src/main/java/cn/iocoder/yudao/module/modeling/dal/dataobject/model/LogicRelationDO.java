package cn.iocoder.yudao.module.modeling.dal.dataobject.model;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import cn.iocoder.yudao.module.modeling.framework.mybatis.core.type.JsonbStringTypeHandler;
import lombok.*;

/**
 * 数据建模-逻辑模型关系 DO（逻辑模型 ER 图的边，字段级关联）
 *
 * @author Li
 */
@TableName(value = "dh_modeling_logic_relation", autoResultMap = true)
@KeySequence("dh_modeling_logic_relation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogicRelationDO extends BaseDO {

    /** 关系类型：1:1 */
    public static final String TYPE_ONE_TO_ONE = "ONE_TO_ONE";

    /** 关系类型：1:N */
    public static final String TYPE_ONE_TO_MANY = "ONE_TO_MANY";

    /** 关系类型：N:M */
    public static final String TYPE_MANY_TO_MANY = "MANY_TO_MANY";

    /**
     * 主键
     */
    @TableId
    private Long id;

    private Long revision;

    /** 有序稳定字段标识对，不存另一份字段定义。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String fieldPairs;

    /**
     * 源逻辑模型编号
     */
    private Long sourceModelId;

    /**
     * 目标逻辑模型编号
     */
    private Long targetModelId;

    /**
     * 源模型字段名
     */
    private String sourceField;

    /**
     * 目标模型字段名
     */
    private String targetField;

    /**
     * 关系名称
     */
    private String name;

    /**
     * 关系类型：ONE_TO_ONE/ONE_TO_MANY/MANY_TO_MANY
     */
    private String relationType;

    /**
     * 描述
     */
    private String description;

}
