package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 数据集市申请")
@Data
public class MarketApplyRespVO {

    private Long id;

    private String applyNo;

    private String title;

    private Long assetId;

    private String assetName;

    private String assetCode;

    private Integer assetType;

    private Long apiId;

    private Long appId;

    private Long demandId;

    private String demandNo;

    private Long applyDeptId;

    private String applyDeptName;

    private Long applyUserId;

    private String applyUserName;

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

    private Boolean effective;

    private Boolean usable;

    private Integer approvalResult;

    private Integer techProvisionStatus;

    private Integer authStatus;

    private Integer revision;

    @Schema(description = "交付来源版本；开通/停用、暂停/恢复、收回时可作为客户端所见版本回传")
    private Long sourceRevision;

    private String dutyMatter;

    private String legalBasis;

    private String supplementNote;

    private LocalDateTime receivedTime;

    private LocalDateTime replyDeadline;

    private LocalDateTime deliveryDeadline;

    private Integer deliveryStatus;

    /**
     * 交付结果：PENDING 待生效、UNKNOWN 结果待确认、APPLIED 已生效、REJECTED 交付被拒绝、
     * SUPERSEDED 原操作被更新来源版本覆盖、SUSPENDED 暂停已确认、REVOKED 终态撤销已确认；
     * 无需交付时为空。由交付操作账本推导，不使用只记录投递意图的 deliveryStatus。
     */
    @Schema(description = "交付结果：PENDING/UNKNOWN/APPLIED/REJECTED/SUPERSEDED/SUSPENDED/REVOKED", example = "APPLIED")
    private String deliveryResolution;

    @Schema(description = "最新交付操作的原身份；仅为定位，不是重投命令或授权凭证")
    private String deliveryOperationId;

    @Schema(description = "续期所对应的原申请编号；续期是新申请，原批准不被延长")
    private Long renewalOfApplyId;

    private String sourceGrantId;

    private LocalDateTime firstUsedTime;

    private Integer paused;

    private Long datasourceId;

    private String tableName;

    private Long defaultCheckerUserId;

    private String defaultCheckerName;

    private Boolean ownerReviewer;

    private Long checkUserId;

    private String checkUserName;

    private LocalDateTime checkTime;

    private String checkNote;

    private String remark;

    private String creator;

    private LocalDateTime createTime;

    private List<MarketCheckProcessRespVO> processes;

}
