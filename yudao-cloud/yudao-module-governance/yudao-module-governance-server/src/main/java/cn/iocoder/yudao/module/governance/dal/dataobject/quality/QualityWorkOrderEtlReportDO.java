package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/** 工单与其来源 ETL 内联质量报告之间的不可变关联。 */
@TableName("dh_governance_quality_work_order_etl_report")
@KeySequence("dh_governance_quality_work_order_etl_report_seq")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class QualityWorkOrderEtlReportDO extends BaseDO {

    @TableId
    private Long id;

    private Long workOrderId;

    private Long reportId;
}
