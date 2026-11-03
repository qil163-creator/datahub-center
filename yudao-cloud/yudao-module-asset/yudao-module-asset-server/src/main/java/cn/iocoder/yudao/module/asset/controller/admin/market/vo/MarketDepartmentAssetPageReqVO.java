package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MarketDepartmentAssetPageReqVO extends PageParam {

    @Size(max = 200)
    private String keyword;

    @Min(1)
    @Max(3)
    private Integer deliveryType;

    @Positive
    private Long marketCategoryId;

    @Pattern(regexp = "applyId|applyTime")
    private String sortField = "applyTime";

    @Pattern(regexp = "asc|desc")
    private String sortOrder = "desc";
}
