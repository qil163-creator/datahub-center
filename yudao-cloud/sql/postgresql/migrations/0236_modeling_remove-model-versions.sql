-- Migration: 0236 移除模型版本机制，改为当前模型直接保存与发布。
-- Purpose: 将当前口径和可证明的发布事实迁入模型头，保留字段真相源及已有部署任务输入，删除版本表及来源版本引用，建立当前模型结构引用守卫。
-- Depends on: 0235_governance_qdata-whitelist-policies.sql；已完成 0213 的模型、指标、限定、实体及 0165 的物化部署结构。
-- Target: 已有 PostgreSQL 平台库的 datahub-center schema；新库使用包含本增量的全量基线。
-- Repeatable: 是；重复执行不覆盖当前口径、字段、发布事实、部署输入或历史未知修订号。
-- Rollback: 单事务失败整体回滚；提交后版本历史和来源版本编号不可恢复，须停用旧应用并从执行前完整备份恢复，禁止回退旧应用直接读写新结构。
-- 风险：删除历史不可逆，结构变更获取排他锁；迁移前停止建模及物化写入，孤立部署尝试、缺失当前版本或不完整来源引用均阻断提交，不猜测归属、操作者或执行时间。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '180s';
SELECT pg_advisory_xact_lock(hashtextextended('modeling-ownership-global', 0));

DO $preflight$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['dh_modeling_logic_model','dh_modeling_logic_model_column',
        'dh_modeling_indicator_model','dh_modeling_business_condition','dh_modeling_business_entity',
        'dh_modeling_model_materialized','dh_modeling_deployment_attempt'] LOOP
        IF to_regclass(table_name) IS NULL THEN
            RAISE EXCEPTION '0236 缺少前置业务表：%', table_name;
        END IF;
    END LOOP;
END $preflight$;
LOCK TABLE dh_modeling_logic_model, dh_modeling_logic_model_column, dh_modeling_indicator_model,
    dh_modeling_business_condition, dh_modeling_business_entity, dh_modeling_model_materialized,
    dh_modeling_deployment_attempt IN SHARE ROW EXCLUSIVE MODE;

-- 先移除本模块的旧版本守卫；保留规划归属、实体及其他业务守卫，不用级联删除绕过未知依赖。
DO $remove_guards$
DECLARE item record;
BEGIN
    FOR item IN SELECT n.nspname, c.relname, t.tgname
        FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
        JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_proc p ON p.oid=t.tgfoid
        WHERE NOT t.tgisinternal AND n.nspname='datahub-center'
          AND p.proname IN ('modeling_indicator_reference_guard','modeling_business_condition_guard',
              'modeling_metric_entity_guard','dh_modeling_published_design_guard',
              'modeling_current_source_guard','modeling_current_reference_guard','modeling_current_reference_lock') LOOP
        EXECUTE format('DROP TRIGGER %I ON %I.%I', item.tgname, item.nspname, item.relname);
    END LOOP;
END $remove_guards$;
DROP FUNCTION IF EXISTS modeling_indicator_reference_guard();
DROP FUNCTION IF EXISTS modeling_business_condition_guard();
DROP FUNCTION IF EXISTS modeling_metric_entity_guard();
DROP FUNCTION IF EXISTS dh_modeling_published_design_guard();

ALTER TABLE dh_modeling_logic_model
    ADD COLUMN IF NOT EXISTS type_config jsonb,
    ADD COLUMN IF NOT EXISTS published_by bigint,
    ADD COLUMN IF NOT EXISTS published_time timestamp;
COMMENT ON COLUMN dh_modeling_logic_model.type_config IS '当前逻辑模型的表类型专属口径配置；直接保存时与模型头和字段整单更新';
COMMENT ON COLUMN dh_modeling_logic_model.published_by IS '当前模型实际完成发布的用户编号；草稿及历史未知为空';
COMMENT ON COLUMN dh_modeling_logic_model.published_time IS '当前模型实际完成发布的时间；草稿及历史未知为空';

-- 只取模型明确指向的最新版本，不按最大编号或发布时间猜测；重放时旧列已删除，不重写新模型。
DO $current_definition$
BEGIN
    IF to_regclass('dh_modeling_logic_model_version') IS NOT NULL
       AND EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid='dh_modeling_logic_model'::regclass
           AND attname='latest_version' AND NOT attisdropped) THEN
        IF EXISTS(SELECT 1 FROM dh_modeling_logic_model m WHERE
            (SELECT count(*) FROM dh_modeling_logic_model_version v
             WHERE v.model_id=m.id AND v.version=m.latest_version AND (m.deleted<>0 OR v.deleted=0))<>1) THEN
            RAISE EXCEPTION '0236 模型当前版本缺失或不唯一，禁止猜测口径';
        END IF;
        UPDATE dh_modeling_logic_model m SET type_config=v.type_config,
            published_by=CASE WHEN m.status='PUBLISHED' AND v.status='PUBLISHED' THEN v.published_by END,
            published_time=CASE WHEN m.status='PUBLISHED' AND v.status='PUBLISHED' THEN v.published_time END
        FROM dh_modeling_logic_model_version v
        WHERE v.model_id=m.id AND v.version=m.latest_version AND (m.deleted<>0 OR v.deleted=0);

        -- 当前字段表完整保留；旧快照中的额外稳定标识不代表尚未迁入，不能复活已移除字段。
    END IF;
END $current_definition$;

ALTER TABLE dh_modeling_model_materialized
    ADD COLUMN IF NOT EXISTS design_revision bigint,
    ADD COLUMN IF NOT EXISTS fields_json jsonb;
ALTER TABLE dh_modeling_deployment_attempt
    ADD COLUMN IF NOT EXISTS model_id bigint,
    ADD COLUMN IF NOT EXISTS design_revision bigint;
COMMENT ON COLUMN dh_modeling_model_materialized.design_revision IS '该次物化提交的当前模型并发修订号；历史未知为空，不代表模型版本';
COMMENT ON COLUMN dh_modeling_model_materialized.fields_json IS '该次物化的字段输入证据；真实部署尝试优先，旧关联可证明时迁入，未知为空';
COMMENT ON COLUMN dh_modeling_deployment_attempt.model_id IS '该次部署所属逻辑模型编号；历史按物化记录精确回填';
COMMENT ON COLUMN dh_modeling_deployment_attempt.design_revision IS '该次部署准备时的模型并发修订号；历史未知为空，不按当前修订号回填';
COMMENT ON COLUMN dh_modeling_deployment_attempt.fields_json IS '该次部署冻结的字段输入；仅为执行及结果核验依据';
COMMENT ON COLUMN dh_modeling_deployment_attempt.model_snapshot_json IS '该次部署冻结的模型输入；仅为执行及结果核验依据，不是模型历史版本';

DO $deployment_inputs$
BEGIN
    IF EXISTS(SELECT 1 FROM dh_modeling_deployment_attempt a LEFT JOIN dh_modeling_model_materialized m
        ON m.id=a.materialized_id WHERE m.id IS NULL OR (a.model_id IS NOT NULL AND a.model_id<>m.model_id)) THEN
        RAISE EXCEPTION '0236 部署尝试没有可精确关联的物化模型，禁止猜测归属';
    END IF;
    UPDATE dh_modeling_deployment_attempt a SET model_id=m.model_id
    FROM dh_modeling_model_materialized m WHERE m.id=a.materialized_id AND a.model_id IS NULL;
    IF EXISTS(SELECT 1 FROM dh_modeling_model_materialized m JOIN dh_modeling_deployment_attempt a
        ON a.id=m.attempt_id WHERE a.materialized_id<>m.id) THEN
        RAISE EXCEPTION '0236 物化记录的部署尝试归属不一致';
    END IF;
    UPDATE dh_modeling_model_materialized m SET fields_json=a.fields_json
    FROM dh_modeling_deployment_attempt a
    WHERE a.id=m.attempt_id AND a.materialized_id=m.id AND m.fields_json IS NULL;
    IF to_regclass('dh_modeling_logic_model_version') IS NOT NULL
       AND EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid='dh_modeling_model_materialized'::regclass
           AND attname='version_id' AND NOT attisdropped) THEN
        UPDATE dh_modeling_model_materialized m SET fields_json=v.fields
        FROM dh_modeling_logic_model_version v
        WHERE v.id=m.version_id AND v.model_id=m.model_id AND m.fields_json IS NULL;
        IF EXISTS(SELECT 1 FROM dh_modeling_model_materialized WHERE version_id IS NOT NULL AND fields_json IS NULL) THEN
            RAISE EXCEPTION '0236 物化来源版本缺失或字段输入未知，禁止删除唯一输入证据';
        END IF;
    END IF;
    IF EXISTS(SELECT 1 FROM dh_modeling_deployment_attempt a
        WHERE jsonb_typeof(a.model_snapshot_json) IS DISTINCT FROM 'object'
            OR NOT (a.model_snapshot_json ? 'modelId' OR a.model_snapshot_json ? 'id')
            OR (a.model_snapshot_json ? 'modelId' AND a.model_snapshot_json ? 'id'
                AND a.model_snapshot_json->'modelId' IS DISTINCT FROM a.model_snapshot_json->'id')
            OR coalesce(a.model_snapshot_json->>'id',a.model_snapshot_json->>'modelId') IS DISTINCT FROM a.model_id::text) THEN
        RAISE EXCEPTION '0236 部署模型输入缺少身份或身份冲突，禁止用当前模型覆盖历史任务';
    END IF;
    UPDATE dh_modeling_deployment_attempt
    SET model_snapshot_json=(model_snapshot_json-'modelId') || jsonb_build_object('id',model_snapshot_json->'modelId')
    WHERE model_snapshot_json ? 'modelId';
END $deployment_inputs$;
ALTER TABLE dh_modeling_deployment_attempt ALTER COLUMN model_id SET NOT NULL;

-- 这些依赖属于旧表结构；当前来源索引及原子/派生互斥约束按新协议重建。
ALTER TABLE dh_modeling_indicator_model DROP CONSTRAINT IF EXISTS ck_modeling_indicator_definition;
DROP INDEX IF EXISTS idx_modeling_indicator_source;
DROP INDEX IF EXISTS idx_modeling_condition_source;
ALTER TABLE dh_modeling_indicator_model DROP COLUMN IF EXISTS source_version_id;
ALTER TABLE dh_modeling_business_condition DROP COLUMN IF EXISTS source_version_id;
ALTER TABLE dh_modeling_model_materialized DROP COLUMN IF EXISTS version_id;
ALTER TABLE dh_modeling_deployment_attempt DROP COLUMN IF EXISTS version_id;
ALTER TABLE dh_modeling_logic_model DROP COLUMN IF EXISTS latest_version;
UPDATE dh_modeling_logic_model SET status='DRAFT' WHERE status='REVISING';
ALTER TABLE dh_modeling_logic_model ALTER COLUMN status SET DEFAULT 'DRAFT';
COMMENT ON COLUMN dh_modeling_logic_model.status IS '当前设计状态：DRAFT草稿、PUBLISHED已发布、DISCARDED已废弃';
COMMENT ON COLUMN dh_modeling_indicator_model.source_model_id IS '原子指标绑定的当前逻辑模型编号；使用时重新校验发布、启用和归属';
COMMENT ON COLUMN dh_modeling_indicator_model.value_field_uid IS '当前来源模型中参与计算的稳定字段标识';
COMMENT ON COLUMN dh_modeling_indicator_model.time_field_uid IS '当前来源模型中统计时间字段稳定标识';
COMMENT ON COLUMN dh_modeling_business_condition.source_model_id IS '业务限定绑定的当前逻辑模型编号；使用时重新校验发布、启用和归属';
COMMENT ON COLUMN dh_modeling_logic_model_column.source_model_name IS '指标当前来源模型名称';

ALTER TABLE dh_modeling_indicator_model ADD CONSTRAINT ck_modeling_indicator_definition CHECK (
    (indicator_type='ATOMIC' AND source_model_id IS NOT NULL
        AND nullif(btrim(value_field_uid),'') IS NOT NULL AND aggregation IS NOT NULL
        AND base_indicator_id IS NULL AND time_period IS NULL AND business_condition IS NULL)
    OR (indicator_type='DERIVED' AND base_indicator_id IS NOT NULL AND base_indicator_id<>id
        AND time_period IS NOT NULL AND nullif(btrim(business_condition),'') IS NOT NULL
        AND source_model_id IS NULL AND value_field_uid IS NULL AND time_field_uid IS NULL AND aggregation IS NULL));
CREATE INDEX idx_modeling_indicator_source ON dh_modeling_indicator_model(source_model_id) WHERE deleted=0;
CREATE INDEX idx_modeling_condition_source ON dh_modeling_business_condition(source_model_id) WHERE deleted=0;
COMMENT ON INDEX idx_modeling_indicator_source IS '有效原子指标的当前模型来源';
COMMENT ON INDEX idx_modeling_condition_source IS '有效业务限定的当前模型来源';
CREATE INDEX IF NOT EXISTS idx_modeling_deployment_model ON dh_modeling_deployment_attempt(model_id);
COMMENT ON INDEX idx_modeling_deployment_model IS '按模型定位真实部署任务';

-- 正向创建和重新保存引用要求来源当前已发布且启用；反向结构校验允许正常转草稿和停用，使用方须实时封闭拒绝未发布来源。
CREATE OR REPLACE FUNCTION modeling_current_source_matches(source_id bigint, entity_id bigint,
    domain_id bigint, subject_id bigint, freshness text, require_published boolean) RETURNS boolean
LANGUAGE sql STABLE SET search_path="datahub-center",pg_catalog AS $matches$
    SELECT EXISTS(SELECT 1 FROM dh_modeling_logic_model m
        JOIN dh_modeling_business_entity e ON e.id=m.entity_id
        WHERE m.id=$1 AND m.deleted=0 AND m.status<>'DISCARDED' AND m.table_type IN ('DIMENSION','DETAIL')
            AND e.id=$2 AND e.deleted=0 AND e.status=0
            AND ((m.table_type='DIMENSION' AND e.entity_type='OBJECT')
                OR (m.table_type='DETAIL' AND e.entity_type='ACTIVITY'))
            AND e.data_domain_id=$3 AND e.subject_domain_id IS NOT DISTINCT FROM $4
            AND m.data_domain_id=e.data_domain_id AND m.subject_domain_id=e.subject_domain_id
            AND m.data_timeliness=$5 AND m.data_timeliness IN ('1','2','3')
            AND (NOT $6 OR (m.enabled=1 AND m.status='PUBLISHED')));
$matches$;
COMMENT ON FUNCTION modeling_current_source_matches(bigint,bigint,bigint,bigint,text,boolean) IS '校验当前逻辑模型与实体、板块、主题和时效；仅正向保存要求当前发布启用';

CREATE OR REPLACE FUNCTION modeling_current_source_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path="datahub-center",pg_catalog AS $guard$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0));
    IF TG_TABLE_NAME='dh_modeling_indicator_model' AND TG_OP<>'INSERT'
       AND EXISTS(SELECT 1 FROM dh_modeling_indicator_model WHERE base_indicator_id=OLD.id AND deleted=0) THEN
        IF TG_OP='DELETE' THEN RAISE EXCEPTION '原子指标仍被派生指标引用' USING ERRCODE='23514'; END IF;
        IF (to_jsonb(NEW)-ARRAY['updater','update_time','revision']) IS DISTINCT FROM
            (to_jsonb(OLD)-ARRAY['updater','update_time','revision']) THEN
            RAISE EXCEPTION '原子指标仍被派生指标引用，不能修改或停用' USING ERRCODE='23514';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    IF NEW.deleted<>0 THEN RETURN NEW; END IF;
    IF TG_TABLE_NAME='dh_modeling_indicator_model' THEN
      IF NEW.indicator_type='DERIVED' THEN
        IF NOT EXISTS(SELECT 1 FROM dh_modeling_indicator_model b WHERE b.id=NEW.base_indicator_id
            AND b.deleted=0 AND b.indicator_type='ATOMIC' AND b.status='ACTIVE'
            AND b.data_domain_id=NEW.data_domain_id AND b.subject_domain_id IS NOT DISTINCT FROM NEW.subject_domain_id
            AND b.entity_id IS NOT DISTINCT FROM NEW.entity_id) THEN
            RAISE EXCEPTION '派生指标必须继承同归属的已启用原子指标及实体' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
      END IF;
    END IF;
    IF NOT modeling_current_source_matches(NEW.source_model_id,NEW.entity_id,NEW.data_domain_id,
        NEW.subject_domain_id,NEW.data_freshness,true) THEN
        RAISE EXCEPTION '指标或限定必须绑定同实体、归属及时效的当前已发布启用模型' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='dh_modeling_indicator_model' THEN
      IF (
        NOT EXISTS(SELECT 1 FROM dh_modeling_logic_model_column c WHERE c.model_id=NEW.source_model_id
            AND c.field_uid=NEW.value_field_uid AND c.deleted=0)
        OR (NEW.time_field_uid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM dh_modeling_logic_model_column c
            WHERE c.model_id=NEW.source_model_id AND c.field_uid=NEW.time_field_uid AND c.deleted=0))) THEN
        RAISE EXCEPTION '指标字段不在当前来源模型的稳定字段集合中' USING ERRCODE='23514';
      END IF;
    END IF;
    RETURN NEW;
END $guard$;
COMMENT ON FUNCTION modeling_current_source_guard() IS '在共享事务锁内保护原子、派生指标及业务限定的当前来源绑定';
CREATE TRIGGER modeling_current_source_guard BEFORE INSERT OR UPDATE OR DELETE ON dh_modeling_indicator_model
    FOR EACH ROW EXECUTE FUNCTION modeling_current_source_guard();
CREATE TRIGGER modeling_current_source_guard BEFORE INSERT OR UPDATE OR DELETE ON dh_modeling_business_condition
    FOR EACH ROW EXECUTE FUNCTION modeling_current_source_guard();

CREATE OR REPLACE FUNCTION modeling_current_reference_lock() RETURNS trigger
LANGUAGE plpgsql SET search_path="datahub-center",pg_catalog AS $lock$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0));
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $lock$;
COMMENT ON FUNCTION modeling_current_reference_lock() IS '在模型、实体及字段变动前取得同一事务锁，防止新增引用并发穿透';

CREATE OR REPLACE FUNCTION modeling_current_reference_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path="datahub-center",pg_catalog AS $guard$
DECLARE affected_id bigint; previous_id bigint; ref record;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0));
    affected_id:=(CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END
        ->> CASE WHEN TG_TABLE_NAME='dh_modeling_logic_model_column' THEN 'model_id' ELSE 'id' END)::bigint;
    previous_id:=CASE WHEN TG_OP<>'INSERT' THEN (to_jsonb(OLD)
        ->> CASE WHEN TG_TABLE_NAME='dh_modeling_logic_model_column' THEN 'model_id' ELSE 'id' END)::bigint END;
    FOR ref IN SELECT i.source_model_id,i.entity_id,i.data_domain_id,i.subject_domain_id,i.data_freshness,
            i.value_field_uid,i.time_field_uid,true AS has_fields FROM dh_modeling_indicator_model i
        WHERE i.deleted=0 AND i.indicator_type='ATOMIC' AND
            CASE WHEN TG_TABLE_NAME='dh_modeling_business_entity' THEN i.entity_id IN (affected_id,previous_id)
                ELSE i.source_model_id IN (affected_id,previous_id) END
        UNION ALL SELECT c.source_model_id,c.entity_id,c.data_domain_id,c.subject_domain_id,c.data_freshness,
            NULL::varchar,NULL::varchar,false FROM dh_modeling_business_condition c
        WHERE c.deleted=0 AND CASE WHEN TG_TABLE_NAME='dh_modeling_business_entity' THEN c.entity_id IN (affected_id,previous_id)
            ELSE c.source_model_id IN (affected_id,previous_id) END LOOP
        IF NOT modeling_current_source_matches(ref.source_model_id,ref.entity_id,ref.data_domain_id,
            ref.subject_domain_id,ref.data_freshness,false) THEN
            RAISE EXCEPTION '当前模型或实体仍被指标及限定引用，不能删除、废弃或变更归属时效' USING ERRCODE='23514';
        END IF;
        IF ref.has_fields AND (NOT EXISTS(SELECT 1 FROM dh_modeling_logic_model_column f
            WHERE f.model_id=ref.source_model_id AND f.field_uid=ref.value_field_uid AND f.deleted=0)
            OR (ref.time_field_uid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM dh_modeling_logic_model_column f
                WHERE f.model_id=ref.source_model_id AND f.field_uid=ref.time_field_uid AND f.deleted=0))) THEN
            RAISE EXCEPTION '当前字段仍被原子指标引用，不能移除稳定字段标识' USING ERRCODE='23514';
        END IF;
    END LOOP;
    RETURN NULL;
END $guard$;
COMMENT ON FUNCTION modeling_current_reference_guard() IS '延迟到事务末保护当前模型结构引用，允许字段整单替换及模型正常转草稿和停用';
DO $install$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['dh_modeling_logic_model','dh_modeling_logic_model_column','dh_modeling_business_entity'] LOOP
        EXECUTE format('CREATE TRIGGER modeling_current_reference_lock BEFORE INSERT OR UPDATE OR DELETE ON %I
            FOR EACH ROW EXECUTE FUNCTION modeling_current_reference_lock()',table_name);
        EXECUTE format('CREATE CONSTRAINT TRIGGER modeling_current_reference_guard AFTER INSERT OR UPDATE OR DELETE ON %I
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION modeling_current_reference_guard()',table_name);
    END LOOP;
END $install$;

-- 已退役对象不改名留存，不新建历史替代表；未知数据库依赖必须显式阻断删除。
DROP TABLE IF EXISTS dh_modeling_logic_model_version;
DROP SEQUENCE IF EXISTS dh_modeling_logic_model_version_seq;
DROP TABLE IF EXISTS dh_modeling_object_change;
DROP SEQUENCE IF EXISTS dh_modeling_object_change_seq;
DROP TABLE IF EXISTS dh_modeling_planning_change;
DROP SEQUENCE IF EXISTS dh_modeling_planning_change_seq;
DROP TABLE IF EXISTS dh_modeling_concept_model_version;
DROP SEQUENCE IF EXISTS dh_modeling_concept_model_version_seq;
DROP TABLE IF EXISTS dh_modeling_physical_model_version;
DROP SEQUENCE IF EXISTS dh_modeling_physical_model_version_seq;
DROP TABLE IF EXISTS dh_modeling_business_object_version;
DROP SEQUENCE IF EXISTS dh_modeling_business_object_version_seq;

DO $postflight$
BEGIN
    IF EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='datahub-center' AND p.prokind IN ('f','p')
            AND p.prosrc ~ '\m(dh_modeling_logic_model_version|source_version_id|latest_version)\M') THEN
        RAISE EXCEPTION '0236 仍有函数引用已删除的模型版本协议，禁止留下运行时故障';
    END IF;
    IF EXISTS(SELECT 1 FROM dh_modeling_indicator_model i WHERE i.deleted=0 AND i.indicator_type='ATOMIC'
        AND (NOT modeling_current_source_matches(i.source_model_id,i.entity_id,i.data_domain_id,
                i.subject_domain_id,i.data_freshness,false)
            OR NOT EXISTS(SELECT 1 FROM dh_modeling_logic_model_column f
                WHERE f.model_id=i.source_model_id AND f.field_uid=i.value_field_uid AND f.deleted=0)
            OR (i.time_field_uid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM dh_modeling_logic_model_column f
                WHERE f.model_id=i.source_model_id AND f.field_uid=i.time_field_uid AND f.deleted=0))))
        OR EXISTS(SELECT 1 FROM dh_modeling_business_condition c WHERE c.deleted=0
            AND NOT modeling_current_source_matches(c.source_model_id,c.entity_id,c.data_domain_id,
                c.subject_domain_id,c.data_freshness,false)) THEN
        RAISE EXCEPTION '0236 存量指标或限定与当前模型结构不一致，须先明确当前来源，不迁造历史模型';
    END IF;
END $postflight$;
COMMIT;
