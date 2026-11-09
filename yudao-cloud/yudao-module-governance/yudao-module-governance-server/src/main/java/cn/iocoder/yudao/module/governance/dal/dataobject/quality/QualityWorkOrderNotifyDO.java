package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

@TableName("dh_governance_quality_work_order_notify")
@KeySequence("dh_governance_quality_work_order_notify_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityWorkOrderNotifyDO extends BaseDO {

    public static final String STATUS_PENDING = "PENDING";

    public static final String STATUS_SENDING = "SENDING";

    public static final String STATUS_SENT = "SENT";

    public static final String STATUS_FAILED = "FAILED";

    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /** 自动投递次数耗尽（死信），等待人工核验已送达/未送达并按需授权重试 */
    public static final String STATUS_EXHAUSTED = "EXHAUSTED";

    /** 自动投递最大次数；FAILED 与 UNKNOWN 共用计数 */
    public static final int MAX_AUTO_ATTEMPTS = 8;

    /** 通知服务幂等键前缀；同一投递记录的所有尝试使用同一键，远端只创建一条站内信 */
    public static final String IDEMPOTENCY_KEY_PREFIX = "governance:quality-work-order-notify:";

    public static final String RESOLUTION_UNRESOLVED = "UNRESOLVED";

    public static final String RESOLUTION_MANUAL_DELIVERED = "MANUAL_DELIVERED";

    public static final String RESOLUTION_MANUAL_NOT_DELIVERED = "MANUAL_NOT_DELIVERED";

    public static final String RESOLUTION_RETRY_AUTHORIZED = "RETRY_AUTHORIZED";

    @TableId
    private Long id;

    private Long workOrderId;

    private String eventType;

    private Long receiverUserId;

    private String timeSlice;

    private String payloadSummary;

    private String sendStatus;

    private Integer retryCount;

    private Integer manualRetryCount;

    private LocalDateTime nextRetryTime;

    private String claimToken;

    private LocalDateTime leaseUntil;

    private String lastError;

    private String resolutionStatus;

    private Integer resolutionVersion;

    private String resolutionReason;

    private Long operatorMessageId;

    private Long resolvedBy;

    private LocalDateTime resolvedAt;

}
