package cn.iocoder.yudao.module.governance.dal.dataobject.master;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 主数据领域持久记录；通过业务对象标识关联。 */
@TableName("dh_governance_master_data_change")
@KeySequence("dh_governance_master_data_change_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class MasterDataChangeDO extends BaseDO {

    private Long id;

    private Long modelId;

    private String requestId;

    private String operation;

    private String status;

    private String payloadHash;

    private String rowsJson;

    private String beforeJson;

    private String rowKeysJson;

    private Integer schemaVersion;

    private Long applicantId;

    private Boolean approvalRequired;

    private String bpmProcessKey;

    private String processInstanceId;

    private Long reviewerId;

    private LocalDateTime reviewTime;

    private String reviewComment;

    private String lastError;

    private String applyToken;

    private LocalDateTime applyStartedAt;

    private LocalDateTime appliedAt;

    private Long applyActorId;

    private String resolutionOutcome;

    private String resolutionReason;

    private String resolutionEvidence;

    private Long resolvedBy;

    private LocalDateTime resolvedAt;
}
