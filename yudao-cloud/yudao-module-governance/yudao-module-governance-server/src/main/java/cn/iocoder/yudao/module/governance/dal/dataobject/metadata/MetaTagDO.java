package cn.iocoder.yudao.module.governance.dal.dataobject.metadata;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据治理-元数据-标签 DO
 *
 * @author Li
 */
@TableName("dh_governance_meta_tag")
@KeySequence("dh_governance_meta_tag_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaTagDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 标签名称
     */
    private String name;

    /**
     * 标签分类
     */
    private String category;

    /**
     * 标签颜色
     */
    private String color;

    /**
     * 备注
     */
    private String remark;

}
