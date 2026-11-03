package cn.iocoder.yudao.module.asset.dal.mysql.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetCatalogVersionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AssetCatalogVersionMapper extends BaseMapperX<AssetCatalogVersionDO> {

    /**
     * 按资产编号查询资产目录版本列表。
     */
    default List<AssetCatalogVersionDO> selectListByAssetId(Long assetId) {
        return selectList(new LambdaQueryWrapperX<AssetCatalogVersionDO>()
                .eq(AssetCatalogVersionDO::getAssetId, assetId)
                .orderByDesc(AssetCatalogVersionDO::getVersionNo));
    }

    /**
     * 查询上限版本编号。
     */
    default Integer selectMaxVersionNo(Long assetId) {
        AssetCatalogVersionDO last = selectOne(new LambdaQueryWrapperX<AssetCatalogVersionDO>()
                .eq(AssetCatalogVersionDO::getAssetId, assetId)
                .orderByDesc(AssetCatalogVersionDO::getVersionNo)
                .last("LIMIT 1"));
        return last == null || last.getVersionNo() == null ? 0 : last.getVersionNo();
    }

}
