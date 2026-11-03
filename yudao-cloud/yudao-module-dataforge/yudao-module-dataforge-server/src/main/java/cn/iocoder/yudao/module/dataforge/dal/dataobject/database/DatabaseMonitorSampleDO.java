package cn.iocoder.yudao.module.dataforge.dal.dataobject.database;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("dh_dataforge_database_sample")
@KeySequence("dh_dataforge_database_sample_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class DatabaseMonitorSampleDO extends BaseDO {

    @TableId
    private Long id;

    private Long databaseId;

    private LocalDateTime slotTime;

    private LocalDateTime collectedTime;

    private String state;

    private String leaseToken;

    private LocalDateTime leaseUntil;

    private Integer connectionStatus;

    private String collectionStatus;

    private String collectionMessage;

    private Long responseTimeMs;

    private String capacityUsed;
}
