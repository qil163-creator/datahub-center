package cn.iocoder.yudao.module.asset.dal.mysql.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetTechLinkDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AssetTechLinkMapper extends BaseMapperX<AssetTechLinkDO> {

    /**
     * 按资产编号查询资产技术关联列表。
     */
    default List<AssetTechLinkDO> selectListByAssetId(Long assetId) {
        return selectList(new LambdaQueryWrapperX<AssetTechLinkDO>()
                .eq(AssetTechLinkDO::getAssetId, assetId)
                .orderByDesc(AssetTechLinkDO::getPrimaryFlag)
                .orderByAsc(AssetTechLinkDO::getId));
    }

    /**
     * 查询主对象。
     */
    default AssetTechLinkDO selectPrimary(Long assetId) {
        return selectOne(new LambdaQueryWrapperX<AssetTechLinkDO>()
                .eq(AssetTechLinkDO::getAssetId, assetId)
                .eq(AssetTechLinkDO::getPrimaryFlag, 1)
                .last("LIMIT 1"));
    }

    /**
     * 按资产与元数据查询资产技术关联。
     */
    default AssetTechLinkDO selectByAssetAndMeta(Long assetId, Long metaTableId) {
        return selectOne(new LambdaQueryWrapperX<AssetTechLinkDO>()
                .eq(AssetTechLinkDO::getAssetId, assetId)
                .eq(AssetTechLinkDO::getMetaTableId, metaTableId)
                .last("LIMIT 1"));
    }

}
