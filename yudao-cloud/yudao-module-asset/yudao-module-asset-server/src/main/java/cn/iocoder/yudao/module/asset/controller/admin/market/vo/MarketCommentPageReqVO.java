package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 资源评价分页")
@Data
@EqualsAndHashCode(callSuper = true)
public class MarketCommentPageReqVO extends PageParam {

    private Long assetId;

}
