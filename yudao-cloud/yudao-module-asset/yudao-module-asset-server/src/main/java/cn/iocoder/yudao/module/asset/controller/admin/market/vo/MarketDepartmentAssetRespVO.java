package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "管理后台 - 同部门申请的资产；申请状态不表示当前用户已经取得消费授权")
public class MarketDepartmentAssetRespVO {

    private Long id;

    private String displayId;

    private Long assetId;

    private String sourceApplyId;

    private String sourceMarketId;

    private String marketName;

    private Long marketCategoryId;

    private String marketCategoryName;

    private List<Integer> deliveryTypes = List.of();

    private String frequency;

    private Integer applyType;

    private LocalDateTime applyTime;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private String applyReason;

    private Integer status;

    private String statusLabel;

    private String statusTone;

    private Boolean canView = true;
}
