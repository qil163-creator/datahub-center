package cn.iocoder.yudao.module.asset.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.quality.AssetQualityTaskDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface AssetQualityTaskMapper extends BaseMapperX<AssetQualityTaskDO> {

    /**
     * 按资产编号查询资产质量任务列表。
     */
    default List<AssetQualityTaskDO> selectListByAssetId(Long assetId) {
        return selectList(new LambdaQueryWrapperX<AssetQualityTaskDO>()
                .eq(AssetQualityTaskDO::getAssetId, assetId)
                .orderByDesc(AssetQualityTaskDO::getCreateTime)
                .orderByDesc(AssetQualityTaskDO::getId));
    }

    /**
     * 按资产编号与名称查询资产质量任务。
     */
    default AssetQualityTaskDO selectByAssetIdAndName(Long assetId, String taskName) {
        return selectOne(AssetQualityTaskDO::getAssetId, assetId,
                AssetQualityTaskDO::getTaskName, taskName);
    }

    /**
     * 按资产编号查询资产质量任务数量。
     */
    default Long selectCountByAssetId(Long assetId) {
        return selectCount(AssetQualityTaskDO::getAssetId, assetId);
    }

    /**
     * 按资产编号查询资产质量任务列表。
     */
    default List<AssetQualityTaskDO> selectListByAssetIds(Collection<Long> assetIds) {
        return selectList(AssetQualityTaskDO::getAssetId, assetIds);
    }

}
