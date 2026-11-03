package cn.iocoder.yudao.module.dataforge.dal.dataobject.etl;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 持久化目标端执行意图；外部提交与质量验收分别维护独立状态。 */
@Data
@TableName("dh_dataforge_etl_target_batch")
@KeySequence("dh_dataforge_etl_target_batch_seq")
public class EtlTargetBatchDO extends BaseDO {

    @TableId
    private Long id;

    private Long recordId;

    private String nodeNo;

    private Long executionUserId;

    private Long qualityBatchId;

    private Long qualityTaskId;

    private Long qualityInstanceId;

    private String batchKey;

    /** 逻辑 batchKey 在平台内保持全局唯一；仅重放操作可指向已有物理批次。 */
    private String physicalBatchKey;

    private Long replayOfBatchId;

    private Long replayOfRecordId;

    private Long workOrderId;

    private Long workOrderSubmissionId;

    private Long replacedByBatchId;

    private String expectedPayloadHash;

    private String operationKey;

    private String configJson;

    private String payloadJson;

    private String sourcePayloadJson;

    private String payloadHash;

    private Long inputCount;

    private Long outputCount;

    private Long admittedCount;

    private Long rejectedCount;

    private String identityHash;

    private String commitStatus;

    private String receiptJson;

    private String qualityStatus;

    private Integer attemptCount;

    private String leaseToken;

    private LocalDateTime leaseUntil;

    private LocalDateTime nextAttemptAt;

    private String lastError;
}
