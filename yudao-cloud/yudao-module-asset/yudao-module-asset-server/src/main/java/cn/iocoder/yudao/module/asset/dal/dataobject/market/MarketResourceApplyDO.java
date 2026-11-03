package cn.iocoder.yudao.module.asset.dal.dataobject.market;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

@TableName("dh_market_resource_apply")
@KeySequence("dh_market_resource_apply_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketResourceApplyDO extends BaseDO {

    public static final int STATUS_DRAFT = 0;

    public static final int STATUS_PENDING = 1;

    public static final int STATUS_APPROVED = 2;

    public static final int STATUS_REJECTED = 3;

    public static final int STATUS_CANCELLED = 4;

    public static final int STATUS_EXPIRED = 5;

    public static final int STATUS_REVOKED = 6;

    public static final int STATUS_SUPPLEMENT = 8;

    public static final int AUTH_NO = 0;

    public static final int AUTH_YES = 1;

    public static final int USE_UNLIMITED = 0;

    public static final int USE_LIMITED = 1;

    public static final int USE_ROWS = 2;

    @TableId
    private Long id;

    private String applyNo;

    private String title;

    private Long assetId;

    private Integer assetType;

    private Long apiId;

    private Long appId;

    private Long demandId;

    private Long applyDeptId;

    private Long applyUserId;

    private String phone;

    private String email;

    private String purpose;

    private String fieldScope;

    private String applyNote;

    private LocalDateTime applyStartTime;

    private LocalDateTime applyEndTime;

    private Integer applyUseType;

    private Integer applyUseTimes;

    private Integer status;

    private Integer ifAuth;

    private Long checkUserId;

    private LocalDateTime checkTime;

    private String checkNote;

    private Integer revision;

    private String snapshot;

    private Long catalogVersionId;

    private String dutyMatter;

    private String legalBasis;

    private String supplementNote;

    private LocalDateTime receivedTime;

    private LocalDateTime replyDeadline;

    private LocalDateTime deliveryDeadline;

    private Integer deliveryStatus;

    private String sourceGrantId;

    private LocalDateTime firstUsedTime;

    private Integer paused;

    private String bpmInstanceId;

    private String remark;

    private Long renewalOfApplyId;

    private String renewalOperationId;

    /** 首次续期请求证据，不随补正或审批更新；历史空值不得按当前期限猜测回填。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime renewalRequestedEndTime;

    private Long sourceRevision;

    /** 真实来源申请的不可变导入关联，不是授权凭证，也不把来源申请人冒认为本地用户。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long sourceWorkOrderId;

}
