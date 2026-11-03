package cn.iocoder.yudao.module.governance.dal.dataobject.metadata;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据治理-元数据-标签关联 DO
 *
 * 多态引用任意元数据对象（object_type + object_id）
 *
 * @author Li
 */
@TableName("dh_governance_meta_tag_rel")
@KeySequence("dh_governance_meta_tag_rel_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaTagRelDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 标签编号
     */
    private Long tagId;

    /**
     * 对象类型：1-库 2-表 3-字段，见 {@link MetaChangeDO} OBJECT_TYPE_* 常量
     */
    private Integer objectType;

    /**
     * 对象编号
     */
    private Long objectId;

    /**
     * 备注
     */
    private String remark;

}
