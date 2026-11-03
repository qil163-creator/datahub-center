package cn.iocoder.yudao.module.asset.dal.dataobject.market;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName("dh_market_demand_reply")
@KeySequence("dh_market_demand_reply_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketDemandReplyDO extends BaseDO {

    public static final int TYPE_COMMENT = 1;

    public static final int TYPE_CAN_SUPPLY = 2;

    public static final int TYPE_CANNOT_SUPPLY = 3;

    @TableId
    private Long id;

    private Long demandId;

    private Integer replyType;

    private String content;

    private Long replyDeptId;

    private Long replyUserId;

}
