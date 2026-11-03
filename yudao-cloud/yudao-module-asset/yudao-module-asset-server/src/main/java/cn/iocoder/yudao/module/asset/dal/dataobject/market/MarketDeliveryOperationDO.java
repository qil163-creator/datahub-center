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

@TableName("dh_market_delivery_operation")
@KeySequence("dh_market_delivery_operation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketDeliveryOperationDO extends BaseDO {

    public static final String TYPE_GRANT = "GRANT";

    public static final String TYPE_REVOKE = "REVOKE";

    public static final String STATUS_PENDING = "PENDING";

    public static final String STATUS_RUNNING = "RUNNING";

    public static final String STATUS_SUCCESS = "SUCCESS";

    public static final String STATUS_RETRY = "RETRY";

    public static final String STATUS_MANUAL = "MANUAL";

    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId
    private Long id;

    private String operationId;

    private Long applyId;

    private String sourceGrantId;

    private String opType;

    private Long appId;

    private Long apiId;

    private Integer expectedRevision;

    private String status;

    private Integer attemptCount;

    private LocalDateTime nextRetryTime;

    private LocalDateTime leaseUntil;

    private String lastError;

    private String resultRef;

    private Integer protocolVersion;

    private Long sourceRevision;

    // 冻结载荷只允许通过 insertFrozen 的 JSONB 显式参数写入，通用更新不得覆盖。
    @com.baomidou.mybatisplus.annotation.TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String payloadJson;

    private String payloadHash;

    private String claimToken;

    private Long rowVersion;

    private String resolutionStatus;
}
