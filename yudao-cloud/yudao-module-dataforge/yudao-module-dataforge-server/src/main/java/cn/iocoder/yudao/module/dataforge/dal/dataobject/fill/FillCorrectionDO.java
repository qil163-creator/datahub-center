package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 在线填报-数据更正实例 DO（已合并数据不回退草稿；更正生成新快照重新走 BPM，通过后幂等覆盖）
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_correction")
@KeySequence("dh_dataforge_fill_correction_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillCorrectionDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 原提交快照编号
     */
    private Long originSubmissionId;

    /**
     * 工作项编号
     */
    private Long workItemId;

    /**
     * 周期任务编号
     */
    private Long taskId;

    /**
     * 更正原因
     */
    private String reason;

    /**
     * 更正生成的新提交快照编号
     */
    private Long correctionSubmissionId;

    /**
     * 状态：0-草拟 1-审批中 2-已合并 3-已取消，见 FillCorrectionStatusEnum
     */
    private Integer status;

}
