package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetRespVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Schema(description = "管理后台 - 集市资产")
@Data
@EqualsAndHashCode(callSuper = true)
public class MarketResourceRespVO extends DataAssetRespVO {

    private BigDecimal commentAvg;

    private Integer commentCount;

    private Integer applyCount;

    private Integer myApplyStatus;

    private Long myApplyId;

    private Boolean authorized;

    private Boolean usable;

    private Boolean favorite;

    private Long marketCategoryId;

    private String marketCategoryName;

    private String marketName;

    private String marketDescription;

    private String frequency;

    private java.util.List<Integer> deliveryTypes;

    private String serviceIdentifier;

    private Integer primaryDeliveryType;

    private String displayScore;

}
