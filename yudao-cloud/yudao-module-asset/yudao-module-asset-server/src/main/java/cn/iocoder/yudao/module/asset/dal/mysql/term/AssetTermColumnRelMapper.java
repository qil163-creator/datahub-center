package cn.iocoder.yudao.module.asset.dal.mysql.term;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.term.AssetTermColumnRelDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AssetTermColumnRelMapper extends BaseMapperX<AssetTermColumnRelDO> {

    /**
     * 按资产编号查询资产术语字段关联列表。
     */
    default List<AssetTermColumnRelDO> selectListByAssetId(Long assetId) {
        return selectList(AssetTermColumnRelDO::getAssetId, assetId);
    }

    /**
     * 按资产编号与字段编号查询资产术语字段关联。
     */
    default AssetTermColumnRelDO selectByAssetIdAndColumnId(Long assetId, Long metaColumnId) {
        return selectOne(AssetTermColumnRelDO::getAssetId, assetId,
                AssetTermColumnRelDO::getMetaColumnId, metaColumnId);
    }

    /**
     * 按资产编号删除资产术语字段关联记录。
     */
    default void deleteByAssetId(Long assetId) {
        delete(AssetTermColumnRelDO::getAssetId, assetId);
    }
}
