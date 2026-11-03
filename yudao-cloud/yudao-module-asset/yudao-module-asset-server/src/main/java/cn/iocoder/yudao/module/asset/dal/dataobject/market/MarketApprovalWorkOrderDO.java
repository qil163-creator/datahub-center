package cn.iocoder.yudao.module.asset.dal.dataobject.market;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dh_market_approval_work_order")
@KeySequence("dh_market_approval_work_order_seq")
public class MarketApprovalWorkOrderDO extends BaseDO {

    @TableId
    private Long id;

    private String sourceSystem;

    private String sourceRecordId;

    private String martSourceId;

    private String martName;

    private String applicantKey;

    private String applicantName;

    private String applicantDeptName;

    private Integer applyType;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime applyTime;

    private String applyReason;

    private String sourceStatus;

    private String sourceApprovalReason;

    private LocalDateTime sourceApprovalTime;

    private String detailSnapshot;

    private String localStatus;

    private String localApprovalReason;

    private Long localCheckUserId;

    private LocalDateTime localCheckTime;

    private Integer revision;
}
