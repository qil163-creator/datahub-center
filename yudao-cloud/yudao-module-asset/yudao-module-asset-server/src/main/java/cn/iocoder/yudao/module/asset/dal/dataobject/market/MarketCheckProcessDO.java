package cn.iocoder.yudao.module.asset.dal.dataobject.market;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName("dh_market_check_process")
@KeySequence("dh_market_check_process_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketCheckProcessDO extends BaseDO {

    public static final String ACTION_SUBMIT = "SUBMIT";

    public static final String ACTION_APPROVE = "APPROVE";

    public static final String ACTION_REJECT = "REJECT";

    public static final String ACTION_AUTH = "AUTH";

    public static final String ACTION_UNAUTH = "UNAUTH";

    public static final String ACTION_CANCEL = "CANCEL";

    public static final String ACTION_REVOKE = "REVOKE";

    public static final String ACTION_EXPIRE = "EXPIRE";

    public static final String ACTION_SUPPLEMENT = "SUPPLEMENT";

    public static final String ACTION_RESUBMIT = "RESUBMIT";

    public static final String ACTION_RENEW = "RENEW";

    public static final String ACTION_PAUSE = "PAUSE";

    public static final String ACTION_RESUME = "RESUME";

    @TableId
    private Long id;

    private Long resourceApplyId;

    private String action;

    private Integer fromStatus;

    private Integer toStatus;

    private Long operatorUserId;

    private String note;

}
