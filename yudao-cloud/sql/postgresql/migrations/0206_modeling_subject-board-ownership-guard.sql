-- Migration: 0206 校验主题域与引用方属于同一数据板块。
-- Purpose: 扩展既有归属守卫，阻止逻辑、概念、指标模型及业务限定引用其他板块的主题域，保护主题父子关系的板块一致性。
-- Depends on: 0205_modeling_domain-workbench.sql 已执行；主题域已有 data_domain_id，既有归属互斥锁及引用守卫已生效。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；只替换守卫函数及两个触发器，不修改任何业务记录，重复执行不改变数据。
-- Rollback: 单事务失败完整回滚；恢复 0171 的归属守卫函数并移除业务限定上的新增归属触发器会失去板块一致性保护，回退前必须确认应用版本及业务约束。
-- 事务边界：取得既有归属互斥锁，短时阻塞相关写入；已有错配记录会阻断迁移，不静默修改归属或删除数据。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
SELECT pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0));
LOCK TABLE dh_modeling_subject_domain,dh_modeling_logic_model,dh_modeling_concept_model,
    dh_modeling_indicator_model,dh_modeling_business_condition IN SHARE ROW EXCLUSIVE MODE;

DO $preflight$
DECLARE table_name text; mismatched boolean;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['dh_modeling_logic_model','dh_modeling_concept_model',
        'dh_modeling_indicator_model','dh_modeling_business_condition'] LOOP
        EXECUTE format('SELECT EXISTS(SELECT 1 FROM %I m WHERE m.deleted=0 AND m.subject_domain_id IS NOT NULL
            AND NOT EXISTS(SELECT 1 FROM dh_modeling_subject_domain s WHERE s.id=m.subject_domain_id
                AND s.deleted=0 AND s.data_domain_id=m.data_domain_id))',table_name) INTO mismatched;
        IF mismatched THEN RAISE EXCEPTION '存在主题域与板块不一致的归属，已阻断迁移：%',table_name; END IF;
    END LOOP;
    IF EXISTS(SELECT 1 FROM dh_modeling_subject_domain s WHERE s.deleted=0 AND s.parent_id<>0
        AND NOT EXISTS(SELECT 1 FROM dh_modeling_subject_domain p
            WHERE p.id=s.parent_id AND p.deleted=0 AND p.data_domain_id=s.data_domain_id)) THEN
        RAISE EXCEPTION '存在主题域父子板块不一致或父级不存在，已阻断迁移';
    END IF;
END $preflight$;

CREATE OR REPLACE FUNCTION modeling_ownership_reference_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $guard$
DECLARE row_data jsonb; object_id bigint; deleting boolean; ref_field text; ref_table text; found_row boolean;
    board_id bigint; subject_id bigint;
BEGIN
    row_data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    object_id:=(row_data->>'id')::bigint;
    PERFORM pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0));
    deleting:=TG_OP='DELETE' OR COALESCE((row_data->>'deleted')::integer,0)<>0;
    board_id:=(row_data->>'data_domain_id')::bigint;
    subject_id:=(row_data->>'subject_domain_id')::bigint;
    IF TG_TABLE_NAME IN ('dh_modeling_logic_model','dh_modeling_concept_model',
        'dh_modeling_indicator_model','dh_modeling_business_condition') AND NOT deleting THEN
        FOR ref_field,ref_table IN SELECT * FROM(VALUES('data_domain_id','dh_modeling_data_domain'),
            ('subject_domain_id','dh_modeling_subject_domain'),('layer_id','dh_modeling_dw_layer')) r(f,t) LOOP
            IF row_data->>ref_field IS NOT NULL THEN
                EXECUTE format('SELECT EXISTS(SELECT 1 FROM "datahub-center".%I WHERE id=$1 AND deleted=0)',ref_table)
                    INTO found_row USING (row_data->>ref_field)::bigint;
                IF NOT found_row THEN RAISE EXCEPTION '模型归属不存在：%',ref_field USING ERRCODE='23514'; END IF;
            END IF;
        END LOOP;
        IF subject_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM "datahub-center".dh_modeling_subject_domain s
            WHERE s.id=subject_id AND s.deleted=0 AND s.data_domain_id=board_id) THEN
            RAISE EXCEPTION '主题域必须属于当前数据板块，选择主题域时板块不能为空' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='dh_modeling_subject_domain' AND NOT deleting THEN
        IF NOT EXISTS(SELECT 1 FROM "datahub-center".dh_modeling_data_domain d WHERE d.id=board_id AND d.deleted=0) THEN
            RAISE EXCEPTION '主题域所属数据板块不存在' USING ERRCODE='23514';
        END IF;
        IF (row_data->>'parent_id')::bigint<>0 AND NOT EXISTS(
            SELECT 1 FROM "datahub-center".dh_modeling_subject_domain p
            WHERE p.id=(row_data->>'parent_id')::bigint AND p.deleted=0 AND p.data_domain_id=board_id) THEN
            RAISE EXCEPTION '上级主题域必须属于当前数据板块' USING ERRCODE='23514';
        END IF;
        IF TG_OP='UPDATE' AND board_id IS DISTINCT FROM OLD.data_domain_id THEN
            IF EXISTS(SELECT 1 FROM "datahub-center".dh_modeling_subject_domain s
                WHERE s.parent_id=object_id AND s.deleted=0 AND s.data_domain_id IS DISTINCT FROM board_id) THEN
                RAISE EXCEPTION '下级主题域仍属于原板块，不能变更板块' USING ERRCODE='23514';
            END IF;
            FOREACH ref_table IN ARRAY ARRAY['dh_modeling_logic_model','dh_modeling_concept_model',
                'dh_modeling_indicator_model','dh_modeling_business_condition'] LOOP
                EXECUTE format('SELECT EXISTS(SELECT 1 FROM "datahub-center".%I WHERE subject_domain_id=$1
                    AND deleted=0 AND data_domain_id IS DISTINCT FROM $2)',ref_table)
                    INTO found_row USING object_id,board_id;
                IF found_row THEN RAISE EXCEPTION '主题域仍被原板块引用，不能变更板块' USING ERRCODE='23514'; END IF;
            END LOOP;
        END IF;
    ELSIF TG_TABLE_NAME IN('dh_modeling_data_domain','dh_modeling_subject_domain','dh_modeling_dw_layer') AND deleting THEN
        ref_field:=CASE TG_TABLE_NAME WHEN 'dh_modeling_data_domain' THEN 'data_domain_id'
            WHEN 'dh_modeling_subject_domain' THEN 'subject_domain_id' ELSE 'layer_id' END;
        FOR ref_table IN SELECT unnest(CASE WHEN ref_field='layer_id' THEN ARRAY['dh_modeling_logic_model']
            ELSE ARRAY['dh_modeling_logic_model','dh_modeling_concept_model','dh_modeling_indicator_model'] END) LOOP
            EXECUTE format('SELECT EXISTS(SELECT 1 FROM "datahub-center".%I WHERE %I=$1 AND deleted=0)',ref_table,ref_field)
                INTO found_row USING object_id;
            IF found_row THEN RAISE EXCEPTION '规划对象仍被模型引用，不能删除' USING ERRCODE='23514'; END IF;
        END LOOP;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $guard$;
COMMENT ON FUNCTION modeling_ownership_reference_guard() IS '保护规划引用及主题域、父级和模型归属的板块一致性';

DROP TRIGGER IF EXISTS modeling_ownership_reference_guard ON dh_modeling_subject_domain;
CREATE TRIGGER modeling_ownership_reference_guard BEFORE INSERT OR UPDATE OR DELETE ON dh_modeling_subject_domain
    FOR EACH ROW EXECUTE FUNCTION modeling_ownership_reference_guard();
DROP TRIGGER IF EXISTS modeling_ownership_reference_guard ON dh_modeling_business_condition;
CREATE TRIGGER modeling_ownership_reference_guard BEFORE INSERT OR UPDATE OR DELETE ON dh_modeling_business_condition
    FOR EACH ROW EXECUTE FUNCTION modeling_ownership_reference_guard();
COMMIT;
