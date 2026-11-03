package cn.iocoder.yudao.module.asset.dal.mysql.market;

import lombok.Data;

import java.time.LocalDateTime;

/** 只读核查的当前对象归属和状态投影；不读取个人联系方式或可变的申请快照。 */
@Data
public class MarketDeliveryEvidenceContext {

    private Long applyId;

    private Long assetId;

    private Long appId;

    private Long apiId;

    private Integer revision;

    private Long sourceRevision;

    private Integer status;

    private Integer paused;

    private Integer ifAuth;

    private LocalDateTime applyUpdateTime;

    private Long techLeaderUserId;

    private Long businessLeaderUserId;

    private Long ownerDeptId;

    private LocalDateTime assetUpdateTime;
}
