package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 我的已批准资产分页")
@Data
@EqualsAndHashCode(callSuper = true)
public class MarketMyAssetPageReqVO extends PageParam {

    @Size(max = 200)
    private String keyword;

    @Min(1)
    @Max(3)
    @Schema(description = "实际申请交付形态：1库表、2API、3文件")
    private Integer deliveryType;

    @Positive
    @Schema(description = "集市类目，包含全部下级")
    private Long marketCategoryId;
}
