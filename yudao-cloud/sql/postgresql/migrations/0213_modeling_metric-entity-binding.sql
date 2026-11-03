-- Migration: 0213 原子指标及业务限定的实体绑定和来源归属保护。
-- Purpose: 新增实体编号，保护同板块、主题、实体、时效的启用维度/事实表发布来源，保留既有版本和引用锁规则。
-- Depends on: 0212_modeling_logic-table-definition.sql；现有指标、限定、业务实体、逻辑模型及发布版本表和引用触发器已建立。
-- Target: 已有 PostgreSQL 平台库；新库使用同步后的 datahub-center.sql。
-- Repeatable: 是；加列、索引及触发器可重放；仅对来源归属可确定的旧定义补充实体编号，不重写业务口径或发布版本。
-- Rollback: 事务失败整体回滚；成功后回退应用可保留新增列。删除列及触发器前须备份新定义，避免丢失实体引用和并发保护。
-- Risk: 加法式结构变更及有限回填获取表锁；共享引用事务锁等待受 lock_timeout 约束，不清空数据、不新增角色授权。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_modeling_indicator_model ADD COLUMN IF NOT EXISTS entity_id bigint;
ALTER TABLE dh_modeling_business_condition ADD COLUMN IF NOT EXISTS entity_id bigint;
COMMENT ON COLUMN dh_modeling_indicator_model.entity_id IS '绑定业务实体编号；派生指标继承原子指标实体';
COMMENT ON COLUMN dh_modeling_business_condition.entity_id IS '绑定的业务对象或业务活动编号';
CREATE INDEX IF NOT EXISTS idx_modeling_indicator_entity ON dh_modeling_indicator_model(entity_id) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_modeling_condition_entity ON dh_modeling_business_condition(entity_id) WHERE deleted=0;
COMMENT ON INDEX idx_modeling_indicator_entity IS '有效指标的业务实体引用';
COMMENT ON INDEX idx_modeling_condition_entity IS '有效业务限定的业务实体引用';

-- 只从同归属、同时效且仍启用的已知来源补充旧实体；不能确定的旧定义保留为空，重新编辑时明确选择。
UPDATE dh_modeling_indicator_model i SET entity_id=m.entity_id
FROM dh_modeling_logic_model m JOIN dh_modeling_business_entity e ON e.id=m.entity_id AND e.deleted=0 AND e.status=0
WHERE i.entity_id IS NULL AND i.deleted=0 AND i.indicator_type='ATOMIC' AND i.source_model_id=m.id
    -- 被派生引用的原子定义已有不可变保护，不为回填绕过或停用该保护。
    AND NOT EXISTS(SELECT 1 FROM dh_modeling_indicator_model d WHERE d.base_indicator_id=i.id AND d.deleted=0)
    AND m.deleted=0 AND m.enabled=1 AND m.table_type IN ('DIMENSION','DETAIL')
    AND i.data_domain_id=m.data_domain_id AND i.subject_domain_id=m.subject_domain_id AND i.data_freshness=m.data_timeliness
    AND EXISTS(SELECT 1 FROM dh_modeling_logic_model_version v WHERE v.id=i.source_version_id AND v.model_id=m.id
        AND v.deleted=0 AND v.status='PUBLISHED' AND v.model_snapshot->>'entityId'=e.id::text
        AND v.model_snapshot->>'dataDomainId'=e.data_domain_id::text AND v.model_snapshot->>'subjectDomainId'=e.subject_domain_id::text
        AND v.model_snapshot->>'dataTimeliness'=m.data_timeliness);
UPDATE dh_modeling_indicator_model i SET entity_id=b.entity_id FROM dh_modeling_indicator_model b
WHERE i.entity_id IS NULL AND i.deleted=0 AND i.indicator_type='DERIVED' AND i.base_indicator_id=b.id
    AND b.deleted=0 AND b.indicator_type='ATOMIC' AND b.entity_id IS NOT NULL;
UPDATE dh_modeling_business_condition c SET entity_id=m.entity_id
FROM dh_modeling_logic_model m JOIN dh_modeling_business_entity e ON e.id=m.entity_id AND e.deleted=0 AND e.status=0
WHERE c.entity_id IS NULL AND c.deleted=0 AND c.source_model_id=m.id
    AND m.deleted=0 AND m.enabled=1 AND m.table_type IN ('DIMENSION','DETAIL')
    AND c.data_domain_id=m.data_domain_id AND c.subject_domain_id=m.subject_domain_id AND c.data_freshness=m.data_timeliness
    AND EXISTS(SELECT 1 FROM dh_modeling_logic_model_version v WHERE v.id=c.source_version_id AND v.model_id=m.id
        AND v.deleted=0 AND v.status='PUBLISHED' AND v.model_snapshot->>'entityId'=e.id::text
        AND v.model_snapshot->>'dataDomainId'=e.data_domain_id::text AND v.model_snapshot->>'subjectDomainId'=e.subject_domain_id::text
        AND v.model_snapshot->>'dataTimeliness'=m.data_timeliness);

CREATE OR REPLACE FUNCTION modeling_metric_entity_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path="datahub-center",pg_catalog AS $guard$
DECLARE data jsonb; referenced boolean; entity bigint;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0));
    IF TG_TABLE_NAME IN ('dh_modeling_indicator_model','dh_modeling_business_condition') THEN
        IF TG_OP='DELETE' THEN RETURN OLD; END IF;
        IF NEW.deleted<>0 THEN RETURN NEW; END IF;
        data:=to_jsonb(NEW); entity:=(data->>'entity_id')::bigint;
        IF TG_TABLE_NAME='dh_modeling_indicator_model' AND data->>'indicator_type'='DERIVED' THEN
            IF NOT EXISTS(SELECT 1 FROM dh_modeling_indicator_model b WHERE b.id=(data->>'base_indicator_id')::bigint
                AND b.deleted=0 AND b.indicator_type='ATOMIC' AND b.entity_id IS NOT DISTINCT FROM entity) THEN
                RAISE EXCEPTION '派生指标实体必须继承原子指标' USING ERRCODE='23514';
            END IF;
            RETURN NEW;
        END IF;
        -- 旧未知实体记录允许停用或删除，不得借旧形态启用或新建定义。
        IF entity IS NULL AND TG_OP='UPDATE' AND data->>'status'='DRAFT' AND to_jsonb(OLD)->>'entity_id' IS NULL THEN RETURN NEW; END IF;
        IF NOT EXISTS(SELECT 1 FROM dh_modeling_business_entity e
            JOIN dh_modeling_logic_model m ON m.id=(data->>'source_model_id')::bigint AND m.entity_id=e.id
            JOIN dh_modeling_logic_model_version v ON v.id=(data->>'source_version_id')::bigint AND v.model_id=m.id
            WHERE e.id=entity AND e.deleted=0 AND e.status=0
                AND e.data_domain_id=(data->>'data_domain_id')::bigint AND e.subject_domain_id=(data->>'subject_domain_id')::bigint
                AND m.deleted=0 AND m.enabled=1 AND m.status<>'DISCARDED' AND m.table_type IN ('DIMENSION','DETAIL')
                AND m.data_domain_id=e.data_domain_id AND m.subject_domain_id=e.subject_domain_id
                AND m.data_timeliness=data->>'data_freshness' AND m.data_timeliness IN ('1','2','3')
                AND v.deleted=0 AND v.status='PUBLISHED'
                AND v.model_snapshot->>'modelId'=m.id::text AND v.model_snapshot->>'entityId'=e.id::text
                AND v.model_snapshot->>'dataDomainId'=e.data_domain_id::text
                AND v.model_snapshot->>'subjectDomainId'=e.subject_domain_id::text
                AND v.model_snapshot->>'dataTimeliness'=m.data_timeliness
                AND v.model_snapshot->>'tableType' IN ('DIMENSION','DETAIL')) THEN
            RAISE EXCEPTION '指标或限定必须绑定同归属实体及时效的有效发布来源' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_TABLE_NAME='dh_modeling_business_entity' THEN
        SELECT EXISTS(SELECT 1 FROM dh_modeling_indicator_model WHERE entity_id=OLD.id AND deleted=0)
            OR EXISTS(SELECT 1 FROM dh_modeling_business_condition WHERE entity_id=OLD.id AND deleted=0) INTO referenced;
        IF referenced AND (TG_OP='DELETE' OR NEW.deleted<>0 OR NEW.status<>0
            OR NEW.data_domain_id IS DISTINCT FROM OLD.data_domain_id OR NEW.subject_domain_id IS DISTINCT FROM OLD.subject_domain_id
            OR NEW.entity_type IS DISTINCT FROM OLD.entity_type OR NEW.entity_subtype IS DISTINCT FROM OLD.entity_subtype) THEN
            RAISE EXCEPTION '业务实体仍被指标或限定引用，不能删除、停用或变更归属类型' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='dh_modeling_logic_model' THEN
        SELECT EXISTS(SELECT 1 FROM dh_modeling_indicator_model WHERE source_model_id=OLD.id AND deleted=0)
            OR EXISTS(SELECT 1 FROM dh_modeling_business_condition WHERE source_model_id=OLD.id AND deleted=0) INTO referenced;
        IF referenced AND (TG_OP='DELETE' OR NEW.deleted<>0 OR NEW.enabled<>1 OR NEW.status='DISCARDED'
            OR NEW.table_type IS DISTINCT FROM OLD.table_type OR NEW.entity_id IS DISTINCT FROM OLD.entity_id
            OR NEW.data_domain_id IS DISTINCT FROM OLD.data_domain_id OR NEW.subject_domain_id IS DISTINCT FROM OLD.subject_domain_id
            OR NEW.data_timeliness IS DISTINCT FROM OLD.data_timeliness) THEN
            RAISE EXCEPTION '来源仍被指标或限定引用，不能停用或变更实体和时效归属' USING ERRCODE='23514';
        END IF;
    ELSE
        SELECT EXISTS(SELECT 1 FROM dh_modeling_indicator_model WHERE source_version_id=OLD.id AND deleted=0)
            OR EXISTS(SELECT 1 FROM dh_modeling_business_condition WHERE source_version_id=OLD.id AND deleted=0) INTO referenced;
        IF referenced AND (TG_OP='DELETE' OR (to_jsonb(NEW)-ARRAY['updater','update_time']) IS DISTINCT FROM
            (to_jsonb(OLD)-ARRAY['updater','update_time'])) THEN
            RAISE EXCEPTION '指标或限定绑定的发布版本不可变更' USING ERRCODE='23514';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $guard$;
COMMENT ON FUNCTION modeling_metric_entity_guard() IS '以共享事务锁保护指标及限定的实体、时效和发布来源';
DO $install$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['dh_modeling_indicator_model','dh_modeling_business_condition',
        'dh_modeling_business_entity','dh_modeling_logic_model','dh_modeling_logic_model_version'] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS modeling_metric_entity_guard ON %I',table_name);
        EXECUTE format('CREATE TRIGGER modeling_metric_entity_guard BEFORE %s ON %I FOR EACH ROW EXECUTE FUNCTION modeling_metric_entity_guard()',
            CASE WHEN table_name IN ('dh_modeling_indicator_model','dh_modeling_business_condition') THEN 'INSERT OR UPDATE OR DELETE'
                ELSE 'UPDATE OR DELETE' END,table_name);
    END LOOP;
END $install$;
COMMIT;
