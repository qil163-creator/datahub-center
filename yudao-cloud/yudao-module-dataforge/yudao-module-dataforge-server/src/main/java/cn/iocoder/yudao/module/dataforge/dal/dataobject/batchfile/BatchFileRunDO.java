package cn.iocoder.yudao.module.dataforge.dal.dataobject.batchfile;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 批量文件入库的运行持久状态。 */
@TableName("dh_batch_file_run")
@KeySequence("dh_batch_file_run_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class BatchFileRunDO extends BaseDO {

    @TableId
    private Long id;

    private Long taskId;

    private String requestId;

    private Long executionUserId;

    private String configJson;

    private String status;

    private String leaseToken;

    private java.time.LocalDateTime leaseUntil;

    private java.time.LocalDateTime startTime;

    private java.time.LocalDateTime endTime;

    private Integer fileCount;

    private Integer successCount;

    private Integer failedCount;

    private Long writtenRows;

    private String itemsJson;

    private String errorMessage;

    private Long resolvedBy;

    private java.time.LocalDateTime resolvedTime;

    private String resolutionReason;
}
