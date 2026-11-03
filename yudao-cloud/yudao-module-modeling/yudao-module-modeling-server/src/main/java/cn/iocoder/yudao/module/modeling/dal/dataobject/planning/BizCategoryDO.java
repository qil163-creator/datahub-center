package cn.iocoder.yudao.module.modeling.dal.dataobject.planning;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 业务分类 DO（纯业务分类树）
 *
 * @author Li
 */
@TableName(value = "dh_modeling_biz_category", autoResultMap = true)
@KeySequence("dh_modeling_biz_category_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BizCategoryDO extends BaseDO {

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

    /**
     * 业务分类名称
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
    private String leaderPhone;

    /**
     * 状态：0-启用 1-停用
     */
    private Integer status;

    /**
     * 责任范围说明，不作为授权依据
     */
    private String responsibilityScope;

    /**
     * 描述
     */
    private String description;

    /**
     * 备注
     */
    private String remark;

}
