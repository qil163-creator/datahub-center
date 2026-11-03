package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 在线填报-计划组织默认填报人 DO（含人员快照；发布任务时复制为工作项填报人）
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_plan_org_assignee")
@KeySequence("dh_dataforge_fill_plan_org_assignee_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillPlanOrgAssigneeDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 采集计划编号（冗余便查）
     */
    private Long planId;

    /**
     * 计划目标组织编号（dh_dataforge_fill_plan_org.id）
     */
    private Long planOrgId;

    /**
     * 填报人用户编号
     */
    private Long userId;

    /**
     * 用户昵称快照
     */
    private String userName;

    /**
     * 用户所属部门编号快照
     */
    private Long deptId;

}
