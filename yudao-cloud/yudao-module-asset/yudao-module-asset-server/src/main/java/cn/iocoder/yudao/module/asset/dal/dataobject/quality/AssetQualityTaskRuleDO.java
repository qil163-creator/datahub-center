package cn.iocoder.yudao.module.asset.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 资产质量任务规则 DO。
 */
@TableName("dh_asset_quality_task_rule")
@KeySequence("dh_asset_quality_task_rule_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetQualityTaskRuleDO extends BaseDO {

    @TableId
    private Long id;

    private Long taskId;

    private Long auditRuleId;

    /** 治理质量规则编号（执行以它为准） */
    private Long qualityRuleId;

    private String ruleName;

    private String dimensionName;

    private Long metaColumnId;

    private String columnName;

    private Long qualityEngineTaskId;

    private String remark;

}
