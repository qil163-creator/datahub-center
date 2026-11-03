package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceEndpointDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MarketResourceEndpointMapper extends BaseMapperX<MarketResourceEndpointDO> {

    /**
     * 按资产编号查询市场资源端点列表。
     */
    default List<MarketResourceEndpointDO> selectListByAssetId(Long assetId) {
        return selectList(new LambdaQueryWrapperX<MarketResourceEndpointDO>()
                .eq(MarketResourceEndpointDO::getAssetId, assetId)
                .eq(MarketResourceEndpointDO::getEndpointType, MarketResourceEndpointDO.TYPE_API)
                .orderByDesc(MarketResourceEndpointDO::getId));
    }

    /**
     * 按资产与API查询市场资源端点。
     */
    default MarketResourceEndpointDO selectByAssetAndApi(Long assetId, Long apiId) {
        return selectOne(new LambdaQueryWrapperX<MarketResourceEndpointDO>()
                .eq(MarketResourceEndpointDO::getAssetId, assetId)
                .eq(MarketResourceEndpointDO::getApiId, apiId)
                .eq(MarketResourceEndpointDO::getEndpointType, MarketResourceEndpointDO.TYPE_API)
                .last("LIMIT 1"));
    }
}
