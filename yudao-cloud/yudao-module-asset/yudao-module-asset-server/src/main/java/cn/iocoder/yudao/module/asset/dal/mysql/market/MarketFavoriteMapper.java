package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketFavoriteDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface MarketFavoriteMapper extends BaseMapperX<MarketFavoriteDO> {

    /** 部分唯一索引处理并发添加，其他约束失败不能当作收藏成功。 */
    @Insert("""
            INSERT INTO dh_asset_market_favorite(user_id,asset_id,creator,updater,create_time,update_time,deleted)
            VALUES(#{userId},#{assetId},CAST(#{userId} AS varchar),CAST(#{userId} AS varchar),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
            ON CONFLICT(user_id,asset_id) WHERE deleted=0 DO NOTHING
            """)
    int insertIfAbsent(@Param("userId") Long userId, @Param("assetId") Long assetId);

    /**
     * 按用户与资产查询市场收藏。
     */
    default List<MarketFavoriteDO> selectByUserAndAssets(Long userId, Collection<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<MarketFavoriteDO>()
                .eq(MarketFavoriteDO::getUserId, userId).in(MarketFavoriteDO::getAssetId, assetIds));
    }

    /**
     * 按指定条件统计市场收藏的查询已发布数量记录数。
     */
    @Select("""
            SELECT COUNT(*) FROM dh_asset_market_favorite f
            JOIN dh_asset_data_asset a ON a.id=f.asset_id AND a.status=1 AND a.deleted=0
            WHERE f.user_id=#{userId} AND f.deleted=0
            """)
    Long selectPublishedCount(@Param("userId") Long userId);
}
