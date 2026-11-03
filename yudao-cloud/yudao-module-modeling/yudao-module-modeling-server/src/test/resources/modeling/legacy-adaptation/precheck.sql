-- 建模平台只读预检；目标为完成 0144 后的当前平台库，也可查看历史库的对象存在状态。
-- 前置：datahub-center.dh_modeling_logic_model 存在；由数据库管理员在明确的目标平台库执行。
-- 可重复执行；仅查询 catalog 和逻辑模型汇总，不读取概念、业务过程、物理模型或旧关系表记录。
-- 不读取连接凭据，不修改数据、结构或权限；不推导归属，不执行旧数据适配或迁移。
-- 运维预检与 modeling-server 的 legacy-adaptation/precheck.sql 测试资源保持相同内容。
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '60s';
SET LOCAL lock_timeout = '5s';

SELECT current_database() AS "目标数据库", current_schema() AS "当前默认Schema",
       current_setting('transaction_read_only') AS "只读事务";

-- 当前发布链路必须保留；退役及历史可选对象仅报告存在状态，缺失不会触发对它们的查询。
SELECT purpose AS "对象用途", name AS "平台表",
       to_regclass(format('%I.%I', 'datahub-center', name)) IS NOT NULL AS "是否存在"
FROM (VALUES
    (1, '当前逻辑模型', 'dh_modeling_logic_model'),
    (2, '当前逻辑模型版本', 'dh_modeling_logic_model_version'),
    (3, '当前逻辑模型字段', 'dh_modeling_logic_model_column'),
    (4, '当前物化发布', 'dh_modeling_model_materialized'),
    (5, '当前物化发布日志', 'dh_modeling_model_materialized_log'),
    (6, '0144退役物理模型', 'dh_modeling_physical_model'),
    (7, '0144退役物理模型版本', 'dh_modeling_physical_model_version'),
    (8, '已退役概念模型', 'dh_modeling_concept_model'),
    (9, '已退役概念模型版本', 'dh_modeling_concept_model_version'),
    (10, '已退役业务过程', 'dh_modeling_business_process'),
    (11, '历史可选关系', 'dh_modeling_business_process_data_domain_rel'),
    (12, '历史可选关系', 'dh_modeling_business_process_supporting_domain_rel'),
    (13, '历史可选候选表', 'dh_modeling_business_process_domain_candidate'),
    (14, '历史可选关系', 'dh_modeling_subject_domain_process_rel'),
    (15, '历史可选关系', 'dh_modeling_biz_category_data_domain_rel')
) objects(position, purpose, name)
ORDER BY position;

-- 可选列由 catalog 判断，不要求历史库已经完成全部归属列迁移。
SELECT requested.name AS "逻辑模型归属或版本列", columns.column_name IS NOT NULL AS "是否存在"
FROM (VALUES ('data_domain_id'), ('biz_category_id'), ('layer_id'),
             ('revision'), ('layout_revision'), ('subject_domain_id'), ('business_process_id')) requested(name)
LEFT JOIN information_schema.columns columns
       ON columns.table_schema = 'datahub-center'
      AND columns.table_name = 'dh_modeling_logic_model'
      AND columns.column_name = requested.name
ORDER BY requested.name;

-- 只统计当前逻辑模型头；to_jsonb 兼容缺少可选列的历史测试夹具。
-- “已有归属/保留值”仅表示非空值，不证明目标存在、有效或已获确认；列缺失时计数为零，应结合上表判断。
WITH models AS (
    SELECT m.tenant_id, to_jsonb(m) AS row_data
    FROM "datahub-center".dh_modeling_logic_model m
)
SELECT CASE WHEN GROUPING(tenant_id) = 1 THEN '全部租户' ELSE tenant_id::text END AS "租户范围",
       count(*) AS "逻辑模型总数",
       count(*) FILTER (WHERE row_data->>'deleted' = '0') AS "未删除逻辑模型",
       count(*) FILTER (WHERE row_data->>'deleted' = '0' AND row_data->>'data_domain_id' IS NOT NULL) AS "已有数据分域归属",
       count(*) FILTER (WHERE row_data->>'deleted' = '0' AND row_data->>'biz_category_id' IS NOT NULL) AS "已有业务分类归属",
       count(*) FILTER (WHERE row_data->>'deleted' = '0' AND row_data->>'subject_domain_id' IS NOT NULL) AS "保留主题域值",
       count(*) FILTER (WHERE row_data->>'deleted' = '0' AND row_data->>'business_process_id' IS NOT NULL) AS "保留业务过程值"
FROM models
GROUP BY GROUPING SETS ((tenant_id), ())
ORDER BY GROUPING(tenant_id) DESC, tenant_id;
COMMIT;
