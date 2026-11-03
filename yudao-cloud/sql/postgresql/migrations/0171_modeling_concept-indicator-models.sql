-- Migration: 0171 概念模型与指标模型业务能力。
-- Purpose: 新建单租户概念设计、原子及派生指标，保护归属和版本引用，注册管理菜单与细分权限。
-- Depends on: 0170_supply_retire-legacy-invoke-menu-by-identity.sql 对应权限状态及 0169 单租户迁移已完成；已存在统一模型、规划表和建模概览菜单。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库使用同步的全量基线。
-- Repeatable: 是；结构与索引存在即跳过，守卫替换，菜单按组件和权限身份查找，重放不增加授权。
-- Rollback: 失败事务回滚；回退应用可保留新增表。若需移除，先备份两类模型和菜单，再移除新增触发器并恢复 0169 归属守卫；删除新增业务记录不可逆。
-- 风险：菜单维护及触发器安装短时锁表，锁等待超过十秒即失败；不自动赋予现有普通角色新权限，由管理员在角色管理中按需授权。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE SEQUENCE IF NOT EXISTS dh_modeling_concept_model_seq;
CREATE TABLE IF NOT EXISTS dh_modeling_concept_model (
    id bigint PRIMARY KEY DEFAULT nextval('dh_modeling_concept_model_seq'),
    name varchar(100) NOT NULL,
    code varchar(64) NOT NULL,
    data_domain_id bigint NOT NULL,
    subject_domain_id bigint,
    description varchar(2000),
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    design jsonb NOT NULL DEFAULT '{"entities":[],"relations":[]}'::jsonb
        CHECK (jsonb_typeof(design)='object' AND design ?& ARRAY['entities','relations']
            AND jsonb_typeof(design->'entities')='array' AND jsonb_typeof(design->'relations')='array'),
    creator varchar(64) DEFAULT '', create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '', update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1))
);
COMMENT ON TABLE dh_modeling_concept_model IS '概念模型及实体关系设计，单租户业务对象';
COMMENT ON COLUMN dh_modeling_concept_model.id IS '概念模型编号';
COMMENT ON COLUMN dh_modeling_concept_model.name IS '概念模型名称';
COMMENT ON COLUMN dh_modeling_concept_model.code IS '概念模型编码';
COMMENT ON COLUMN dh_modeling_concept_model.data_domain_id IS '所属数据域编号';
COMMENT ON COLUMN dh_modeling_concept_model.subject_domain_id IS '可选分析主题域编号';
COMMENT ON COLUMN dh_modeling_concept_model.description IS '业务说明';
COMMENT ON COLUMN dh_modeling_concept_model.status IS '状态：草稿或已启用';
COMMENT ON COLUMN dh_modeling_concept_model.revision IS '并发修订号';
COMMENT ON COLUMN dh_modeling_concept_model.design IS '实体、属性、稳定标识及关系端点的结构化设计';
COMMENT ON COLUMN dh_modeling_concept_model.creator IS '创建人';
COMMENT ON COLUMN dh_modeling_concept_model.create_time IS '创建时间';
COMMENT ON COLUMN dh_modeling_concept_model.updater IS '更新人';
COMMENT ON COLUMN dh_modeling_concept_model.update_time IS '更新时间';
COMMENT ON COLUMN dh_modeling_concept_model.deleted IS '逻辑删除标记';
CREATE UNIQUE INDEX IF NOT EXISTS uq_modeling_concept_code ON dh_modeling_concept_model(lower(code)) WHERE deleted=0;
CREATE UNIQUE INDEX IF NOT EXISTS uq_modeling_concept_domain_name ON dh_modeling_concept_model(data_domain_id,name) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_modeling_concept_subject ON dh_modeling_concept_model(subject_domain_id) WHERE deleted=0;

CREATE SEQUENCE IF NOT EXISTS dh_modeling_indicator_model_seq;
CREATE TABLE IF NOT EXISTS dh_modeling_indicator_model (
    id bigint PRIMARY KEY DEFAULT nextval('dh_modeling_indicator_model_seq'),
    code varchar(100) NOT NULL, name varchar(100) NOT NULL,
    indicator_type varchar(16) NOT NULL CHECK (indicator_type IN ('ATOMIC','DERIVED')),
    data_domain_id bigint NOT NULL, subject_domain_id bigint, owner_user_id bigint,
    description varchar(2000), definition varchar(2000), unit varchar(50),
    data_freshness varchar(100), result_data_type varchar(32),
    source_model_id bigint, source_version_id bigint, value_field_uid varchar(64), time_field_uid varchar(64),
    aggregation varchar(20) CHECK (aggregation IN ('SUM','COUNT','COUNT_DISTINCT','AVG','MIN','MAX')),
    base_indicator_id bigint, time_period varchar(16) CHECK (time_period IN ('DAY','WEEK','MONTH','QUARTER','YEAR')),
    business_condition varchar(2000),
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    creator varchar(64) DEFAULT '', create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '', update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CONSTRAINT ck_modeling_indicator_definition CHECK (
        (indicator_type='ATOMIC' AND source_model_id IS NOT NULL AND source_version_id IS NOT NULL
            AND nullif(btrim(value_field_uid),'') IS NOT NULL AND aggregation IS NOT NULL
            AND base_indicator_id IS NULL AND time_period IS NULL AND business_condition IS NULL)
        OR (indicator_type='DERIVED' AND base_indicator_id IS NOT NULL AND base_indicator_id<>id
            AND time_period IS NOT NULL AND nullif(btrim(business_condition),'') IS NOT NULL
            AND source_model_id IS NULL AND source_version_id IS NULL AND value_field_uid IS NULL
            AND time_field_uid IS NULL AND aggregation IS NULL)
    )
);
COMMENT ON TABLE dh_modeling_indicator_model IS '原子指标和派生指标的持久业务定义，不执行自由表达式';
COMMENT ON COLUMN dh_modeling_indicator_model.id IS '指标编号';
COMMENT ON COLUMN dh_modeling_indicator_model.code IS '指标英文编码';
COMMENT ON COLUMN dh_modeling_indicator_model.name IS '指标中文名称';
COMMENT ON COLUMN dh_modeling_indicator_model.indicator_type IS '指标类型：原子或派生';
COMMENT ON COLUMN dh_modeling_indicator_model.data_domain_id IS '所属数据域编号';
COMMENT ON COLUMN dh_modeling_indicator_model.subject_domain_id IS '可选分析主题域编号';
COMMENT ON COLUMN dh_modeling_indicator_model.owner_user_id IS '负责人用户编号';
COMMENT ON COLUMN dh_modeling_indicator_model.description IS '指标说明';
COMMENT ON COLUMN dh_modeling_indicator_model.definition IS '指标业务口径';
COMMENT ON COLUMN dh_modeling_indicator_model.unit IS '度量单位';
COMMENT ON COLUMN dh_modeling_indicator_model.data_freshness IS '数据时效说明';
COMMENT ON COLUMN dh_modeling_indicator_model.result_data_type IS '指标结果数据类型';
COMMENT ON COLUMN dh_modeling_indicator_model.source_model_id IS '原子指标来源逻辑模型编号';
COMMENT ON COLUMN dh_modeling_indicator_model.source_version_id IS '原子指标绑定的已发布不可变版本编号';
COMMENT ON COLUMN dh_modeling_indicator_model.value_field_uid IS '参与计算的稳定字段标识';
COMMENT ON COLUMN dh_modeling_indicator_model.time_field_uid IS '统计时间字段稳定标识';
COMMENT ON COLUMN dh_modeling_indicator_model.aggregation IS '聚合方式';
COMMENT ON COLUMN dh_modeling_indicator_model.base_indicator_id IS '派生指标关联的已启用原子指标编号';
COMMENT ON COLUMN dh_modeling_indicator_model.time_period IS '派生指标统计周期';
COMMENT ON COLUMN dh_modeling_indicator_model.business_condition IS '派生指标业务限定定义';
COMMENT ON COLUMN dh_modeling_indicator_model.status IS '状态：草稿或已启用';
COMMENT ON COLUMN dh_modeling_indicator_model.revision IS '并发修订号';
COMMENT ON COLUMN dh_modeling_indicator_model.creator IS '创建人';
COMMENT ON COLUMN dh_modeling_indicator_model.create_time IS '创建时间';
COMMENT ON COLUMN dh_modeling_indicator_model.updater IS '更新人';
COMMENT ON COLUMN dh_modeling_indicator_model.update_time IS '更新时间';
COMMENT ON COLUMN dh_modeling_indicator_model.deleted IS '逻辑删除标记';
CREATE UNIQUE INDEX IF NOT EXISTS uq_modeling_indicator_code ON dh_modeling_indicator_model(lower(code)) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_modeling_indicator_domain ON dh_modeling_indicator_model(data_domain_id) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_modeling_indicator_subject ON dh_modeling_indicator_model(subject_domain_id) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_modeling_indicator_base ON dh_modeling_indicator_model(base_indicator_id) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_modeling_indicator_source ON dh_modeling_indicator_model(source_model_id,source_version_id) WHERE deleted=0;

-- 复用同一归属互斥键，确保规划对象删除与新增模型引用不能并发穿透。
CREATE OR REPLACE FUNCTION modeling_ownership_reference_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $guard$
DECLARE row_data jsonb; object_id bigint; deleting boolean; ref_field text; ref_table text; found_row boolean;
BEGIN
    row_data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    object_id:=(row_data->>'id')::bigint;
    PERFORM pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0));
    deleting:=TG_OP='DELETE' OR COALESCE((row_data->>'deleted')::integer,0)<>0;
    IF TG_TABLE_NAME IN ('dh_modeling_logic_model','dh_modeling_concept_model','dh_modeling_indicator_model') AND NOT deleting THEN
        FOR ref_field,ref_table IN SELECT * FROM(VALUES('data_domain_id','dh_modeling_data_domain'),('subject_domain_id','dh_modeling_subject_domain'),('layer_id','dh_modeling_dw_layer')) r(f,t) LOOP
            IF row_data->>ref_field IS NOT NULL THEN
                EXECUTE format('SELECT EXISTS(SELECT 1 FROM "datahub-center".%I WHERE id=$1 AND deleted=0)',ref_table)
                    INTO found_row USING (row_data->>ref_field)::bigint;
                IF NOT found_row THEN RAISE EXCEPTION '模型归属不存在：%',ref_field USING ERRCODE='23514'; END IF;
            END IF;
        END LOOP;
    ELSIF TG_TABLE_NAME IN('dh_modeling_data_domain','dh_modeling_subject_domain','dh_modeling_dw_layer') AND deleting THEN
        ref_field:=CASE TG_TABLE_NAME WHEN 'dh_modeling_data_domain' THEN 'data_domain_id' WHEN 'dh_modeling_subject_domain' THEN 'subject_domain_id' ELSE 'layer_id' END;
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
COMMENT ON FUNCTION modeling_ownership_reference_guard() IS '保护逻辑、概念及指标模型的规划归属和删除引用';

CREATE OR REPLACE FUNCTION modeling_indicator_reference_guard() RETURNS trigger LANGUAGE plpgsql SET search_path="datahub-center",pg_catalog AS $guard$
DECLARE version_fields jsonb;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0));
    IF TG_TABLE_NAME='dh_modeling_indicator_model' THEN
        IF TG_OP<>'INSERT' AND EXISTS(SELECT 1 FROM dh_modeling_indicator_model WHERE base_indicator_id=OLD.id AND deleted=0) THEN
            IF TG_OP='DELETE' THEN RAISE EXCEPTION '原子指标仍被派生指标引用' USING ERRCODE='23514'; END IF;
            IF (to_jsonb(NEW)-ARRAY['updater','update_time','revision']) IS DISTINCT FROM
               (to_jsonb(OLD)-ARRAY['updater','update_time','revision']) THEN
                RAISE EXCEPTION '原子指标仍被派生指标引用，不能修改或停用' USING ERRCODE='23514';
            END IF;
        END IF;
        IF TG_OP='DELETE' THEN RETURN OLD; END IF;
        IF NEW.deleted<>0 THEN RETURN NEW; END IF;
        IF NEW.indicator_type='DERIVED' THEN
            IF NOT EXISTS(SELECT 1 FROM dh_modeling_indicator_model WHERE id=NEW.base_indicator_id
                AND deleted=0 AND indicator_type='ATOMIC' AND status='ACTIVE' AND data_domain_id=NEW.data_domain_id) THEN
                RAISE EXCEPTION '派生指标必须关联同一数据域的已启用原子指标' USING ERRCODE='23514';
            END IF;
        ELSE
            SELECT v.fields INTO version_fields FROM dh_modeling_logic_model_version v
                JOIN dh_modeling_logic_model m ON m.id=v.model_id AND m.deleted=0 AND m.status<>'DISCARDED'
                WHERE v.id=NEW.source_version_id AND v.model_id=NEW.source_model_id AND v.deleted=0 AND v.status='PUBLISHED';
            IF NOT FOUND THEN RAISE EXCEPTION '指标来源必须是有效模型的已发布版本' USING ERRCODE='23514'; END IF;
            IF jsonb_typeof(version_fields) IS DISTINCT FROM 'array'
                OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(version_fields) field WHERE field->>'fieldUid'=NEW.value_field_uid)
                OR (NEW.time_field_uid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(version_fields) field WHERE field->>'fieldUid'=NEW.time_field_uid)) THEN
                RAISE EXCEPTION '指标字段不在绑定版本的稳定字段集合中' USING ERRCODE='23514';
            END IF;
        END IF;
    ELSE
        IF TG_OP='UPDATE' THEN
            IF NEW.deleted=0 AND (TG_TABLE_NAME<>'dh_modeling_logic_model' OR to_jsonb(NEW)->>'status'<>'DISCARDED') THEN RETURN NEW; END IF;
        END IF;
        IF EXISTS(SELECT 1 FROM dh_modeling_indicator_model WHERE deleted=0 AND
            CASE WHEN TG_TABLE_NAME='dh_modeling_logic_model' THEN source_model_id=OLD.id ELSE source_version_id=OLD.id END) THEN
            RAISE EXCEPTION '模型版本仍被指标引用，不能删除或废弃' USING ERRCODE='23514';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $guard$;
COMMENT ON FUNCTION modeling_indicator_reference_guard() IS '串行保护派生指标、原子指标及已发布来源版本的完整引用';

DO $install_guards$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['dh_modeling_concept_model','dh_modeling_indicator_model'] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS modeling_ownership_reference_guard ON %I',table_name);
        EXECUTE format('CREATE TRIGGER modeling_ownership_reference_guard BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION modeling_ownership_reference_guard()',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['dh_modeling_indicator_model','dh_modeling_logic_model','dh_modeling_logic_model_version'] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS modeling_indicator_reference_guard ON %I',table_name);
        EXECUTE format('CREATE TRIGGER modeling_indicator_reference_guard BEFORE %s ON %I FOR EACH ROW EXECUTE FUNCTION modeling_indicator_reference_guard()',
            CASE WHEN table_name='dh_modeling_indicator_model' THEN 'INSERT OR UPDATE OR DELETE' ELSE 'UPDATE OR DELETE' END,table_name);
    END LOOP;
END $install_guards$;

-- 按组件定位建模目录和新菜单，不复用已删除旧概念模型的历史编号及授权。
LOCK TABLE system_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE root_id bigint; planning_id bigint; matches integer; item record; page_id bigint; button_id bigint; action text;
BEGIN
    SELECT count(*),min(parent_id) INTO matches,root_id FROM system_menu
        WHERE deleted=0 AND type=2 AND trim(both '/' from component)='governance/model-home/index';
    IF matches<>1 THEN RAISE EXCEPTION '建模概览菜单缺失或不唯一，禁止猜测新功能目录'; END IF;
    IF NOT EXISTS(SELECT 1 FROM system_menu WHERE id=root_id AND deleted=0 AND type=1) THEN
        RAISE EXCEPTION '建模概览所属目录无效，禁止猜测新功能目录';
    END IF;
    SELECT count(*),min(parent_id) INTO matches,planning_id FROM system_menu
        WHERE deleted=0 AND type=2 AND trim(both '/' from component)='modeling/datadomain/index';
    IF matches>1 THEN RAISE EXCEPTION '数据域菜单不唯一，禁止猜测概念模型目录'; END IF;
    IF matches=1 AND NOT EXISTS(
        WITH RECURSIVE ancestors AS (
            SELECT id,parent_id FROM system_menu WHERE id=planning_id AND deleted=0 AND type=1
            UNION
            SELECT menu.id,menu.parent_id FROM system_menu menu JOIN ancestors child ON child.parent_id=menu.id
                WHERE menu.deleted=0 AND menu.type=1
        ) SELECT 1 FROM ancestors WHERE id=root_id
    ) THEN RAISE EXCEPTION '数据域菜单所属目录不在数据建模下，禁止跨模块创建菜单'; END IF;
    FOR item IN SELECT * FROM (VALUES
        ('概念模型','concept-model','modeling/concept-model/index','ModelingConceptModel','carbon:concept',coalesce(planning_id,root_id),30),
        ('指标模型','indicator-model','modeling/indicator-model/index','ModelingIndicatorModel','carbon:chart-line',root_id,40)
    ) t(label,path,component,component_name,icon,parent_id,sort) LOOP
        SELECT count(*),min(id) INTO matches,page_id FROM system_menu
            WHERE trim(both '/' from component)=item.component OR permission='modeling:'||item.path||':query';
        IF matches>1 THEN RAISE EXCEPTION '新增模型菜单身份重复：%',item.component; END IF;
        IF matches=0 THEN
            SELECT coalesce(max(id),0)+1 INTO page_id FROM system_menu;
            INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
            VALUES(page_id,item.label,'modeling:'||item.path||':query',2,item.sort,item.parent_id,item.path,item.icon,item.component,item.component_name,0,true,true,true,'migration:0171','migration:0171',0);
        ELSIF NOT EXISTS(SELECT 1 FROM system_menu WHERE id=page_id AND deleted=0 AND type=2 AND parent_id=item.parent_id AND component=item.component AND permission='modeling:'||item.path||':query') THEN
            RAISE EXCEPTION '模型菜单身份冲突，需核查已有菜单：%',page_id;
        END IF;
        FOREACH action IN ARRAY ARRAY['create','update','delete'] LOOP
            SELECT count(*),min(id) INTO matches,button_id FROM system_menu WHERE permission='modeling:'||item.path||':'||action;
            IF matches>1 THEN RAISE EXCEPTION '模型按钮权限重复：%',action; END IF;
            IF matches=0 THEN
                SELECT coalesce(max(id),0)+1 INTO button_id FROM system_menu;
                INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show,creator,updater,deleted)
                VALUES(button_id,item.label||CASE action WHEN 'create' THEN '新增' WHEN 'update' THEN '修改及启停' ELSE '删除' END,
                    'modeling:'||item.path||':'||action,3,0,page_id,'',0,true,true,true,'migration:0171','migration:0171',0);
            ELSIF NOT EXISTS(SELECT 1 FROM system_menu WHERE id=button_id AND parent_id=page_id AND type=3 AND deleted=0) THEN
                RAISE EXCEPTION '模型按钮归属冲突：%',button_id;
            END IF;
        END LOOP;
    END LOOP;
    PERFORM setval('system_menu_id_seq',greatest((SELECT last_value FROM system_menu_id_seq),(SELECT max(id) FROM system_menu)));
    PERFORM setval('system_menu_seq',greatest((SELECT last_value FROM system_menu_seq),(SELECT max(id) FROM system_menu)));
END $menus$;
COMMIT;
