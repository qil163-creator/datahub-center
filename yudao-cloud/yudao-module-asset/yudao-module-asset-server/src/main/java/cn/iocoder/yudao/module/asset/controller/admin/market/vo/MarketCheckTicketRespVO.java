package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Schema(description = "管理后台 - 服务审批工单；来源身份与本地身份隔离")
public class MarketCheckTicketRespVO {

    private String id;

    private String origin;

    private String displayId;

    private String sourceRecordId;

    private String martName;

    private String applicantKey;

    private String applicantName;

    private String applicantDeptName;

    private Integer applyType;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime applyTime;

    private String applyReason;

    private String approvalReason;

    private String status;

    private String statusLabel;

    private String statusTone;

    private String sourceStatus;

    private String sourceStatusLabel;

    private Integer revision;

    private Boolean canApprove;

    private Boolean canReject;

    private Boolean canView;

    private MarketApplyRespVO nativeApply;

    @Schema(description = "经类型白名单投影的来源详情，不含远端凭证或真实调用能力")
    private Map<String, Object> detailSnapshot;
}
