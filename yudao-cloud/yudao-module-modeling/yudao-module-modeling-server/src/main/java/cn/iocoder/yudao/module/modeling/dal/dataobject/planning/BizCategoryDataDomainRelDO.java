package cn.iocoder.yudao.module.modeling.dal.dataobject.planning;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 业务分类覆盖数据域关系 DO
 */
@TableName("dh_modeling_biz_category_data_domain_rel")
@KeySequence("dh_modeling_biz_category_data_domain_rel_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BizCategoryDataDomainRelDO extends BaseDO {

    @TableId
    private Long id;

    /** 业务分类编号 */
    private Long bizCategoryId;

    /** 数据域编号 */
    private Long dataDomainId;

    /** 展示排序（数组下标） */
    private Integer sortOrder;

}
