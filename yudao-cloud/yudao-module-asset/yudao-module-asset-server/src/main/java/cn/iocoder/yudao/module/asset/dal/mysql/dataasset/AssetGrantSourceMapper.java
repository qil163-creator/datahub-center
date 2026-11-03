package cn.iocoder.yudao.module.asset.dal.mysql.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetGrantSourceDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssetGrantSourceMapper extends BaseMapperX<AssetGrantSourceDO> {

    /**
     * 按指定条件读取并锁定资产授权来源记录，供事务内一致性校验或修改使用。
     */
    @Select("""
            SELECT * FROM dh_asset_grant_source
            WHERE source_key=#{sourceKey} FOR UPDATE
            """)
    AssetGrantSourceDO selectSourceForUpdate(
            @Param("sourceKey") String sourceKey);

    /**
     * 按申请编号查询资产授权来源。
     */
    default AssetGrantSourceDO selectByApplyId(Long applyId) {
        return selectOne(new LambdaQueryWrapperX<AssetGrantSourceDO>()
                .eq(AssetGrantSourceDO::getApplyId, applyId)
                .last("LIMIT 1"));
    }

    /**
     * 统计有效其他。
     */
    default long countActiveOthers(Long appId, Long apiId, Long applyId) {
        if (appId == null || apiId == null) {
            return 0;
        }
        return selectCount(new LambdaQueryWrapperX<AssetGrantSourceDO>()
                .eq(AssetGrantSourceDO::getAppId, appId)
                .eq(AssetGrantSourceDO::getApiId, apiId)
                .eq(AssetGrantSourceDO::getRevoked, 0)
                .ne(applyId != null, AssetGrantSourceDO::getApplyId, applyId));
    }

}
