package cn.iocoder.yudao.module.asset.dal.dataobject.market;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@TableName("dh_market_audit_event")
@KeySequence("dh_market_audit_event_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketAuditEventDO extends BaseDO {

    @TableId
    private Long id;

    private String eventId;

    private String eventType;

    private Long actorUserId;

    private Long deptId;

    private String objectType;

    private Long objectId;

    private String fromStatus;

    private String toStatus;

    private String reason;

    private String result;

    private LocalDateTime occurredAt;

    private String correlationId;
}
