package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 在线填报-计划目标组织 DO（含组织快照，组织架构变化不影响已配置计划）
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_plan_org")
@KeySequence("dh_dataforge_fill_plan_org_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillPlanOrgDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 采集计划编号
     */
    private Long planId;

    /**
     * 报送组织编号（system_dept.id；区别于模板归属组织 ownerOrgId）
     */
    private Long reportingOrgId;

    /**
     * 组织名称快照
     */
    private String orgName;

    /**
     * 组织编码快照
     */
    private String orgCode;

    /**
     * 组织路径快照
     */
    private String orgPath;

    /**
     * 显示顺序
     */
    private Integer sort;

}
