package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

/** 任务自己的评测配置；运行时复制进实例快照。 */
@TableName(value = "dh_governance_quality_task_evaluation", autoResultMap = true)
@KeySequence("dh_governance_quality_task_evaluation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityTaskEvaluationDO extends BaseDO {

    @TableId
    private Long id;

    private Long taskId;

    private Long objectId;

    private String evaluationName;

    private String ruleCode;

    private String strategyKey;

    private String qualityDim;

    private String severity;

    private Integer status;

    private String description;

    private String errorExample;

    private String repairAdvice;

    private String whereClause;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String columnNames;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String config;
}
