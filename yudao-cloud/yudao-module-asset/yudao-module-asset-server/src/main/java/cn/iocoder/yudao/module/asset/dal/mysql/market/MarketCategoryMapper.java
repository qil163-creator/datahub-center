package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCategoryListReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCategoryDO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MarketCategoryMapper extends BaseMapperX<MarketCategoryDO> {

    /** 所有树结构写入在事务内串行，避免两个并发移动共同形成环。 */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(1500,28)) tree_lock")
    Integer lockTree();

    /**
     * 按指定条件读取并锁定市场分类记录，供事务内一致性校验或修改使用。
     */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM dh_asset_market_category WHERE id=#{id} AND deleted=0 FOR UPDATE")
    MarketCategoryDO selectForUpdate(@Param("id") Long id);

    /**
     * 查询用于发布。
     */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM dh_asset_market_category WHERE id=#{id} AND deleted=0 FOR SHARE")
    MarketCategoryDO selectForPublish(@Param("id") Long id);

    /**
     * 查询分类列表。
     */
    default List<MarketCategoryDO> selectCategoryList(MarketCategoryListReqVO req) {
        return selectList(new LambdaQueryWrapperX<MarketCategoryDO>()
                .likeIfPresent(MarketCategoryDO::getName, req.getName())
                .eqIfPresent(MarketCategoryDO::getParentId, req.getParentId())
                .eqIfPresent(MarketCategoryDO::getStatus, req.getStatus())
                .orderByAsc(MarketCategoryDO::getSort).orderByAsc(MarketCategoryDO::getId));
    }
}
