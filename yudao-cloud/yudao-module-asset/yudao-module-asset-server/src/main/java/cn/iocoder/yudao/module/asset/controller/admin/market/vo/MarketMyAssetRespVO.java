package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 当前用户的一条已批准资产来源；展示状态不是消费凭证")
@Data
public class MarketMyAssetRespVO {

    private Long applyId;

    private String applyNo;

    private Long assetId;

    private String assetCode;

    private String assetName;

    private String marketName;

    private Long marketCategoryId;

    private String marketCategoryName;

    private Integer deliveryType;

    private LocalDateTime createTime;

    private LocalDateTime applyStartTime;

    private LocalDateTime applyEndTime;

    private LocalDateTime evaluatedAt;

    @Schema(description = "AVAILABLE/NOT_STARTED/EXPIRED/PAUSED/REVOKED/DELIVERY_PENDING/DELIVERY_UNKNOWN/UNAVAILABLE")
    private String accessStatus;

    private String deliveryResolution;

    private String updateCycle;

    private Boolean effective;

    private Boolean usable;

    private Boolean canPreview;

    private Boolean canViewApiDocument;

    private Boolean canDownload;

    private String disabledReason;
}
