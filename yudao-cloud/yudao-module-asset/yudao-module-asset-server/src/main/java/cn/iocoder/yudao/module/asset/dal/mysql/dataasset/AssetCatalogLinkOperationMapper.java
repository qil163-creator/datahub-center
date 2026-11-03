package cn.iocoder.yudao.module.asset.dal.mysql.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetCatalogLinkOperationDO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AssetCatalogLinkOperationMapper extends BaseMapperX<AssetCatalogLinkOperationDO> {

    /**
     * 读取资产目录关联操作的下一个修订号。
     */
    @Select("SELECT COALESCE(MAX(source_revision),0)+1 FROM dh_asset_catalog_link_operation WHERE source_key=#{sourceKey} AND table_id=#{table}")
    long nextRevision(@Param("sourceKey") String sourceKey, @Param("table") long table);

    /**
     * 查询资产目录关联操作达到处理时间的待办记录。
     */
    @Select("""
            SELECT id FROM dh_asset_catalog_link_operation WHERE deleted=0
            AND ((status IN ('PENDING','UNKNOWN') AND (next_retry_time IS NULL OR next_retry_time<=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')))
            OR (status='RUNNING' AND lease_until<=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai'))) ORDER BY id LIMIT 100
            """)
    List<Long> due();

    /**
     * 领取待处理的资产目录关联操作任务或记录。
     */
    @Select(value = """
            UPDATE dh_asset_catalog_link_operation SET status='RUNNING',claim_token=#{token},attempts=attempts+1,
            lease_until=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')+INTERVAL '2 minutes',update_time=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')
            WHERE id=#{id} AND deleted=0
            AND (status IN ('PENDING','UNKNOWN') OR (status='RUNNING' AND lease_until<=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')))
            RETURNING *
            """, affectData = true)
    AssetCatalogLinkOperationDO claim(@Param("id") long id, @Param("token") String token);

    /**
     * 按业务条件更新资产目录关联操作的完成状态或数据，返回受影响行数。
     */
    @Update("""
            UPDATE dh_asset_catalog_link_operation SET status=#{status},outcome=#{outcome},last_error=#{error},
            claim_token=NULL,lease_until=NULL,next_retry_time=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')+INTERVAL '1 minute',update_time=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')
            WHERE id=#{id} AND claim_token=#{token} AND status='RUNNING'
            AND lease_until>(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')
            """)
    int complete(@Param("id") long id, @Param("token") String token,
            @Param("status") String status, @Param("outcome") String outcome, @Param("error") String error);

    /**
     * 按指定条件统计资产目录关联操作的未完成记录数。
     */
    @Select("""
            SELECT COUNT(*) FROM dh_asset_catalog_link_operation WHERE asset_id=#{asset}
            AND deleted=0 AND status<>'SUCCESS'
            AND NOT EXISTS(SELECT 1 FROM dh_asset_catalog_link_operation n WHERE n.asset_id=#{asset} AND n.table_id=dh_asset_catalog_link_operation.table_id
              AND n.source_revision>dh_asset_catalog_link_operation.source_revision AND n.status='SUCCESS')
            """)
    long unresolved(@Param("asset") long asset);
}
