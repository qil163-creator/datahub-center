package cn.iocoder.yudao.module.asset.dal.mysql.market;

import org.apache.ibatis.annotations.*;
import java.util.Collection;
import java.util.List;

@Mapper
public interface MarketFileBindingMapper {
    @Select("SELECT * FROM dh_asset_market_file_binding WHERE catalog_version_id=#{versionId}")
    MarketFileBinding find(long versionId);

    @Select("""
            <script>SELECT * FROM dh_asset_market_file_binding WHERE managed_file_id IS NOT NULL AND catalog_version_id IN
            <foreach collection="versionIds" item="v" open="(" separator="," close=")">#{v}</foreach></script>
            """)
    List<MarketFileBinding> readyByVersions(@Param("versionIds") Collection<Long> versionIds);

    @Insert("""
            INSERT INTO dh_asset_market_file_binding(catalog_version_id,asset_id,operation_key,file_name,file_size,sha256,uploader_id)
            VALUES (#{catalogVersionId},#{assetId},#{operationKey},#{fileName},#{fileSize},#{sha256},#{uploaderId})
            ON CONFLICT(catalog_version_id) DO NOTHING
            """)
    int reserve(MarketFileBinding item);

    @Update("""
            UPDATE dh_asset_market_file_binding SET managed_file_id=#{managedFileId},file_name=#{fileName},
                file_size=#{fileSize},sha256=#{sha256},uploader_id=CASE WHEN managed_file_id IS NULL THEN #{uploaderId} ELSE uploader_id END
            WHERE catalog_version_id=#{catalogVersionId} AND asset_id=#{assetId} AND operation_key=#{operationKey}
              AND (managed_file_id IS NULL OR (managed_file_id=#{managedFileId} AND sha256=#{sha256}
                  AND file_name=#{fileName} AND file_size=#{fileSize}))
            """)
    int complete(MarketFileBinding file);

    @Select("""
            SELECT asset_id FROM dh_market_resource_apply
            WHERE id=#{applyId} AND apply_user_id=#{userId} AND deleted=0
            FOR UPDATE
            """)
    Long lockDownloadAssetId(@Param("applyId") long applyId,@Param("userId") long userId);

    record Usage(Long applyId, Long downloadCount) {}

    @Select("""
            <script>SELECT apply_id,download_count FROM dh_asset_market_file_usage WHERE apply_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
            """)
    List<Usage> usage(@Param("ids") Collection<Long> ids);

    @Insert("""
            INSERT INTO dh_asset_market_file_usage(apply_id,download_count) VALUES(#{id},1)
            ON CONFLICT(apply_id) DO UPDATE SET download_count=dh_asset_market_file_usage.download_count+1,
                last_used_at=CURRENT_TIMESTAMP
            WHERE CAST(#{limit} AS bigint) IS NULL OR dh_asset_market_file_usage.download_count < #{limit}
            """)
    int consume(@Param("id") long id,@Param("limit") Long limit);
}
