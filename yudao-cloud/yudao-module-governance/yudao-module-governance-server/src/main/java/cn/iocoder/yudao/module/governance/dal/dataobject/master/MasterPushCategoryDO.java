package cn.iocoder.yudao.module.governance.dal.dataobject.master;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dh_governance_master_push_category")
@KeySequence("dh_governance_master_push_category_seq")
public class MasterPushCategoryDO extends BaseDO {
    @TableId
    private Long id;
    private Long parentId;
    private String name;
    private Integer sort;
    private Integer status;
    private String description;
}
