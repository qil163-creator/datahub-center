package cn.iocoder.yudao.module.asset.dal.mysql.market;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MarketDepartmentAssetRow extends MarketMyAssetRow {

    private String sourceApplyId;

    private String sourceSystem;

    private String sourceMarketId;

    private String sourceDetailSnapshot;

    private String sourceAssetExternalCode;

    private String declaredDeliveryTypes;
}
