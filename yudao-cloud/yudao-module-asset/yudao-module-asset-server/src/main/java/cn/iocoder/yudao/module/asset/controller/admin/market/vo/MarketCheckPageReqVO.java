package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理后台 - 服务审批工单分页")
public class MarketCheckPageReqVO extends PageParam {

    @Size(max = 256)
    private String martName;

    @Pattern(regexp = "[1-6]")
    @Schema(description = "1未授权、2待授权、3已通过、4已驳回、5已下架、6已过期")
    private String status;

    @Size(max = 160)
    private String applicantKey;

    @Pattern(regexp = "displayId|applyTime")
    private String sortBy = "applyTime";

    @Pattern(regexp = "asc|desc")
    private String sortOrder = "desc";
}
