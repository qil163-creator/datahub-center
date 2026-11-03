package cn.iocoder.yudao.module.asset.dal.mysql.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetEtlBatchBindingDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AssetEtlBatchBindingMapper extends BaseMapperX<AssetEtlBatchBindingDO> {

    /**
     * 与资产详情同一对象范围；只读历史，远端批次失效不抹掉过去的关联事实。
     */
    default java.util.List<AssetEtlBatchBindingDO> history(Long assetId, Integer beforeRevision, int limit) {
        if (limit < 1 || limit > 51) {
            throw new IllegalArgumentException("Bounded binding history required");
        }
        return selectList(new LambdaQueryWrapperX<AssetEtlBatchBindingDO>()
                .eq(AssetEtlBatchBindingDO::getAssetId, assetId)
                .ltIfPresent(AssetEtlBatchBindingDO::getRevision, beforeRevision)
                .orderByDesc(AssetEtlBatchBindingDO::getRevision).last("LIMIT " + limit));
    }

    /**
     * 读取资产ETL批次绑定最新记录。
     */
    default AssetEtlBatchBindingDO latest(Long assetId) {
        return selectOne(
                new LambdaQueryWrapperX<AssetEtlBatchBindingDO>().eq(AssetEtlBatchBindingDO::getAssetId, assetId)
                        .orderByDesc(AssetEtlBatchBindingDO::getRevision).last("LIMIT 1"));
    }
}
