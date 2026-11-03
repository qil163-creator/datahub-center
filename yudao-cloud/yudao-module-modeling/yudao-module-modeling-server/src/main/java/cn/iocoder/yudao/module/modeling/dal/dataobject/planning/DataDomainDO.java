package cn.iocoder.yudao.module.modeling.dal.dataobject.planning;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import cn.iocoder.yudao.framework.mybatis.core.type.LongListTypeHandler;
import cn.iocoder.yudao.module.modeling.framework.mybatis.core.type.JsonbStringTypeHandler;

import java.util.List;

import lombok.*;

/**
 * 数据板块 DO
 *
 * @author Li
 */
@TableName(value = "dh_modeling_data_domain", autoResultMap = true)
@KeySequence("dh_modeling_data_domain_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataDomainDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 数据板块名称
     */
    private String name;

    /**
     * 英文缩写
     */
    private String code;

    /** 板块图标。 */
    private String icon;

    /** 业务负责人，最多三人。 */
    @TableField(typeHandler = LongListTypeHandler.class)
    private List<Long> businessOwnerIds;

    /** 数据负责人，最多三人。 */
    @TableField(typeHandler = LongListTypeHandler.class)
    private List<Long> dataOwnerIds;

    /** 推荐逻辑表名规则。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String namingRules;

    /**
     * 负责人用户编号
     */
    private Long leaderUserId;

    /**
     * 负责人电话
     */
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String leaderPhone;

    /**
     * 状态：0-启用 1-停用
     */
    private Integer status;

    /**
     * 描述
     */
    private String description;

    /**
     * 备注
     */
    private String remark;

}
