package cn.iocoder.yudao.module.dataforge.dal.dataobject.database;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("dh_dataforge_database_alarm_record")
@KeySequence("dh_dataforge_database_alarm_record_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class DatabaseAlarmRecordDO extends BaseDO {

    @TableId
    private Long id;

    private Long databaseId;

    private Long ruleId;

    private String ruleName;

    private String description;

    private String metric;

    private String operator;

    private String threshold;

    private String currentValue;

    private String level;

    private String status;

    private LocalDateTime firstTime;

    private LocalDateTime lastTime;

    private Integer occurrences;

    private String handledBy;

    private LocalDateTime handledTime;

    private String handleRemark;
}
