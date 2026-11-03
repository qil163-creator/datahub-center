package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketListingDO;
import org.apache.ibatis.annotations.*;

import java.util.Collection;
import java.util.List;

@Mapper
public interface MarketListingMapper extends BaseMapperX<MarketListingDO> {

    /**
     * 按业务条件新增市场市场上架的新增或更新状态或数据，返回受影响行数。
     */
    @Insert("""
            INSERT INTO dh_asset_market_listing(asset_id,market_category_id,display_name,description,frequency,delivery_types,revision,
              creator,updater,create_time,update_time,deleted)
            VALUES(#{item.assetId},#{item.marketCategoryId},#{item.displayName},#{item.description},#{item.frequency},
              #{item.deliveryTypes},#{item.revision},CAST(#{userId} AS varchar),CAST(#{userId} AS varchar),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
            ON CONFLICT(asset_id) DO UPDATE SET market_category_id=EXCLUDED.market_category_id,
              display_name=EXCLUDED.display_name,description=EXCLUDED.description,frequency=EXCLUDED.frequency,
              delivery_types=EXCLUDED.delivery_types,revision=EXCLUDED.revision,
              updater=EXCLUDED.updater,update_time=CURRENT_TIMESTAMP,deleted=0
            """)
    int upsert(@Param("item") MarketListingDO item, @Param("userId") Long userId);

    /**
     * 按指定条件统计市场市场上架的查询已发布分类数量记录数。
     */
    @Select("""
            SELECT l.market_category_id category_id,COUNT(*) cnt
            FROM dh_asset_market_listing l JOIN dh_asset_data_asset a
              ON a.id=l.asset_id AND a.status=1 AND a.deleted=0
            WHERE l.deleted=0 AND l.market_category_id IS NOT NULL GROUP BY l.market_category_id
            """)
    List<java.util.Map<String, Object>> selectPublishedCategoryCounts();

    /**
     * 按资产编号查询市场市场上架。
     */
    default List<MarketListingDO> selectByAssetIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<MarketListingDO>().in(MarketListingDO::getAssetId, ids));
    }
}
