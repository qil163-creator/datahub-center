package cn.iocoder.yudao.module.asset.dal.mysql.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetImportDatasetDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AssetImportDatasetMapper extends BaseMapperX<AssetImportDatasetDO> {

    /**
     * 更新资产导入数据集的字段。
     */
    @Update("UPDATE dh_asset_import_dataset SET columns_json=CAST(#{json} AS jsonb), revision=revision+1, updater=#{actor}, update_time=CURRENT_TIMESTAMP WHERE asset_id=#{id} AND revision=#{revision} AND deleted=0")
    int updateColumns(@Param("id") Long id, @Param("revision") Integer revision,
            @Param("json") String json, @Param("actor") String actor);
}
