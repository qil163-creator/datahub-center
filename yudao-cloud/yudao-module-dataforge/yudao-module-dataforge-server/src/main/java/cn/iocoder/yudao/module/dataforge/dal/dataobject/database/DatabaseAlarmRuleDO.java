package cn.iocoder.yudao.module.dataforge.dal.dataobject.database;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@TableName("dh_dataforge_database_alarm_rule")
@KeySequence("dh_dataforge_database_alarm_rule_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class DatabaseAlarmRuleDO extends BaseDO {

    @TableId
    private Long id;

    private Long databaseId;

    private String name;

    private String description;

    private String metric;

    private String operator;

    private String threshold;

    private String level;

    private Integer status;

    private String owner;
}
