package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据治理-数据质量-工单问题关联 DO
 *
 * @author Li
 */
@TableName("dh_governance_quality_work_order_issue")
@KeySequence("dh_governance_quality_work_order_issue_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityWorkOrderIssueDO extends BaseDO {

    @TableId
    private Long id;

    /** 工单ID */
    private Long workOrderId;

    /** 问题数据ID */
    private Long issueId;

}
