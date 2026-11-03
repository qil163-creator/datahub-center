package cn.iocoder.yudao.module.supply.dal.dataobject.appapi;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

@TableName(value = "dh_supply_grant_op", autoResultMap = true)
@KeySequence("dh_supply_grant_op_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplyGrantOpDO extends BaseDO {

    public static final String DELIVERED = "DELIVERED";

    public static final String CONFLICT = "CONFLICT";

    public static final String UNKNOWN = "UNKNOWN";

    public static final String FAILED = "FAILED";

    @TableId
    private Long id;

    private String sourceSystem;

    private String operationId;

    private String sourceDecisionId;

    private String payloadHash;

    private String aggregateKey;

    private Integer targetStatus;

    private String resultStatus;

    private Long grantId;

    private Integer grantRevision;

    private String errorCode;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private LocalDateTime leaseUntil;

    private Integer protocolVersion;

    private Long sourceRevision;

    private Long aggregateRevision;

    private String commandType;

    @com.baomidou.mybatisplus.annotation.TableField(typeHandler = cn.iocoder.yudao.module.supply.framework.mybatis.core.type.JsonbStringTypeHandler.class)
    private String responseJson;
}
