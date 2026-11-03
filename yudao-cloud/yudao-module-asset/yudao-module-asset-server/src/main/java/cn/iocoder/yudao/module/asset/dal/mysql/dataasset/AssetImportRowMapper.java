package cn.iocoder.yudao.module.asset.dal.mysql.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetImportRowDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AssetImportRowMapper extends BaseMapperX<AssetImportRowDO> {

    /**
     * 更新数据行。
     */
    @Update("UPDATE dh_asset_import_row SET row_json=CAST(#{json} AS jsonb), revision=revision+1, updater=#{actor}, update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND asset_id=#{assetId} AND revision=#{revision} AND deleted=0")
    int updateRow(@Param("id") Long id, @Param("assetId") Long assetId, @Param("revision") Integer revision,
            @Param("json") String json, @Param("actor") String actor);
}
