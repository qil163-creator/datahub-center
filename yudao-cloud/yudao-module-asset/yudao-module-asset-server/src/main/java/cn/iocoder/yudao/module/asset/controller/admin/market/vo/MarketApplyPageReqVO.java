package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 数据集市申请分页")
@Data
@EqualsAndHashCode(callSuper = true)
public class MarketApplyPageReqVO extends PageParam {

    private String keyword;

    private String applyNo;

    private Long assetId;

    private Integer assetType;

    private Integer status;

    private Integer ifAuth;

    private Long applyDeptId;

    @Schema(description = "1-仅本人 0-全部")
    private Integer ifSelf;

    @Schema(description = "1-仅我负责的资产 0-全部")
    private Integer ifOwner;

}
