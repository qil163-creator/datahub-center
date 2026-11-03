-- 只读的治理例外到期清单查询，不属于迁移或修复脚本。
-- 必需的 psql 变量：inventory_schema、after_action_id、through_action_id、page_size。
-- through_action_id=0 时获取平台的上界，后续分页继续使用该上界。
-- 需要 DBA 角色获准对下方五张已有质量表执行 SELECT。
-- 部署前置条件：已执行 sql/postgresql/migrations/0076_governance_quality-expiry-result-binding.sql，
-- 还须执行 0169_datahub_remove-business-tenancy.sql；此前须完成 0075 并具备完整质量表；SELECT 权限须覆盖五个新增的动作字段。
-- 不设置账号或凭据，不执行 DDL、DML、通知、状态迁移或行锁操作。
-- 历史动作缺少后续处理标识时，同源候选项始终标记为 UNPROVEN（未证实）。
-- 当前来源关系或负责人不是原始到期事务的冻结证据。
-- v1 直接结果只是引用以及关联和通知义务的冻结快照，
-- 不能证明行值、外部恢复、交付或整体业务已成功。
\set ON_ERROR_STOP on
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '3s';

-- BEGIN INVENTORY QUERY
WITH raw_params AS (
    SELECT :'after_action_id'::bigint AS after_id,
           :'through_action_id'::bigint AS requested_through,
           :'page_size'::integer AS requested_size
), bound AS MATERIALIZED (
    SELECT r.*, COALESCE(NULLIF(requested_through, 0),
        (SELECT COALESCE(MAX(a.id), 0)
           FROM :"inventory_schema".dh_governance_quality_work_order_action a
          WHERE a.action_type='EXCEPTION_EXPIRED')) AS through_id
    FROM raw_params r
), params AS MATERIALIZED (
    SELECT after_id, through_id,
           CASE WHEN after_id >= 0 AND requested_through >= 0
                     AND through_id >= after_id AND requested_size BETWEEN 1 AND 200
                THEN requested_size
                ELSE ('Invalid inventory parameters: ' || after_id
                      || '/' || requested_through || '/' || requested_size)::integer END AS page_size
    FROM bound
), selected AS MATERIALIZED (
    SELECT a.*
    FROM :"inventory_schema".dh_governance_quality_work_order_action a CROSS JOIN params p
    WHERE a.action_type='EXCEPTION_EXPIRED'
      AND a.id > p.after_id AND a.id <= p.through_id
    ORDER BY a.id LIMIT (SELECT page_size + 1 FROM params)
), page AS MATERIALIZED (
    SELECT * FROM selected ORDER BY id LIMIT (SELECT page_size FROM params)
), result_records AS (
    SELECT a.*, CASE
        WHEN a.follow_up_work_order_id IS NULL AND a.expiry_result_version IS NULL
         AND a.expiry_issue_hash IS NULL AND a.expiry_issue_count IS NULL
         AND a.expiry_receiver_user_id IS NULL THEN 'LEGACY_UNPROVEN'
        WHEN a.expiry_result_version=1 AND a.follow_up_work_order_id IS NOT NULL
         AND a.follow_up_work_order_id > 0 AND a.follow_up_work_order_id <> a.work_order_id
         AND a.expiry_issue_hash IS NOT NULL AND a.expiry_issue_count IS NOT NULL
         AND a.expiry_issue_hash ~ '^[0-9a-f]{64}$' AND a.expiry_issue_count >= 0
            THEN 'BOUND_V1_REFERENCE'
        ELSE 'INCOMPLETE_OR_UNSUPPORTED'
    END AS result_binding_status
    FROM page a
), inspected AS (
    SELECT a.id, jsonb_build_object(
        'action_id', a.id::text,
        'action_deleted', a.deleted <> 0,
        'source_order_id', a.work_order_id::text,
        'submission_id', a.submission_id::text,
        'source_order_missing', w.id IS NULL,
        'source_order_deleted', COALESCE(w.deleted <> 0, false),
        'submission_missing', s.id IS NULL,
        'submission_deleted', COALESCE(s.deleted <> 0, false),
        'submission_wrong_source', s.id IS NOT NULL AND s.work_order_id IS DISTINCT FROM a.work_order_id,
        'submission_not_exception', s.id IS NOT NULL AND s.submission_type IS DISTINCT FROM 'EXCEPTION',
        'same_submission_audit_count', (
            SELECT COUNT(*) FROM :"inventory_schema".dh_governance_quality_work_order_action other
             WHERE other.work_order_id=a.work_order_id
               AND other.action_type='EXCEPTION_EXPIRED'
               AND other.submission_id IS NOT DISTINCT FROM a.submission_id),
        'result_binding_status', a.result_binding_status,
        'direct_result', CASE WHEN a.result_binding_status='LEGACY_UNPROVEN' THEN NULL
            ELSE jsonb_build_object(
                'version', a.expiry_result_version,
                'follow_up_work_order_id', a.follow_up_work_order_id::text,
                'issue_hash', a.expiry_issue_hash,
                'issue_count', a.expiry_issue_count,
                'receiver_user_id', a.expiry_receiver_user_id::text,
                'target_missing', target.id IS NULL,
                'target_deleted', COALESCE(target.deleted <> 0, false),
                'type_matches', CASE WHEN target.id IS NOT NULL
                    THEN target.source_type IS NOT DISTINCT FROM 'EXCEPTION_EXPIRED' END,
                'current_root_matches_source', CASE WHEN target.id IS NOT NULL AND w.id IS NOT NULL
                    THEN target.root_order_id IS NOT DISTINCT FROM COALESCE(w.root_order_id,w.id) END,
                'current_relation_hash', CASE WHEN target.id IS NOT NULL THEN direct_relations.issue_hash END,
                'current_relation_count', CASE WHEN target.id IS NOT NULL THEN direct_relations.issue_count END,
                'current_relations_match_snapshot', CASE
                    WHEN a.result_binding_status='BOUND_V1_REFERENCE' AND target.id IS NOT NULL
                    THEN direct_relations.issue_hash=a.expiry_issue_hash
                     AND direct_relations.issue_count=a.expiry_issue_count END,
                'current_issue_count_matches', CASE WHEN target.id IS NOT NULL
                    THEN target.issue_count IS NOT DISTINCT FROM direct_relations.issue_count END,
                'expected_notify_intent_count', direct_notifications.total,
                'live_expected_notify_intent_count', direct_notifications.live_total
            ) END,
        'candidate_binding', CASE WHEN w.id IS NULL THEN 'SOURCE_ORDER_MISSING'
                                  WHEN candidates.total=0 THEN 'NO_SAME_ROOT_CANDIDATE'
                                  ELSE 'UNPROVEN' END,
        'candidate_count', candidates.total,
        'candidate_deleted_count', candidates.deleted_count,
        'candidates_truncated', candidates.total > 20,
        'candidates', COALESCE(details.items, '[]'::jsonb)
    ) AS item
    FROM result_records a
    LEFT JOIN :"inventory_schema".dh_governance_quality_work_order w
           ON w.id=a.work_order_id
    LEFT JOIN :"inventory_schema".dh_governance_quality_work_order_submission s
           ON s.id=a.submission_id
    -- 解析动作的确切引用，包括已删除行；不搜索候选详情。
    LEFT JOIN :"inventory_schema".dh_governance_quality_work_order target
           ON target.id=a.follow_up_work_order_id
    CROSS JOIN LATERAL (
        -- 与 Hutool 对 UTF-8 规范化多重集计算的 sha256Hex 保持一致；按数值排序并保留重复项。
        -- 空多重集仅对 'expiry-issues-v1:' 计算哈希；使用 PostgreSQL 16 内置功能，无需扩展。
        SELECT COUNT(*) AS issue_count,
               pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
                   'expiry-issues-v1:' || COALESCE(string_agg(i.issue_id::text, ',' ORDER BY i.issue_id), ''),
                   'UTF8')), 'hex') AS issue_hash
          FROM :"inventory_schema".dh_governance_quality_work_order_issue i
         WHERE i.work_order_id=target.id AND i.deleted=0
    ) direct_relations
    CROSS JOIN LATERAL (
        -- 将义务固定到原接收人，即使目标已缺失或已重新分配。
        -- 原接收人为 NULL 时无需创建通知意图，因此两项计数均为零。
        SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE n.deleted=0) AS live_total
          FROM :"inventory_schema".dh_governance_quality_work_order_notify n
         WHERE a.expiry_receiver_user_id IS NOT NULL
           AND n.work_order_id=a.follow_up_work_order_id AND n.receiver_user_id=a.expiry_receiver_user_id
           AND n.event_type='EXCEPTION_EXPIRED'
    ) direct_notifications
    CROSS JOIN LATERAL (
        SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE c.deleted <> 0) AS deleted_count
          FROM :"inventory_schema".dh_governance_quality_work_order c
         WHERE w.id IS NOT NULL AND c.id<>w.id
           AND c.root_order_id=COALESCE(w.root_order_id,w.id) AND c.source_type='EXCEPTION_EXPIRED'
    ) candidates
    LEFT JOIN LATERAL (
        SELECT jsonb_agg(jsonb_build_object(
            'order_id', c.id::text,
            'deleted', c.deleted <> 0,
            'binding', 'UNPROVEN',
            'current_source_relations_match',
                ARRAY(SELECT i.issue_id FROM :"inventory_schema".dh_governance_quality_work_order_issue i
                       WHERE i.work_order_id=w.id AND i.deleted=0
                       ORDER BY i.issue_id)
                = ARRAY(SELECT i.issue_id FROM :"inventory_schema".dh_governance_quality_work_order_issue i
                         WHERE i.work_order_id=c.id AND i.deleted=0
                         ORDER BY i.issue_id),
            'current_relation_count', (SELECT COUNT(*)
                FROM :"inventory_schema".dh_governance_quality_work_order_issue i
               WHERE i.work_order_id=c.id AND i.deleted=0),
            'current_issue_count_matches', c.issue_count IS NOT DISTINCT FROM (SELECT COUNT(*)
                FROM :"inventory_schema".dh_governance_quality_work_order_issue i
               WHERE i.work_order_id=c.id AND i.deleted=0),
            'currently_assigned', c.owner_user_id IS NOT NULL,
            'notify_intent_count', (SELECT COUNT(*)
                FROM :"inventory_schema".dh_governance_quality_work_order_notify n
               WHERE n.work_order_id=c.id AND n.event_type='EXCEPTION_EXPIRED'),
            'live_notify_intent_count', (SELECT COUNT(*)
                FROM :"inventory_schema".dh_governance_quality_work_order_notify n
               WHERE n.work_order_id=c.id AND n.event_type='EXCEPTION_EXPIRED' AND n.deleted=0),
            'live_notify_status_counts', COALESCE((SELECT jsonb_object_agg(ns.status, ns.total)
                FROM (SELECT COALESCE(n.send_status,'NULL') AS status, COUNT(*) AS total
                        FROM :"inventory_schema".dh_governance_quality_work_order_notify n
                       WHERE n.work_order_id=c.id
                         AND n.event_type='EXCEPTION_EXPIRED' AND n.deleted=0
                       GROUP BY COALESCE(n.send_status,'NULL')) ns), '{}'::jsonb)
        ) ORDER BY c.id) AS items
        FROM (SELECT c.* FROM :"inventory_schema".dh_governance_quality_work_order c
               WHERE w.id IS NOT NULL AND c.id<>w.id
                 AND c.root_order_id=COALESCE(w.root_order_id,w.id) AND c.source_type='EXCEPTION_EXPIRED'
               ORDER BY c.id LIMIT 20) c
    ) details ON true
)
SELECT jsonb_build_object(
    'report_version', 2,
    'as_of', transaction_timestamp(),
    'transaction_read_only', current_setting('transaction_read_only'),
    'transaction_isolation', current_setting('transaction_isolation'),
    'scope', 'PLATFORM_EXPIRED_ACTIONS_INCLUDING_LOGICALLY_DELETED',
    'snapshot_scope', 'THIS_PAGE_ONLY',
    'binding_proof', 'DIRECT_RESULT_V1_OR_UNPROVEN_LEGACY',
    'through_action_id', p.through_id::text,
    'next_after_action_id', COALESCE((SELECT MAX(id) FROM page), p.after_id)::text,
    'page_size', p.page_size,
    'scanned_count', (SELECT COUNT(*) FROM page),
    'has_more', (SELECT COUNT(*) FROM selected) > p.page_size,
    'rows', COALESCE((SELECT jsonb_agg(item ORDER BY id) FROM inspected), '[]'::jsonb)
) AS inventory
FROM params p;
-- END INVENTORY QUERY

COMMIT;
