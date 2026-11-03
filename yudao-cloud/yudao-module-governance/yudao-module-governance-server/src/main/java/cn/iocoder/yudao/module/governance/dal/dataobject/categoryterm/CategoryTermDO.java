package cn.iocoder.yudao.module.governance.dal.dataobject.categoryterm;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName("dh_governance_category_term")
@KeySequence("dh_governance_category_term_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryTermDO extends BaseDO {

    public static final Long PARENT_ID_ROOT = 0L;

    @TableId
    private Long id;
    private Long parentId;
    private String categoryName;
    private String categoryCode;
    private Integer sort;
    private Short status;
    private String remark;

}
