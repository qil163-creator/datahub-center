package cn.iocoder.yudao.module.governance.dal.dataobject.master;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/** 主数据域，平台内共享的模型分类。 */
@TableName("dh_governance_master_domain")
@KeySequence("dh_governance_master_domain_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasterDomainDO extends BaseDO {

    private Long id;

    private String code;

    private String name;

    private String description;

    private String remark;

    private Long leaderUserId;

    private String leaderPhone;

    private String icon;

    private Integer sort;

    /** 0-启用，1-停用。 */
    private Integer status;
}
