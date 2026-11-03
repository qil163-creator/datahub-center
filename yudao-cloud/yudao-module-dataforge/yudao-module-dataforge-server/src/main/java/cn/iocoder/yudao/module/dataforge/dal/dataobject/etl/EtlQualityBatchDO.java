package cn.iocoder.yudao.module.dataforge.dal.dataobject.etl;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

/** 内联检测事实及可靠上报状态，与目标写入成功独立。后台扫描按任务处理。 */
@TableName("dh_dataforge_etl_quality_batch")
@KeySequence("dh_dataforge_etl_quality_batch_seq")
@Data
public class EtlQualityBatchDO extends BaseDO {

    @TableId
    private Long id;

    private Long recordId;

    private String nodeNo;

    private Long executionUserId;

    private String planHash;

    private String identityFieldsJson;

    private String identityHash;

    private Long inputCount;

    private Long passCount;

    private Long warnCount;

    private Long rejectCount;

    private Long violationCount;

    private String registrationStatus;

    private Long governanceReportId;

    private Integer attemptCount;

    private String leaseToken;

    private LocalDateTime leaseUntil;

    private LocalDateTime nextAttemptAt;
}
