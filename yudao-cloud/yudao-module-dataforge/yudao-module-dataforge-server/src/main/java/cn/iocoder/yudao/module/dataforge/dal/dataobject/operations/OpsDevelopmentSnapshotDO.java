package cn.iocoder.yudao.module.dataforge.dal.dataobject.operations;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 源系统数据开发实例只读快照，不参与本平台调度、抢占或停止状态机。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dh_dataforge_ops_development_snapshot")
public class OpsDevelopmentSnapshotDO extends BaseDO {

    @TableId
    private Long id;

    private Long taskId;

    private Long categoryId;

    private String taskName;

    private String instanceName;

    private String taskType;

    private String executionType;

    private String status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long duration;

    private String ownerName;

    private String creatorName;

    private String description;

    private String logContent;
}
