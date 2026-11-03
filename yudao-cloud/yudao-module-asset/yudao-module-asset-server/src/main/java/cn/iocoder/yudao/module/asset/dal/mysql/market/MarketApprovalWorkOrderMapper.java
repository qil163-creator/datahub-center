package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketApprovalWorkOrderDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/** 来源工单与本地申请只合并读取；来源意见不会写入真实申请、批准或交付表。 */
@Mapper
public interface MarketApprovalWorkOrderMapper extends BaseMapperX<MarketApprovalWorkOrderDO> {

    String TICKETS = """
            SELECT w.source_record_id id,w.source_record_id display_id,'QDATA' origin,w.mart_name,
                   'QDATA:'||w.applicant_key applicant_key,w.applicant_name,w.apply_time,
                   COALESCE(w.local_status,w.source_status) status
            FROM dh_market_approval_work_order w WHERE w.source_system='QDATA' AND w.deleted=0
            UNION ALL
            SELECT r.id::text id,COALESCE(NULLIF(r.apply_no,''),r.id::text) display_id,'NATIVE' origin,
                   COALESCE(NULLIF(l.display_name,''),a.asset_name,r.title) mart_name,
                   'NATIVE:'||r.apply_user_id::text applicant_key,NULL::varchar applicant_name,r.create_time apply_time,
                   CASE r.status WHEN 0 THEN '1' WHEN 1 THEN '2' WHEN 2 THEN '3' WHEN 3 THEN '4'
                     WHEN 4 THEN '5' WHEN 5 THEN '6' WHEN 6 THEN '5' WHEN 8 THEN '2' ELSE '1' END status
            FROM dh_market_resource_apply r
            JOIN dh_asset_data_asset a ON a.id=r.asset_id AND a.deleted=0
            LEFT JOIN dh_asset_market_listing l ON l.asset_id=r.asset_id AND l.deleted=0
            WHERE r.deleted=0 AND r.source_work_order_id IS NULL
            <choose>
              <when test="providerIds != null and !providerIds.isEmpty()">
                AND r.asset_id IN
                <foreach collection="providerIds" item="assetId" open="(" close=")" separator=",">#{assetId}</foreach>
              </when>
              <otherwise>AND FALSE</otherwise>
            </choose>
            """;
    String DIRECTION = "<choose><when test=\"req.sortOrder == 'asc'\"> ASC </when><otherwise> DESC </otherwise></choose>";
    String ORDER = "<choose><when test=\"req.sortBy == 'applyTime'\">t.apply_time " + DIRECTION
            + " NULLS LAST</when><otherwise>"
            + "CASE WHEN t.display_id ~ '^[0-9]+$' THEN t.display_id::numeric END " + DIRECTION
            + " NULLS LAST,t.display_id " + DIRECTION + "</otherwise></choose>,t.origin,t.id";
    String FILTERS = """
            WHERE 1=1
            <if test="req.martName != null and req.martName.trim() != ''">
              AND t.mart_name ILIKE '%'||#{req.martName}||'%'
            </if>
            <if test="req.status != null">AND t.status=#{req.status}</if>
            <if test="req.applicantKey != null and req.applicantKey.trim() != ''">
              AND t.applicant_key=#{req.applicantKey}
            </if>
            """;

    /**
     * 按指定条件统计市场审批工作工单的工单记录数。
     */
    @Select("<script>SELECT COUNT(*) FROM (" + TICKETS + ") t " + FILTERS + "</script>")
    long countTickets(@Param("req") MarketCheckPageReqVO req, @Param("providerIds") Collection<Long> providerIds);

    /**
     * 查询工单。
     */
    @Select("<script>SELECT t.id,t.origin,t.mart_name,t.applicant_key,t.applicant_name FROM (" + TICKETS + ") t "
            + FILTERS + " ORDER BY " + ORDER
            + " LIMIT #{req.pageSize} OFFSET #{offset}</script>")
    List<MarketCheckTicketRow> selectTickets(@Param("req") MarketCheckPageReqVO req,
            @Param("providerIds") Collection<Long> providerIds, @Param("offset") long offset);

    /**
     * 查询申请人。
     */
    @Select("<script>SELECT DISTINCT ON (t.applicant_key) t.applicant_key,t.applicant_name FROM (" + TICKETS
            + ") t ORDER BY t.applicant_key,t.apply_time DESC NULLS LAST,t.id DESC LIMIT 500</script>")
    List<MarketCheckTicketRow> selectApplicants(@Param("providerIds") Collection<Long> providerIds);

    /**
     * 查询来源。
     */
    @Select("""
            SELECT id,source_system,source_record_id,mart_source_id,mart_name,applicant_key,applicant_name,
                   applicant_dept_name,apply_type,start_time,end_time,apply_time,apply_reason,source_status,
                   source_approval_reason,source_approval_time,detail_snapshot::text detail_snapshot,
                   local_status,local_approval_reason,local_check_user_id,local_check_time,revision
            FROM dh_market_approval_work_order
            WHERE source_system='QDATA' AND source_record_id=#{sourceId} AND deleted=0
            """)
    MarketApprovalWorkOrderDO selectSource(@Param("sourceId") String sourceId);

    /**
     * 按业务条件更新市场审批工作工单的决策来源状态或数据，返回受影响行数。
     */
    @Update("""
            UPDATE dh_market_approval_work_order
            SET local_status=#{status},local_approval_reason=#{reason},local_check_user_id=#{actor},
                local_check_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai',revision=revision+1,
                updater=#{actor}::text,update_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'
            WHERE id=#{id} AND source_system='QDATA' AND deleted=0 AND revision=#{revision}
              AND local_status IS NULL AND source_status IN ('1','2')
            """)
    int decideSource(@Param("id") long id, @Param("revision") int revision, @Param("status") String status,
            @Param("reason") String reason, @Param("actor") long actor);
}
