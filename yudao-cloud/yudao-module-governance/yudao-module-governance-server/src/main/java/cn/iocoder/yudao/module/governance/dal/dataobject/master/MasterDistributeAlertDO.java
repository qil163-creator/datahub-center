package cn.iocoder.yudao.module.governance.dal.dataobject.master;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 主数据派发终态告警账本，使用全局运行标识关联业务状态。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dh_governance_master_distribute_alert")
public class MasterDistributeAlertDO extends BaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Long runId;

    private String eventType;

    private Long recipientId;

    private String taskName;

    private Integer status;

    private Integer attempts;

    private LocalDateTime availableAt;

    private String leaseToken;

    private LocalDateTime leaseUntil;

    private Long notifyMessageId;

    private String lastError;

    private LocalDateTime occurredAt;

    private LocalDateTime handledAt;
}
