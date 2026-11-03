package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据治理-数据质量-任务检查对象关联 DO
 *
 * @author Li
 */
@TableName("dh_governance_quality_task_object")
@KeySequence("dh_governance_quality_task_object_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityTaskObjectDO extends BaseDO {

    @TableId
    private Long id;

    /** 任务ID */
    private Long taskId;

    /** 检查对象ID */
    private Long objectId;

}
