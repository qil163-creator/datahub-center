package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Schema(description = "管理后台 - 数据集市检索 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class MarketPageReqVO extends PageParam {

    @Size(max = 200)
    @Schema(description = "发布快照中的资产名称、表名或说明")
    private String keyword;

    @Size(max = 3)
    @Schema(description = "资产类型多选：1-表、2-API、3-文件")
    private List<@NotNull @Min(1) @Max(3) Integer> assetTypes;

    @Schema(description = "仅当前用户具有有效授权的资产")
    private Boolean authorizedOnly;

    @Schema(description = "仅当前用户收藏的资产")
    private Boolean favoriteOnly;

    @Positive
    @Schema(description = "独立集市类目，包含其全部下级")
    private Long marketCategoryId;

    @Pattern(regexp = "LATEST|POPULAR|FAVORITE")
    @Schema(description = "LATEST-最新发布，POPULAR-真实浏览次数，FAVORITE-真实收藏人数", example = "LATEST")
    private String sort = "LATEST";
}
