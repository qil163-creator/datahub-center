-- Migration: 0212 逻辑模型详情、两步编辑与汇总自动字段契约。
-- Purpose: 补齐字段类别、维度、分类分级、代码表、汇总粒度及指标来源口径快照，约束同类实体时效唯一，复用统一模型和版本字段存储。
-- Depends on: 0211_modeling_materialized-log-snapshot.sql；0208_modeling_logic-table-qdata.sql 已建立五类逻辑模型。
-- Target: 已有 PostgreSQL 平台库；新库使用同步后的 datahub-center.sql。
-- Preconditions: 有效 DIMENSION/DETAIL 记录不存在相同类型、业务实体与统计时效；如有重复，索引创建将失败并整体回滚，不自动删改业务数据。
-- Repeatable: yes；使用 ADD COLUMN IF NOT EXISTS、CREATE UNIQUE INDEX IF NOT EXISTS 和可重放注释。
-- Rollback: 事务失败自动回滚；成功后可删除本迁移新增列，但会不可逆丢弃新字段元数据，执行前应备份。
-- Risk: 加法式 DDL 获取短时表锁，lock_timeout=10s；仅为可唯一解析的存量汇总定义补充元数据，保留字段身份和历史发布快照。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_modeling_logic_model ADD COLUMN IF NOT EXISTS grain_entity_ids varchar(1024);
COMMENT ON COLUMN dh_modeling_logic_model.grain_entity_ids IS '汇总统计粒度业务实体编号列表，逗号分隔';

ALTER TABLE dh_modeling_logic_model_column
    ADD COLUMN IF NOT EXISTS field_category varchar(32),
    ADD COLUMN IF NOT EXISTS dimension_id bigint,
    ADD COLUMN IF NOT EXISTS dimension_name varchar(255),
    ADD COLUMN IF NOT EXISTS data_classification_id bigint,
    ADD COLUMN IF NOT EXISTS data_classification_name varchar(255),
    ADD COLUMN IF NOT EXISTS data_grade varchar(64),
    ADD COLUMN IF NOT EXISTS code_table_id bigint,
    ADD COLUMN IF NOT EXISTS code_table_name varchar(255),
    ADD COLUMN IF NOT EXISTS unique_flag smallint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS indicator_id bigint,
    ADD COLUMN IF NOT EXISTS atomic_indicator_name varchar(255),
    ADD COLUMN IF NOT EXISTS business_condition varchar(2000),
    ADD COLUMN IF NOT EXISTS statistics_period varchar(32),
    ADD COLUMN IF NOT EXISTS source_model_name varchar(255);
COMMENT ON COLUMN dh_modeling_logic_model_column.field_category IS '字段类别：PRIMARY_KEY/ATTRIBUTE/DIMENSION/MEASURE/TIME/GRAIN/INDICATOR/NORMAL';
COMMENT ON COLUMN dh_modeling_logic_model_column.dimension_id IS '关联维度业务实体编号';
COMMENT ON COLUMN dh_modeling_logic_model_column.dimension_name IS '关联维度名称';
COMMENT ON COLUMN dh_modeling_logic_model_column.data_classification_id IS '治理数据分类编号';
COMMENT ON COLUMN dh_modeling_logic_model_column.data_classification_name IS '治理数据分类名称';
COMMENT ON COLUMN dh_modeling_logic_model_column.data_grade IS '治理数据分级编码';
COMMENT ON COLUMN dh_modeling_logic_model_column.code_table_id IS '标准代码表编号';
COMMENT ON COLUMN dh_modeling_logic_model_column.code_table_name IS '标准代码表名称';
COMMENT ON COLUMN dh_modeling_logic_model_column.unique_flag IS '是否唯一：1 是，0 否';
COMMENT ON COLUMN dh_modeling_logic_model_column.indicator_id IS '汇总自动字段来源派生指标编号';
COMMENT ON COLUMN dh_modeling_logic_model_column.atomic_indicator_name IS '原子指标名称快照';
COMMENT ON COLUMN dh_modeling_logic_model_column.business_condition IS '指标业务限定口径快照';
COMMENT ON COLUMN dh_modeling_logic_model_column.statistics_period IS '指标统计周期快照';
COMMENT ON COLUMN dh_modeling_logic_model_column.source_model_name IS '指标绑定的发布版本来源模型名称快照';

-- 旧页面只保存粒度名称。仅同板块/主题域且唯一匹配已启用普通对象时回填，歧义或停用对象留空供人工重新选择。
WITH requested AS (
    SELECT id, data_domain_id, subject_domain_id, string_to_array(statistics_str, ',') AS names
    FROM dh_modeling_logic_model
    WHERE table_type = 'SUMMARY' AND deleted = 0 AND grain_entity_ids IS NULL
        AND statistics_str IS NOT NULL AND btrim(statistics_str) <> ''
), resolved AS (
    SELECT r.id, wanted.position, min(e.id) AS entity_id
    FROM requested r CROSS JOIN LATERAL unnest(r.names) WITH ORDINALITY wanted(name, position)
    JOIN dh_modeling_business_entity e ON e.data_domain_id = r.data_domain_id AND e.subject_domain_id = r.subject_domain_id
        AND e.name = btrim(wanted.name) AND e.entity_type = 'OBJECT' AND e.entity_subtype = 'NORMAL'
        AND e.status = 0 AND e.deleted = 0
    GROUP BY r.id, wanted.position HAVING count(*) = 1
), complete AS (
    SELECT resolved.id, string_agg(entity_id::text, ',' ORDER BY position) AS ids
    FROM resolved JOIN requested ON requested.id = resolved.id
    GROUP BY resolved.id, requested.names HAVING count(*) = cardinality(requested.names)
)
UPDATE dh_modeling_logic_model m SET grain_entity_ids = complete.ids FROM complete WHERE m.id = complete.id;

UPDATE dh_modeling_logic_model_column c SET field_category = 'TIME'
FROM dh_modeling_logic_model m
WHERE c.model_id = m.id AND m.table_type = 'SUMMARY' AND c.field_name = 'ds'
    AND c.deleted = 0 AND m.deleted = 0 AND c.field_category IS NULL;
UPDATE dh_modeling_logic_model_column c SET field_category = 'GRAIN', dimension_id = e.id, dimension_name = e.name
FROM dh_modeling_logic_model m JOIN dh_modeling_business_entity e
    ON e.id::text = ANY(string_to_array(m.grain_entity_ids, ','))
WHERE c.model_id = m.id AND m.table_type = 'SUMMARY' AND lower(c.field_name) = lower(e.code) || '_id'
    AND c.deleted = 0 AND m.deleted = 0 AND c.field_category IS NULL;

-- 仅当前修订版本补充同一字段的元数据；不重写字段 UUID、不触碰发布版本。
UPDATE dh_modeling_logic_model_version v SET fields = (
    SELECT jsonb_agg(item.value || coalesce(jsonb_strip_nulls(jsonb_build_object(
        'fieldCategory', c.field_category, 'dimensionId', c.dimension_id, 'dimensionName', c.dimension_name)), '{}'::jsonb)
        ORDER BY item.position)
    FROM jsonb_array_elements(v.fields) WITH ORDINALITY item(value, position)
    LEFT JOIN dh_modeling_logic_model_column c ON c.model_id = v.model_id AND c.deleted = 0
        AND c.field_uid = item.value->>'fieldUid'
)
FROM dh_modeling_logic_model m
WHERE v.model_id = m.id AND m.table_type = 'SUMMARY' AND v.status = 'REVISING'
    AND v.version = m.latest_version AND v.deleted = 0 AND m.deleted = 0 AND jsonb_array_length(v.fields) > 0;

CREATE UNIQUE INDEX IF NOT EXISTS uq_modeling_logic_entity_time_live
    ON dh_modeling_logic_model (table_type, entity_id, data_timeliness)
    WHERE deleted = 0 AND status <> 'DISCARDED' AND table_type IN ('DIMENSION', 'DETAIL')
        AND entity_id IS NOT NULL AND data_timeliness IS NOT NULL;
COMMENT ON INDEX uq_modeling_logic_entity_time_live IS '同类逻辑表的业务实体与统计时效单平台唯一';
COMMIT;
