package cn.iocoder.yudao.module.asset.dal.dataobject.market;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

@TableName("dh_market_resource_comment")
@KeySequence("dh_market_resource_comment_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketResourceCommentDO extends BaseDO {

    public static final int KIND_NORMAL = 1;

    public static final int KIND_CHECK = 2;

    @TableId
    private Long id;

    private Long assetId;

    private Long applyId;

    private String commentStr;

    private Integer level;

    private String replyStr;

    private Long replyUserId;

    private LocalDateTime replyTime;

    private Integer commentKind;

    private String problemType;

    private String impactScope;

    private LocalDateTime foundTime;

    private Long workOrderId;

    private String workOrderStatus;

    private String idempotentKey;

}
