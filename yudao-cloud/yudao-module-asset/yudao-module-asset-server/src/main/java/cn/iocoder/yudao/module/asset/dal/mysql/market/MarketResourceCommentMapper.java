package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceCommentDO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface MarketResourceCommentMapper extends BaseMapperX<MarketResourceCommentDO> {

    /** 同一校核操作的查重与写入在短事务中串行，锁随提交或回滚释放。 */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{requestKey}, 0))")
    Integer lockCommentRequest(@Param("requestKey") String requestKey);

    /**
     * 查询市场资源注释分页。
     */
    default PageResult<MarketResourceCommentDO> selectPage(MarketCommentPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MarketResourceCommentDO>()
                .eqIfPresent(MarketResourceCommentDO::getAssetId, reqVO.getAssetId())
                .orderByDesc(MarketResourceCommentDO::getId));
    }

    /**
     * 按幂等键查询市场资源注释。
     */
    default MarketResourceCommentDO selectByIdempotentKey(String idempotentKey) {
        if (idempotentKey == null || idempotentKey.isBlank()) {
            return null;
        }
        return selectOne(MarketResourceCommentDO::getIdempotentKey, idempotentKey);
    }

    /**
     * 按资产编号查询统计。
     */
    default List<Map<String, Object>> selectStatsByAssetIds(Collection<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return List.of();
        }
        return selectMaps(Wrappers.<MarketResourceCommentDO>query()
                .select("asset_id AS asset_id", "COUNT(*) AS cnt", "AVG(level) AS avg_level")
                .in("asset_id", assetIds)
                .groupBy("asset_id"));
    }

}
