package cn.iocoder.yudao.module.asset.dal.mysql.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetImportRowLogDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AssetImportRowLogMapper extends BaseMapperX<AssetImportRowLogDO> {

    /**
     * 新增资产导入数据行日志的日志。
     */
    @Insert("INSERT INTO dh_asset_import_row_log(asset_id,row_id,revision,before_json,after_json,creator,updater) VALUES(#{assetId},#{rowId},#{revision},CAST(#{beforeJson} AS jsonb),CAST(#{afterJson} AS jsonb),#{creator},#{creator})")
    int insertLog(AssetImportRowLogDO log);
}
