package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 数据需求分页")
@Data
@EqualsAndHashCode(callSuper = true)
public class MarketDemandPageReqVO extends PageParam {

    private String keyword;

    private String demandNo;

    private Integer demandType;

    private Integer urgency;

    private Integer expectedDelivery;

    private Integer status;

    private Long applyDeptId;

    @Schema(description = "1-仅本人 0-大厅/全部")
    private Integer ifSelf;

}
