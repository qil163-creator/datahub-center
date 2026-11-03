package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandMatchDO;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface MarketDemandMatchMapper extends BaseMapperX<MarketDemandMatchDO> {

    /** 仅忽略同需求、资产的未删除匹配冲突；其他约束失败仍抛出。主键沿用表的序列默认值。 */
    @Options(useGeneratedKeys = true, keyProperty = "et.id", keyColumn = "id")
    @Insert("""
            INSERT INTO dh_market_demand_match
                (demand_id,asset_id,match_type,match_note,status,matcher_user_id,
                 creator,updater,create_time,update_time,deleted)
            VALUES (#{et.demandId},#{et.assetId},#{et.matchType},#{et.matchNote},#{et.status},#{et.matcherUserId},
                    #{et.creator},#{et.updater},#{et.createTime},#{et.updateTime},0)
            ON CONFLICT (demand_id,asset_id) WHERE deleted=0 DO NOTHING
            """)
    int insertIfAbsent(@Param(Constants.ENTITY) MarketDemandMatchDO match);

    /** 验收短事务先锁匹配、再锁申请，防止已读取的匹配关联被撤回/换绑后仍作为验收依据。 */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
            SELECT * FROM dh_market_demand_match
            WHERE id=#{id} AND deleted=0
            FOR UPDATE
            """)
    MarketDemandMatchDO selectForAcceptance(@Param("id") long id);

    /**
     * 按需求编号查询市场需求匹配列表。
     */
    default List<MarketDemandMatchDO> selectListByDemandId(Long demandId) {
        return selectList(new LambdaQueryWrapperX<MarketDemandMatchDO>()
                .eq(MarketDemandMatchDO::getDemandId, demandId)
                .orderByDesc(MarketDemandMatchDO::getId));
    }

    /**
     * 按需求与资产查询市场需求匹配。
     */
    default MarketDemandMatchDO selectByDemandAndAsset(Long demandId, Long assetId) {
        return selectOne(new LambdaQueryWrapperX<MarketDemandMatchDO>()
                .eq(MarketDemandMatchDO::getDemandId, demandId)
                .eq(MarketDemandMatchDO::getAssetId, assetId)
                .last("LIMIT 1"));
    }

    /**
     * 按需求与状态查询市场需求匹配数量。
     */
    default long selectCountByDemandAndStatus(Long demandId, Integer status) {
        return selectCount(new LambdaQueryWrapperX<MarketDemandMatchDO>()
                .eq(MarketDemandMatchDO::getDemandId, demandId)
                .eq(MarketDemandMatchDO::getStatus, status));
    }

    /**
     * 按需求编号查询市场需求匹配列表。
     */
    default List<MarketDemandMatchDO> selectListByDemandIds(Collection<Long> demandIds) {
        if (demandIds == null || demandIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<MarketDemandMatchDO>()
                .in(MarketDemandMatchDO::getDemandId, demandIds)
                .orderByDesc(MarketDemandMatchDO::getId));
    }

}
