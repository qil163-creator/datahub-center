package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 原证据只读投影，无写接口；每个关联对象均校验关联关系和未删除状态。 */
@Mapper
public interface MarketDeliveryEvidenceMapper {

    // 在数据库侧截断异常大载荷，避免 JDBC 先加载无限 JSON。4 MiB 覆盖协议正常字段上限及转义。
    String OPERATION_SQL = """
            SELECT id, operation_id, apply_id, source_grant_id, op_type, app_id, api_id,
                   protocol_version, source_revision, payload_hash, row_version, status, resolution_status,
                   attempt_count, result_ref, last_error, update_time,
                   CASE WHEN octet_length(payload_json::text) <= 4194304
                        THEN payload_json::text ELSE NULL END AS payload_json
            FROM dh_market_delivery_operation
            WHERE operation_id=#{operationId} AND deleted=0
            """;

    /**
     * 查询操作。
     */
    @Select(OPERATION_SQL)
    MarketDeliveryOperationDO selectOperation(
            @Param("operationId") String operationId);

    String CONTEXT_SQL = """
            SELECT a.id AS apply_id, a.asset_id, a.app_id, a.api_id, a.revision, a.source_revision,
                   a.status, a.paused, a.if_auth, a.update_time AS apply_update_time,
                   d.tech_leader_user_id, d.business_leader_user_id, d.owner_dept_id,
                   d.update_time AS asset_update_time
            FROM dh_market_resource_apply a
            JOIN dh_asset_data_asset d ON d.id=a.asset_id AND d.deleted=0
            WHERE a.id=#{applyId} AND a.deleted=0
            """;

    /**
     * 查询上下文。
     */
    @Select(CONTEXT_SQL)
    MarketDeliveryEvidenceContext selectContext(
            @Param("applyId") long applyId);

    /** 仅在持久核查的短事务中调用；持锁期间不执行任何 RPC。 */
    @Select(CONTEXT_SQL + " FOR UPDATE")
    MarketDeliveryEvidenceContext lockContext(@Param("applyId") long applyId);

    /**
     * 按指定条件读取并锁定市场交付证据记录，供事务内一致性校验或修改使用。
     */
    @Select(OPERATION_SQL + " FOR UPDATE")
    MarketDeliveryOperationDO lockOperation(@Param("operationId") String operationId);

    /**
     * 为市场交付证据证据核验设置数据库锁等待上限。
     */
    @Select("SELECT set_config('lock_timeout','2s',true)")
    String boundLockWait();
}
