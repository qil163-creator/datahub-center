package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 在线填报-填报人变更日志 DO（只追加）
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_assignment_log")
@KeySequence("dh_dataforge_fill_assignment_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillAssignmentLogDO extends BaseDO {

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
     * 动作：1-新增 2-移除 3-转派 4-强制重新分配，见 FillAssignmentActionEnum
     */
    private Integer action;

    /**
     * 原填报人编号（新增时为空）
     */
    private Long fromUserId;

    /**
     * 新填报人编号（移除时为空）
     */
    private Long toUserId;

    /**
     * 操作人编号
     */
    private Long operatorUserId;

    /**
     * 原因说明
     */
    private String reason;

}
