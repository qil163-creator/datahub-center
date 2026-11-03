package cn.iocoder.yudao.module.dataforge.dal.dataobject.etl;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 文件副作用持久账本；扫描按持久化运行记录处理。 */
@Data
@TableName("dh_dataforge_etl_file_output")
@KeySequence("dh_dataforge_etl_file_output_seq")
public class EtlFileOutputDO extends BaseDO {

    @TableId
    private Long id;

    private Long recordId;

    private Long pipelineId;

    private String nodeNo;

    private Long ownerId;

    private String runToken;

    private String operationKey;

    private String storageKey;

    private String storageIdentity;

    private String fileName;

    private String state;

    private String temporaryFileKey;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String temporaryResourcesJson;

    private String payloadHash;

    private Long payloadBytes;

    private Boolean publicationApproved;

    private String recoveryStatus;

    private Integer attempts;

    private String recoveryToken;

    private LocalDateTime recoveryLeaseUntil;

    private LocalDateTime nextAttemptAt;

    private String lastErrorCode;

    private Long revision;

    /** 默认策略为 KEEP；任何按存储时长执行的任务都不得将其转为删除请求。 */
    private String retentionStatus;

    private LocalDateTime retireAfter;

    private Long retirementRequestedBy;

    private String retirementReason;

    private String purgePhase;
}
