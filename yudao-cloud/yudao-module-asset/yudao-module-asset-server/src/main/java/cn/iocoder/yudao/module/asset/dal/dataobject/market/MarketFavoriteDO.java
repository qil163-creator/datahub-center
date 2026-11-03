package cn.iocoder.yudao.module.asset.dal.dataobject.market;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@TableName("dh_asset_market_favorite")
@KeySequence("dh_asset_market_favorite_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class MarketFavoriteDO extends BaseDO {

    @TableId
    private Long id;

    private Long userId;

    private Long assetId;
}
