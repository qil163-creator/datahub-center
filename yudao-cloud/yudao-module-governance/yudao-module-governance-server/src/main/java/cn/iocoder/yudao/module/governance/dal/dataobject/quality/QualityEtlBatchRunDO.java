package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/** 可信已提交 ETL 目标批次与其真实质量任务实例之间的持久化关联。 */
@TableName("dh_governance_quality_etl_batch_run")
@KeySequence("dh_governance_quality_etl_batch_run_seq")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class QualityEtlBatchRunDO extends BaseDO {

    @TableId
    private Long id;

    private Long qualityTaskId;

    private Long checkObjectId;

    private Long inlineReportId;

    private String qualityNodeId;

    private String qualityBatchId;

    private Long targetBatchId;

    private Long recordId;

    private Long replayOfBatchId;

    private Long replayOfRecordId;

    private Long workOrderSubmissionId;

    private String runId;

    private String sinkNodeId;

    private String batchKey;

    private String physicalBatchValue;

    private String manifestHash;

    private String manifestJson;

    private Long rowCount;

    private Long admittedRowCount;

    private Boolean allowWarnings;

    private String identityFieldsJson;

    private String identityHash;

    private Long instanceId;

    private String state;
}
