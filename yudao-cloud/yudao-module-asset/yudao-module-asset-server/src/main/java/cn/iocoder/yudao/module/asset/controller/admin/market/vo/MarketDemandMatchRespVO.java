package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 需求匹配结果")
@Data
public class MarketDemandMatchRespVO {

    private Long id;

    private Long demandId;

    private Long assetId;

    private String assetName;

    private String assetCode;

    private Integer assetType;

    private Integer matchType;

    private BigDecimal matchScore;

    private String matchNote;

    private Integer status;

    private Long applyId;

    private Long matcherUserId;

    private String matcherName;

    private Long confirmUserId;

    private String confirmUserName;

    private LocalDateTime confirmTime;

    private String confirmNote;

    private LocalDateTime createTime;

}
