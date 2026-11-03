package cn.iocoder.yudao.module.asset.dal.mysql.market;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 复用领域审计记录正式批准；只有追加和查询，不能将核查待办升级为批准。 */
@Mapper
public interface MarketApprovalEvidenceMapper {

    /**
     * 新增审批。
     */
    @Insert("""
            INSERT INTO dh_market_audit_event
              (event_id,event_type,actor_user_id,dept_id,object_type,object_id,from_status,to_status,
               reason,result,occurred_at,correlation_id,approval_ref,approval_asset_id,approval_scope_hash)
            VALUES (#{reference},'GRANT_APPROVAL_EVIDENCE',#{actor},#{department},'APPLY',#{applyId},'1','2',
                    #{reason},'APPROVED',clock_timestamp() AT TIME ZONE 'Asia/Shanghai',#{reference},
                    #{reference},#{assetId},#{scopeHash})
            ON CONFLICT (object_id) WHERE event_type='GRANT_APPROVAL_EVIDENCE' DO NOTHING
            """)
    int insertApproval(@Param("applyId") long applyId,
            @Param("assetId") long assetId, @Param("reference") String reference,
            @Param("scopeHash") String scopeHash, @Param("actor") long actor,
            @Param("department") Long department, @Param("reason") String reason);

    // 按申请而非可变的批准引用查找，防止修改 revision 后以“没有凭证”为由绕过原批准。

    /**
     * 按申请查询市场审批证据。
     */
    @Select("""
            SELECT id,approval_ref,approval_asset_id,approval_scope_hash FROM dh_market_audit_event
            WHERE object_type='APPLY' AND object_id=#{applyId}
              AND event_type='GRANT_APPROVAL_EVIDENCE'
            """)
    // 凭证内容不可变，但“尚无凭证”的旧查询结果不能遮蔽另一节点刚提交的正式批准。
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MarketApprovalEvidenceRow selectByApply(@Param("applyId") long applyId);

    /** 同一列表快照内批量核对批准摘要，不返回原始审计载荷。 */
    @Select("""
            <script>
            SELECT id,object_id apply_id,approval_ref,approval_asset_id,approval_scope_hash
            FROM dh_market_audit_event
            WHERE object_type='APPLY' AND event_type='GRANT_APPROVAL_EVIDENCE' AND object_id IN
              <foreach collection="applyIds" item="id" open="(" close=")" separator=",">#{id}</foreach>
            </script>
            """)
    java.util.List<MarketApprovalEvidenceRow> selectByApplies(@Param("applyIds") java.util.Collection<Long> applyIds);
}
