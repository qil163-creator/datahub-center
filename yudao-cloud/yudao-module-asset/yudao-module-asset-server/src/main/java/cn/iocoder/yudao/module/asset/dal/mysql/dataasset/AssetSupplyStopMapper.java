package cn.iocoder.yudao.module.asset.dal.mysql.dataasset;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AssetSupplyStopMapper {

    /**
     * 查询资产供数停止达到处理时间的待办记录。
     */
    @Select("""
            SELECT a.id FROM dh_market_resource_apply a JOIN dh_asset_data_asset d ON d.id=a.asset_id
            WHERE a.deleted=0 AND a.status=2 AND a.source_work_order_id IS NULL AND d.deleted=0
            AND d.status=2 AND d.stop_supply_user_id IS NOT NULL ORDER BY a.id LIMIT 100
            """)
    List<Long> due();

    /**
     * 按指定条件统计资产供数停止的待处理记录数。
     */
    @Select("""
            SELECT COUNT(*) FROM dh_market_resource_apply a
            LEFT JOIN LATERAL (SELECT o.* FROM dh_market_delivery_operation o
                WHERE o.apply_id=a.id AND o.deleted=0
                ORDER BY o.source_revision DESC NULLS LAST,o.id DESC LIMIT 1) latest ON true
            WHERE a.asset_id=#{asset} AND a.deleted=0 AND a.source_work_order_id IS NULL
            AND (a.status=2 OR (latest.id IS NOT NULL AND (latest.status<>'SUCCESS' OR latest.op_type<>'REVOKE'
                  OR COALESCE(latest.resolution_status,'UNKNOWN')<>'CONFIRMED'))
                OR (a.status IN (5,6) AND a.api_id IS NOT NULL AND a.app_id IS NOT NULL AND latest.id IS NULL)
                OR EXISTS(SELECT 1 FROM dh_asset_grant_source s WHERE s.apply_id=a.id
                  AND s.deleted=0 AND (COALESCE(s.revoked,0)<>1 OR COALESCE(s.source_status,'ACTIVE')<>'REVOKED')))
            """)
    long pending(@Param("asset") long asset);

    /**
     * 按指定条件统计资产供数停止的待关闭申请记录数。
     */
    @Select("""
            SELECT COUNT(*) FROM dh_market_resource_apply WHERE asset_id=#{asset}
            AND deleted=0 AND source_work_order_id IS NULL AND status IN (0,1,2,8)
            """)
    long openApplications(@Param("asset") long asset);

    /**
     * 按指定条件统计资产供数停止的未关闭来源记录数。
     */
    @Select("SELECT COUNT(*) FROM dh_asset_grant_source WHERE asset_id=#{asset} AND deleted=0 AND (COALESCE(revoked,0)<>1 OR COALESCE(source_status,'ACTIVE')<>'REVOKED')")
    long unclosedSources(@Param("asset") long asset);
}
