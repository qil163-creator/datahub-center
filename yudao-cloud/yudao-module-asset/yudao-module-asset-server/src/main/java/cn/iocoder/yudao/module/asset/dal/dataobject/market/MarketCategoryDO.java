package cn.iocoder.yudao.module.asset.dal.dataobject.market;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@TableName("dh_asset_market_category")
@KeySequence("dh_asset_market_category_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class MarketCategoryDO extends BaseDO {

    @TableId
    private Long id;

    private Long parentId;

    private String name;

    private String description;

    private Short status;

    private Integer sort;
}
