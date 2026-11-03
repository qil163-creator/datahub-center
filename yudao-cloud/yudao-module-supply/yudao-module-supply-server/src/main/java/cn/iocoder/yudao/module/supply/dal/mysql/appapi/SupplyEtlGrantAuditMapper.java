package cn.iocoder.yudao.module.supply.dal.mysql.appapi;

import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.SupplyEtlGrantAuditDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 不提供更新、删除审计的入口；数据库触发器同时保护历史。 */
@Mapper
public interface SupplyEtlGrantAuditMapper {

    /**
     * 查询供应ETL授权审计的历史。
     *
     * @param appId 应用编号
     * @param grantId 授权编号
     * @param beforeId 变更前编号
     * @param fetchLimit 获取上限
     * @return 供应ETL授权审计的历史结果集合
     */
    @Select("""
            <script>
            SELECT id,grant_id,app_id,api_id,actor_id,revision,action,
                   before_json::text AS before_json,after_json::text AS after_json,reason,trace_id,result,create_time
              FROM dh_supply_etl_grant_audit
             WHERE app_id=#{appId} AND deleted=0
             <if test="grantId != null">AND grant_id=#{grantId}</if>
             <if test="beforeId != null">AND id &lt; #{beforeId}</if>
             ORDER BY id DESC LIMIT #{fetchLimit}
            </script>
            """)
    List<SupplyEtlGrantAuditDO> history(@Param("appId") Long appId,
            @Param("grantId") Long grantId, @Param("beforeId") Long beforeId, @Param("fetchLimit") int fetchLimit);

    /**
     * 新增供应ETL授权审计的追加记录。
     *
     * @param audit 审计
     * @return 追加数值
     */
    @Insert("""
            INSERT INTO dh_supply_etl_grant_audit
              (grant_id,app_id,api_id,actor_id,revision,action,before_json,after_json,reason,trace_id,result,creator,updater)
            VALUES(#{grantId},#{appId},#{apiId},#{actorId},#{revision},#{action},
              CAST(#{beforeJson} AS jsonb),CAST(#{afterJson} AS jsonb),#{reason},#{traceId},#{result},
              CAST(#{actorId} AS text),CAST(#{actorId} AS text))
            """)
    int append(SupplyEtlGrantAuditDO audit);
}
