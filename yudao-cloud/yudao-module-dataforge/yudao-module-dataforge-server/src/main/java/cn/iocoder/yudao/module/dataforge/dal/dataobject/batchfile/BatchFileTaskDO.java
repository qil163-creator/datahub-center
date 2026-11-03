package cn.iocoder.yudao.module.dataforge.dal.dataobject.batchfile;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 批量文件入库的配置持久状态。 */
@TableName("dh_batch_file_task")
@KeySequence("dh_batch_file_task_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class BatchFileTaskDO extends BaseDO {

    @TableId
    private Long id;

    private String taskName;

    private Long responsibleUserId;

    private String responsibleName;

    private String responsiblePhone;

    private String description;

    private String remark;

    private Long targetDatabaseId;

    private String targetDatabaseName;

    private Boolean online;

    private Long revision;

    private String configJson;

    private String lastStatus;

    private Long lastRunId;

    private java.time.LocalDateTime lastRunTime;

    private Integer fileCount;

    private Integer targetCount;

    private Integer successFiles;

    private Integer failedFiles;
}
