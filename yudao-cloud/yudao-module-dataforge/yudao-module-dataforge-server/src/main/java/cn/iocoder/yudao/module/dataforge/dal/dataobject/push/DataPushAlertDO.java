package cn.iocoder.yudao.module.dataforge.dal.dataobject.push;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 推送终态告警账本，控制面按任务处理。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dh_dataforge_push_alert")
public class DataPushAlertDO extends BaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Long runId;

    private String executeNo;

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
