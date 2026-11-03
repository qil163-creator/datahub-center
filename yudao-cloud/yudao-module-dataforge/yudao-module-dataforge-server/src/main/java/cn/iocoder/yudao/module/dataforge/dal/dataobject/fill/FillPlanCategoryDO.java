package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/** 采集计划分类目录，平台内共享。 */
@TableName("dh_dataforge_fill_plan_category")
@KeySequence("dh_dataforge_fill_plan_category_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillPlanCategoryDO extends BaseDO {

    public static final Long PARENT_ID_ROOT = 0L;

    @TableId
    private Long id;

    private Long parentId;

    private String name;

    private Integer sort;

}
