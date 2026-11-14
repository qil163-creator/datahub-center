-- Migration: 0291 建模逻辑模型与业务实体版本快照。
-- Purpose: 新建逻辑模型版本表（每次发布生成）与业务实体版本表（每次上线生成），快照写入后不可修改，
--          只用于查看与比对；为当前已发布的逻辑模型、已上线的业务实体回填 V1 初始版本。
-- Depends on: 0290_dataforge_fill-multi-level-rollup.sql；0236 已移除旧版本表；0169 单租户迁移已完成；
--             dh_modeling_logic_model、dh_modeling_logic_model_column、dh_modeling_business_entity、
--             dh_modeling_business_entity_relation 已存在。
-- Target: 已有 PostgreSQL 平台库的 datahub-center schema；新库基线需同步纳入本迁移。
-- Repeatable: 是；表、序列、索引与守卫按存在性创建，回填只为尚无任何版本的已发布模型、已上线实体生成 V1。
-- Rollback: 单事务失败整体回滚；提交后可删除两张版本表、序列和守卫函数恢复到 0290 状态，版本数据随之丢失，
--           不影响当前模型、字段、实体与关系。
-- 风险：回填期间对模型、字段、实体和关系表加 SHARE 锁，阻塞并发写入；十秒内取不到锁即失败。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '180s';

DO $preflight$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['dh_modeling_logic_model','dh_modeling_logic_model_column',
        'dh_modeling_business_entity','dh_modeling_business_entity_relation'] LOOP
        IF to_regclass(table_name) IS NULL THEN
            RAISE EXCEPTION '0291 缺少前置业务表：%', table_name;
        END IF;
    END LOOP;
END $preflight$;

-- ---------- 逻辑模型版本 ----------
CREATE SEQUENCE IF NOT EXISTS dh_modeling_logic_model_version_seq;
CREATE TABLE IF NOT EXISTS dh_modeling_logic_model_version (
    id bigint PRIMARY KEY DEFAULT nextval('dh_modeling_logic_model_version_seq'),
    model_id bigint NOT NULL REFERENCES dh_modeling_logic_model(id) ON DELETE CASCADE,
    version_no integer NOT NULL CHECK (version_no > 0),
    model_revision bigint NOT NULL DEFAULT 0,
    model_snapshot jsonb NOT NULL,
    fields_snapshot jsonb NOT NULL CHECK (jsonb_typeof(fields_snapshot) = 'array'),
    type_config jsonb,
    field_count integer NOT NULL DEFAULT 0 CHECK (field_count >= 0),
    change_summary jsonb,
    published_by bigint,
    published_time timestamp NOT NULL,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0
);
ALTER SEQUENCE dh_modeling_logic_model_version_seq OWNED BY dh_modeling_logic_model_version.id;
CREATE UNIQUE INDEX IF NOT EXISTS uk_modeling_logic_model_version_no
    ON dh_modeling_logic_model_version(model_id, version_no) WHERE deleted = 0;
COMMENT ON TABLE dh_modeling_logic_model_version IS '数据建模-逻辑模型版本快照，每次发布生成，只读';
COMMENT ON COLUMN dh_modeling_logic_model_version.id IS '版本编号';
COMMENT ON COLUMN dh_modeling_logic_model_version.model_id IS '逻辑模型编号';
COMMENT ON COLUMN dh_modeling_logic_model_version.version_no IS '模型内递增的版本号，从 1 开始';
COMMENT ON COLUMN dh_modeling_logic_model_version.model_revision IS '发布完成时模型的修订号';
COMMENT ON COLUMN dh_modeling_logic_model_version.model_snapshot IS '发布时的模型表头快照';
COMMENT ON COLUMN dh_modeling_logic_model_version.fields_snapshot IS '发布时的字段快照，按字段顺序排列';
COMMENT ON COLUMN dh_modeling_logic_model_version.type_config IS '发布时的表类型专属口径配置';
COMMENT ON COLUMN dh_modeling_logic_model_version.field_count IS '快照字段数';
COMMENT ON COLUMN dh_modeling_logic_model_version.change_summary IS '相对上一版本的字段新增、删除、修改数量，初始版本标记 initial';
COMMENT ON COLUMN dh_modeling_logic_model_version.published_by IS '发布人用户编号；历史回填时沿用模型记录，未知为空';
COMMENT ON COLUMN dh_modeling_logic_model_version.published_time IS '发布时间';
COMMENT ON COLUMN dh_modeling_logic_model_version.creator IS '创建者';
COMMENT ON COLUMN dh_modeling_logic_model_version.create_time IS '创建时间';
COMMENT ON COLUMN dh_modeling_logic_model_version.updater IS '更新者';
COMMENT ON COLUMN dh_modeling_logic_model_version.update_time IS '更新时间';
COMMENT ON COLUMN dh_modeling_logic_model_version.deleted IS '是否删除';

-- ---------- 业务实体版本 ----------
CREATE SEQUENCE IF NOT EXISTS dh_modeling_business_entity_version_seq;
CREATE TABLE IF NOT EXISTS dh_modeling_business_entity_version (
    id bigint PRIMARY KEY DEFAULT nextval('dh_modeling_business_entity_version_seq'),
    entity_id bigint NOT NULL REFERENCES dh_modeling_business_entity(id) ON DELETE CASCADE,
    version_no integer NOT NULL CHECK (version_no > 0),
    entity_revision bigint NOT NULL DEFAULT 0,
    snapshot jsonb NOT NULL,
    change_summary jsonb,
    published_by bigint,
    published_time timestamp NOT NULL,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0
);
ALTER SEQUENCE dh_modeling_business_entity_version_seq OWNED BY dh_modeling_business_entity_version.id;
CREATE UNIQUE INDEX IF NOT EXISTS uk_modeling_business_entity_version_no
    ON dh_modeling_business_entity_version(entity_id, version_no) WHERE deleted = 0;
COMMENT ON TABLE dh_modeling_business_entity_version IS '数据建模-业务实体版本快照，每次上线生成，只读';
COMMENT ON COLUMN dh_modeling_business_entity_version.id IS '版本编号';
COMMENT ON COLUMN dh_modeling_business_entity_version.entity_id IS '业务实体编号';
COMMENT ON COLUMN dh_modeling_business_entity_version.version_no IS '实体内递增的版本号，从 1 开始';
COMMENT ON COLUMN dh_modeling_business_entity_version.entity_revision IS '上线完成时实体的修订号';
COMMENT ON COLUMN dh_modeling_business_entity_version.snapshot IS '上线时的实体信息与继承、关联、前置、流程事件关系快照';
COMMENT ON COLUMN dh_modeling_business_entity_version.change_summary IS '相对上一版本变化的属性与关系数量，初始版本标记 initial';
COMMENT ON COLUMN dh_modeling_business_entity_version.published_by IS '上线操作人用户编号；历史回填未知为空';
COMMENT ON COLUMN dh_modeling_business_entity_version.published_time IS '上线时间';
COMMENT ON COLUMN dh_modeling_business_entity_version.creator IS '创建者';
COMMENT ON COLUMN dh_modeling_business_entity_version.create_time IS '创建时间';
COMMENT ON COLUMN dh_modeling_business_entity_version.updater IS '更新者';
COMMENT ON COLUMN dh_modeling_business_entity_version.update_time IS '更新时间';
COMMENT ON COLUMN dh_modeling_business_entity_version.deleted IS '是否删除';

-- ---------- 快照不可修改：只允许逻辑删除及其审计字段变化 ----------
CREATE OR REPLACE FUNCTION dh_modeling_version_snapshot_guard() RETURNS trigger
    LANGUAGE plpgsql AS $guard$
BEGIN
    IF (to_jsonb(NEW) - ARRAY['deleted','updater','update_time'])
        IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['deleted','updater','update_time']) THEN
        RAISE EXCEPTION '版本快照写入后不可修改：%', TG_TABLE_NAME USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$guard$;
COMMENT ON FUNCTION dh_modeling_version_snapshot_guard() IS '建模版本快照只读守卫，仅允许逻辑删除';
DROP TRIGGER IF EXISTS trg_modeling_logic_model_version_guard ON dh_modeling_logic_model_version;
CREATE TRIGGER trg_modeling_logic_model_version_guard BEFORE UPDATE ON dh_modeling_logic_model_version
    FOR EACH ROW EXECUTE FUNCTION dh_modeling_version_snapshot_guard();
DROP TRIGGER IF EXISTS trg_modeling_business_entity_version_guard ON dh_modeling_business_entity_version;
CREATE TRIGGER trg_modeling_business_entity_version_guard BEFORE UPDATE ON dh_modeling_business_entity_version
    FOR EACH ROW EXECUTE FUNCTION dh_modeling_version_snapshot_guard();

-- ---------- 回填 V1：键名与应用写入的快照结构一致 ----------
LOCK TABLE dh_modeling_logic_model, dh_modeling_logic_model_column,
    dh_modeling_business_entity, dh_modeling_business_entity_relation IN SHARE MODE;

INSERT INTO dh_modeling_logic_model_version(model_id, version_no, model_revision, model_snapshot, fields_snapshot,
        type_config, field_count, change_summary, published_by, published_time, creator, updater)
SELECT m.id, 1, coalesce(m.revision, 0),
       jsonb_strip_nulls(jsonb_build_object(
           'name', m.name, 'code', m.code, 'tableNameNotation', m.table_name_notation,
           'tableType', m.table_type, 'createMode', m.create_mode, 'modelStyle', m.model_style,
           'layerId', m.layer_id, 'warehouseLayer', m.warehouse_layer, 'dataDomainId', m.data_domain_id,
           'subjectDomainId', m.subject_domain_id, 'bizCategoryId', m.biz_category_id, 'entityId', m.entity_id,
           'objType', m.obj_type, 'businessType', m.business_type, 'dataTimeliness', m.data_timeliness,
           'statisticsStr', m.statistics_str, 'grain', m.grain, 'description', m.description,
           'tableCase', m.table_case, 'documentType', m.document_type, 'documentId', m.document_id,
           'contactUserId', m.contact_user_id, 'contactNumber', m.contact_number, 'enabled', m.enabled,
           'datasourceId', m.datasource_id, 'dbType', m.db_type, 'dbName', m.db_name,
           'schemaName', m.schema_name, 'sourceSystemId', m.source_system_id, 'remark', m.remark)),
       coalesce(f.fields, '[]'::jsonb), m.type_config, coalesce(f.field_count, 0),
       jsonb_build_object('initial', true), m.published_by,
       coalesce(m.published_time, m.update_time, CURRENT_TIMESTAMP), 'migration:0291', 'migration:0291'
FROM dh_modeling_logic_model m
LEFT JOIN LATERAL (
    SELECT count(*)::integer AS field_count,
           jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
               'fieldUid', c.field_uid, 'sourceAttrUid', c.source_attr_uid, 'cnName', c.cn_name,
               'fieldName', c.field_name, 'dataType', c.data_type, 'dataLength', c.data_length,
               'dataPrecision', c.data_precision, 'dataScale', c.data_scale, 'dataFormat', c.data_format,
               'measureUnit', c.measure_unit, 'valueRange', c.value_range, 'defaultValue', c.default_value,
               'sort', c.sort, 'comment', c.comment, 'remark', c.remark,
               'pkFlag', c.pk_flag = 1, 'nullableFlag', c.nullable_flag = 1, 'aggType', c.agg_type,
               'standardId', c.standard_id, 'standardCode', c.standard_code, 'standardName', c.standard_name,
               'standardColumnType', c.standard_column_type, 'fieldCategory', c.field_category,
               'dimensionId', c.dimension_id, 'dimensionName', c.dimension_name,
               'dataClassificationId', c.data_classification_id,
               'dataClassificationName', c.data_classification_name, 'dataGrade', c.data_grade,
               'codeTableId', c.code_table_id, 'codeTableName', c.code_table_name,
               'uniqueFlag', CASE WHEN c.unique_flag IS NULL THEN NULL ELSE c.unique_flag = 1 END,
               'indicatorId', c.indicator_id, 'atomicIndicatorName', c.atomic_indicator_name,
               'businessCondition', c.business_condition, 'statisticsPeriod', c.statistics_period,
               'sourceModelName', c.source_model_name))
               ORDER BY c.sort NULLS LAST, c.id) AS fields
    FROM dh_modeling_logic_model_column c
    WHERE c.model_id = m.id AND c.deleted = 0
) f ON true
WHERE m.deleted = 0 AND m.status = 'PUBLISHED'
  AND NOT EXISTS (SELECT 1 FROM dh_modeling_logic_model_version v WHERE v.model_id = m.id);

INSERT INTO dh_modeling_business_entity_version(entity_id, version_no, entity_revision, snapshot, change_summary,
        published_by, published_time, creator, updater)
SELECT e.id, 1, coalesce(e.revision, 0),
       jsonb_strip_nulls(jsonb_build_object(
           'name', e.name, 'code', e.code, 'entityType', e.entity_type, 'entitySubtype', e.entity_subtype,
           'dataDomainId', e.data_domain_id, 'subjectDomainId', e.subject_domain_id,
           'leaderUserId', e.leader_user_id, 'leaderPhone', e.leader_phone, 'description', e.description,
           'relations', coalesce(r.relations, '[]'::jsonb))),
       jsonb_build_object('initial', true), NULL,
       coalesce(e.update_time, CURRENT_TIMESTAMP), 'migration:0291', 'migration:0291'
FROM dh_modeling_business_entity e
LEFT JOIN LATERAL (
    SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
               'relationType', rel.relation_type, 'entityId', t.id, 'name', t.name, 'code', t.code))
               ORDER BY rel.relation_type, rel.sort_order NULLS LAST, t.id) AS relations
    FROM dh_modeling_business_entity_relation rel
    JOIN dh_modeling_business_entity t ON t.id = rel.target_entity_id AND t.deleted = 0
    WHERE rel.source_entity_id = e.id AND rel.deleted = 0
) r ON true
WHERE e.deleted = 0 AND e.status = 0
  AND NOT EXISTS (SELECT 1 FROM dh_modeling_business_entity_version v WHERE v.entity_id = e.id);

COMMIT;
