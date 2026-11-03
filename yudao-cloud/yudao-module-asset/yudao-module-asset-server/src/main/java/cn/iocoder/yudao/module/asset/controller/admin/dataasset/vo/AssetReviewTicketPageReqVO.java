package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AssetReviewTicketPageReqVO extends PageParam {

    private Long assetId;

    private Integer status;

    private Integer issueType;

}
