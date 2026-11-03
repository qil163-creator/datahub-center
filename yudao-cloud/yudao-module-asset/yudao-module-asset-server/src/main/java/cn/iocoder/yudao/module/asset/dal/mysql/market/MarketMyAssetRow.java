package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 只用于本服务的批准列表投影，禁止作为接口响应返回原始申请快照。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MarketMyAssetRow extends MarketResourceApplyDO {

    private String assetName;

    private String assetCode;

    private String marketName;

    private Long marketCategoryId;

    private String marketCategoryName;

    private String updateCycle;

    private String description;

    private String assetVersion;

    private Boolean frozenCatalogValid;

    private Integer deliveryType;
}
