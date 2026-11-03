package cn.iocoder.yudao.module.governance.dal.dataobject.etl;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/** ETL 数据行质量聚合的幂等回执，不是 SQL 质量任务实例。 */
@TableName("dh_governance_etl_quality_report")
@KeySequence("dh_governance_etl_quality_report_seq")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class InlineQualityReportDO extends BaseDO {

    @TableId
    private Long id;

    private String runId;

    private String nodeId;

    private String batchId;

    private String sourceType;

    private String planHash;

    private String idempotencyKey;

    private String requestHash;

    private Long executionUserId;

    private Long totalCount;

    private Long passCount;

    private Long warnCount;

    private Long rejectCount;

    private String issueReferencesJson;

    private String identityFieldsJson;

    private String identityHash;
}
