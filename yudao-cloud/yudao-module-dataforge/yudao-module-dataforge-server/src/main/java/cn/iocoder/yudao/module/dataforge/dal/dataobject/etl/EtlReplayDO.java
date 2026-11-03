package cn.iocoder.yudao.module.dataforge.dal.dataobject.etl;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dh_dataforge_etl_replay")
@KeySequence("dh_dataforge_etl_replay_seq")
public class EtlReplayDO extends BaseDO {

    @TableId
    private Long id;

    private Long recordId;

    private Long sourceRecordId;

    private Long workOrderId;

    private Long submissionId;

    private Long actorId;

    private String requestId;

    private String requestHash;

    private String snapshotMode;

    private String reason;

    private String attachStatus;

    private Integer attemptCount;

    private String leaseToken;

    private LocalDateTime leaseUntil;

    private LocalDateTime nextAttemptAt;

    private String lastError;
}
