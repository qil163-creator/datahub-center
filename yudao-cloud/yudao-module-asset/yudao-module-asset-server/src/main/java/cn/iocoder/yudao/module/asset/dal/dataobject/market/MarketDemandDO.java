package cn.iocoder.yudao.module.asset.dal.dataobject.market;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

@TableName("dh_market_demand")
@KeySequence("dh_market_demand_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketDemandDO extends BaseDO {

    public static final int TYPE_NEW_DATA = 1;

    public static final int TYPE_EXISTING_SHARE = 2;

    public static final int TYPE_QUALITY = 3;

    public static final int TYPE_OTHER = 4;

    public static final int DELIVERY_API = 1;

    public static final int DELIVERY_TABLE = 2;

    public static final int DELIVERY_FILE = 3;

    public static final int DELIVERY_ANY = 4;

    public static final int URGENCY_LOW = 1;

    public static final int URGENCY_MEDIUM = 2;

    public static final int URGENCY_HIGH = 3;

    public static final int STATUS_DRAFT = 0;

    public static final int STATUS_PUBLISHED = 1;

    public static final int STATUS_MATCHING = 2;

    public static final int STATUS_MATCHED = 3;

    public static final int STATUS_CLOSED = 4;

    public static final int STATUS_CANCELLED = 5;

    public static final int STATUS_REJECTED = 6;

    public static final int STATUS_APPLYING = 7;

    public static final int ACCEPTANCE_NONE = 0;

    public static final int ACCEPTANCE_DONE = 1;

    @TableId
    private Long id;

    private String demandNo;

    private String title;

    private Integer demandType;

    private String description;

    private String purpose;

    private String expectedFields;

    private String expectedUpdateFreq;

    private Integer expectedDelivery;

    private LocalDateTime expectFinishTime;

    private Integer urgency;

    private Long applyDeptId;

    private Long applyUserId;

    private String phone;

    private String email;

    private Integer status;

    private LocalDateTime publishTime;

    private String closeReason;

    private Integer acceptanceStatus;

    private Long acceptedApplyId;

    private LocalDateTime acceptedTime;

    private String remark;

}
