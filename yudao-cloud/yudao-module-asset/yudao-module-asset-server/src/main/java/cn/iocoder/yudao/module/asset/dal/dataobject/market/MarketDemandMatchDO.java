package cn.iocoder.yudao.module.asset.dal.dataobject.market;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("dh_market_demand_match")
@KeySequence("dh_market_demand_match_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketDemandMatchDO extends BaseDO {

    public static final int TYPE_SUGGEST = 1;

    public static final int TYPE_MANUAL = 2;

    public static final int STATUS_PENDING = 0;

    public static final int STATUS_ACCEPTED = 1;

    public static final int STATUS_REJECTED = 2;

    @TableId
    private Long id;

    private Long demandId;

    private Long assetId;

    private Integer matchType;

    private BigDecimal matchScore;

    private String matchNote;

    private Integer status;

    private Long applyId;

    private Long matcherUserId;

    private Long confirmUserId;

    private LocalDateTime confirmTime;

    private String confirmNote;

}
