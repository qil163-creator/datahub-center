package cn.iocoder.yudao.module.dataforge.dal.dataobject.dev;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** DS 周期触发绑定和恢复意图；不保存凭据或用户脚本。 */
@TableName("dh_dataforge_dev_schedule")
@KeySequence("dh_dataforge_dev_schedule_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class DevScheduleDO extends BaseDO {

    @TableId
    private Long id;

    private Long taskId;

    private Long versionId;

    private Integer generation;

    private String cronExpression;

    private String timezoneId;

    private Long dsProjectCode;

    private String workerGroup;

    private String tenantCode;

    private Boolean desiredEnabled;

    private Boolean syncedEnabled;

    private Long dsWorkflowCode;

    private Integer dsScheduleId;

    private String syncStatus;

    private Integer attempts;

    private String leaseOwner;

    private LocalDateTime leaseUntil;

    private String lastError;

    private Long lastDsInstanceId;

    private LocalDateTime scheduleStartTime;

    private LocalDateTime pollEndTime;

    private Integer pollPageNo;

    private Long pollHighWaterId;

    private LocalDateTime lastPollTime;
}
