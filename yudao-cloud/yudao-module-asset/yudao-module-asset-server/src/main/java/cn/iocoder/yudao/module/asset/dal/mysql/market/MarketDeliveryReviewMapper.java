package cn.iocoder.yudao.module.asset.dal.mysql.market;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 复用市场领域审计，不建立第二套审计账本；没有更新或删除入口。 */
@Mapper
public interface MarketDeliveryReviewMapper {

    String COLUMNS = """
            SELECT id,event_type,event_id AS request_id,review_operation_id AS operation_id,
                   review_request_hash AS request_hash,
                   CAST(review_evidence->>'applyId' AS bigint) AS apply_id,
                   CAST(review_evidence->>'sourceRevision' AS bigint) AS source_revision,
                   result AS decision,review_evidence->>'classification' AS classification,
                   review_evidence->>'approvalStatus' AS approval_status,
                   actor_user_id,dept_id AS actor_dept_id,reason,review_evidence->>'basis' AS basis,
                   occurred_at,deleted,review_evidence->>'resultingOperationId' AS resulting_operation_id
            FROM dh_market_audit_event
            WHERE 1=1
            """;
    String PROJECTION = COLUMNS + " AND event_type='DELIVERY_REVIEW_DECISION'";
    String DISPOSAL_PROJECTION = COLUMNS + " AND event_type='DELIVERY_DISPOSAL'";
    String REDELIVERY_PROJECTION = COLUMNS + " AND event_type='DELIVERY_REDELIVERY'";

    // 已删除的记录也占用请求身份；禁止复用旧身份产生另一份审计。

    /**
     * 查询市场交付复核的请求。
     */
    @Select(PROJECTION + " AND event_id=#{requestId}")
    MarketDeliveryReviewRow selectRequest(@Param("requestId") String requestId);

    /**
     * 查询处置请求。
     */
    @Select(DISPOSAL_PROJECTION + " AND event_id=#{requestId}")
    MarketDeliveryReviewRow selectDisposalRequest(@Param("requestId") String requestId);

    /** 受控重投意图审计；同请求编号重试只回放，不重复重发。 */
    @Select(REDELIVERY_PROJECTION + " AND event_id=#{requestId}")
    MarketDeliveryReviewRow selectRedeliverRequest(@Param("requestId") String requestId);

    /** 读取核查决定的完整证据 JSON（服务端比对用，不回传浏览器）。 */
    @Select("SELECT review_evidence::text FROM dh_market_audit_event"
            + " WHERE event_id=#{requestId} AND event_type='DELIVERY_REVIEW_DECISION'")
    String selectDecisionEvidence(@Param("requestId") String requestId);

    // 待办结论与实际处置按原操作合并列出；与 0069 历史索引谓词一致。

    /**
     * 查询市场交付复核历史。
     */
    @Select(COLUMNS + " AND event_type IN ('DELIVERY_REVIEW_DECISION','DELIVERY_DISPOSAL')"
            + " AND review_operation_id=#{operationId} AND deleted=0 AND id>#{afterId} ORDER BY id LIMIT 51")
    List<MarketDeliveryReviewRow> selectHistory(
            @Param("operationId") String operationId, @Param("afterId") long afterId);

    /**
     * 新增决策。
     */
    @Insert("""
            INSERT INTO dh_market_audit_event
              (event_id,event_type,actor_user_id,dept_id,object_type,object_id,from_status,to_status,
               reason,result,occurred_at,correlation_id,review_operation_id,review_request_hash,review_evidence)
            VALUES (#{row.requestId},'DELIVERY_REVIEW_DECISION',#{row.actorUserId},#{row.actorDeptId},
                    'DELIVERY',#{operationRowId},#{status},#{status},#{row.reason},#{row.decision},
                    clock_timestamp() AT TIME ZONE 'Asia/Shanghai',#{row.requestId},#{row.operationId},
                    #{row.requestHash},CAST(#{evidenceJson} AS jsonb))
            ON CONFLICT (event_id) WHERE event_type='DELIVERY_REVIEW_DECISION' DO NOTHING
            """)
    int insertDecision(@Param("row") MarketDeliveryReviewRow row,
            @Param("operationRowId") long operationRowId, @Param("status") String status,
            @Param("evidenceJson") String evidenceJson);

    /** 实际处置与授权变化同事务追加；重复请求编号由唯一索引拒绝并回滚，不覆盖首份审计。 */
    @Insert("""
            INSERT INTO dh_market_audit_event
              (event_id,event_type,actor_user_id,dept_id,object_type,object_id,from_status,to_status,
               reason,result,occurred_at,correlation_id,review_operation_id,review_request_hash,review_evidence,
               creator,updater)
            VALUES (#{row.requestId},'DELIVERY_DISPOSAL',#{row.actorUserId},#{row.actorDeptId},
                    'DELIVERY',#{operationRowId},#{fromStatus},#{toStatus},#{row.reason},#{row.decision},
                    clock_timestamp() AT TIME ZONE 'Asia/Shanghai',#{row.requestId},#{row.operationId},
                    #{row.requestHash},CAST(#{evidenceJson} AS jsonb),#{creator},#{creator})
            """)
    int insertDisposal(@Param("row") MarketDeliveryReviewRow row,
            @Param("operationRowId") long operationRowId, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("evidenceJson") String evidenceJson,
            @Param("creator") String creator);

    /** 受控重投意图与锁定状态同事务追加；重复请求编号由唯一索引拒绝并回滚，由调用方回放首份意图。 */
    @Insert("""
            INSERT INTO dh_market_audit_event
              (event_id,event_type,actor_user_id,dept_id,object_type,object_id,from_status,to_status,
               reason,result,occurred_at,correlation_id,review_operation_id,review_request_hash,review_evidence,
               creator,updater)
            VALUES (#{row.requestId},'DELIVERY_REDELIVERY',#{row.actorUserId},#{row.actorDeptId},
                    'DELIVERY',#{operationRowId},#{fromStatus},#{toStatus},#{row.reason},#{row.decision},
                    clock_timestamp() AT TIME ZONE 'Asia/Shanghai',#{row.requestId},#{row.operationId},
                    #{row.requestHash},CAST(#{evidenceJson} AS jsonb),#{creator},#{creator})
            """)
    int insertRedeliver(@Param("row") MarketDeliveryReviewRow row,
            @Param("operationRowId") long operationRowId, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("evidenceJson") String evidenceJson,
            @Param("creator") String creator);
}
