package cn.iocoder.yudao.module.modeling.dal.dataobject.planning;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.*;

/**
 * 主题域 DO（业务分析主题树）
 *
 * @author Li
 */
@TableName("dh_modeling_subject_domain")
@KeySequence("dh_modeling_subject_domain_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubjectDomainDO extends BaseDO {

    public static final Long PARENT_ID_ROOT = 0L;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 父级编号，0 表示顶级节点
     */
    private Long parentId;

    /** 所属数据板块。 */
    private Long dataDomainId;

    /** 英文缩写，用于逻辑表命名。 */
    private String abbreviation;

    /**
     * 主题域名称
     */
    private String name;

    /**
     * 英文缩写
     */
    private String code;

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
