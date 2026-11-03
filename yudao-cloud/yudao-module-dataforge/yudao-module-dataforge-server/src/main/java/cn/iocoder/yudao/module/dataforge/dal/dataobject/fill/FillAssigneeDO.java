package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 在线填报-工作项当前填报人 DO（多人权限平等共享草稿；失效改 state 不删行）
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_assignee")
@KeySequence("dh_dataforge_fill_assignee_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillAssigneeDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 工作项编号
     */
    private Long workItemId;

    /**
     * 周期任务编号（冗余便查）
     */
    private Long taskId;

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

    /**
     * 状态：1-有效 0-已失效，见 FillAssigneeStateEnum
     */
    private Integer state;

    /**
     * 来源：1-计划默认 2-发起者指定 3-转派接收 4-管理员重新分配，见 FillAssigneeSourceEnum
     */
    private Integer source;

    /**
     * 生效时间
     */
    private LocalDateTime effectiveTime;

}
