-- Migration: 0185 指标模型分组与独立业务限定。
-- Purpose: 新增单租户业务限定定义及来源保护，将指标模型分为原子指标、业务限定和派生指标。
-- Depends on: 0184_asset_my-assets-root-menu.sql；0171_modeling_concept-indicator-models.sql 已建立指标页面和模型来源。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；结构与触发器可重放，菜单按稳定组件校验，仅首次迁移复制原指标导航授权。
-- Rollback: 单事务失败完整回滚；提交后恢复执行前菜单和授权备份，新增业务限定数据先导出再删除，序列前进不回退。
-- 本迁移不执行计算逻辑；菜单迁移持有权限表锁，请在维护窗口停止菜单和权限写入并刷新权限缓存。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE SEQUENCE IF NOT EXISTS dh_modeling_business_condition_seq;
CREATE TABLE IF NOT EXISTS dh_modeling_business_condition (
    id bigint PRIMARY KEY DEFAULT nextval('dh_modeling_business_condition_seq'),
    code varchar(100) NOT NULL CHECK (code ~ '^[a-zA-Z][a-zA-Z0-9_]*$'),
    name varchar(100) NOT NULL CHECK (nullif(btrim(name),'') IS NOT NULL),
    data_domain_id bigint NOT NULL, subject_domain_id bigint, owner_user_id bigint,
    description varchar(2000), business_entity varchar(100), data_freshness varchar(100),
    source_model_id bigint NOT NULL, source_version_id bigint NOT NULL,
    config_type varchar(16) NOT NULL DEFAULT 'BASIC' CHECK (config_type='BASIC'),
    logic_mode varchar(16) NOT NULL DEFAULT 'UNIFIED' CHECK (logic_mode='UNIFIED'),
    expression varchar(4000) NOT NULL CHECK (nullif(btrim(expression),'') IS NOT NULL),
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0),
    creator varchar(64) DEFAULT '', create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '', update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1))
);
COMMENT ON TABLE dh_modeling_business_condition IS '数据建模-业务限定';
COMMENT ON COLUMN dh_modeling_business_condition.id IS '业务限定编号';
COMMENT ON COLUMN dh_modeling_business_condition.code IS '英文编码';
COMMENT ON COLUMN dh_modeling_business_condition.name IS '中文名称';
COMMENT ON COLUMN dh_modeling_business_condition.data_domain_id IS '归属数据域编号';
COMMENT ON COLUMN dh_modeling_business_condition.subject_domain_id IS '可选分析主题域编号';
COMMENT ON COLUMN dh_modeling_business_condition.owner_user_id IS '负责人用户编号';
COMMENT ON COLUMN dh_modeling_business_condition.description IS '描述';
COMMENT ON COLUMN dh_modeling_business_condition.business_entity IS '可选业务实体描述';
COMMENT ON COLUMN dh_modeling_business_condition.data_freshness IS '数据时效说明';
COMMENT ON COLUMN dh_modeling_business_condition.source_model_id IS '来源逻辑模型编号';
COMMENT ON COLUMN dh_modeling_business_condition.source_version_id IS '来源不可变已发布版本编号';
COMMENT ON COLUMN dh_modeling_business_condition.config_type IS '配置方式：当前仅支持基础限定BASIC';
COMMENT ON COLUMN dh_modeling_business_condition.logic_mode IS '计算逻辑方式：当前仅支持全时段统一UNIFIED';
COMMENT ON COLUMN dh_modeling_business_condition.expression IS '只保存不执行的计算逻辑定义';
COMMENT ON COLUMN dh_modeling_business_condition.status IS '状态：草稿DRAFT或已启用ACTIVE';
COMMENT ON COLUMN dh_modeling_business_condition.revision IS '并发修订号';
COMMENT ON COLUMN dh_modeling_business_condition.creator IS '创建人编号';
COMMENT ON COLUMN dh_modeling_business_condition.create_time IS '创建时间';
COMMENT ON COLUMN dh_modeling_business_condition.updater IS '更新人编号';
COMMENT ON COLUMN dh_modeling_business_condition.update_time IS '更新时间';
COMMENT ON COLUMN dh_modeling_business_condition.deleted IS '逻辑删除标记';
CREATE UNIQUE INDEX IF NOT EXISTS uq_modeling_condition_code ON dh_modeling_business_condition(lower(code)) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_modeling_condition_domain ON dh_modeling_business_condition(data_domain_id,subject_domain_id) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_modeling_condition_source ON dh_modeling_business_condition(source_model_id,source_version_id) WHERE deleted=0;
COMMENT ON INDEX uq_modeling_condition_code IS '有效业务限定英文编码不区分大小写唯一';
COMMENT ON INDEX idx_modeling_condition_domain IS '有效业务限定按归属筛选';
COMMENT ON INDEX idx_modeling_condition_source IS '有效业务限定来源版本引用';

-- 与现有指标和规划引用共用事务锁，覆盖逻辑删除、来源废弃、下线和发布快照修改。
CREATE OR REPLACE FUNCTION modeling_business_condition_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path="datahub-center",pg_catalog AS $guard$
DECLARE ref_field text; referenced boolean;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0));
    IF TG_TABLE_NAME='dh_modeling_business_condition' THEN
        IF TG_OP='DELETE' THEN RETURN OLD; END IF;
        IF NEW.deleted<>0 THEN RETURN NEW; END IF;
        IF NOT EXISTS(SELECT 1 FROM dh_modeling_data_domain WHERE id=NEW.data_domain_id AND deleted=0)
           OR (NEW.subject_domain_id IS NOT NULL AND NOT EXISTS(
               SELECT 1 FROM dh_modeling_subject_domain WHERE id=NEW.subject_domain_id AND deleted=0)) THEN
            RAISE EXCEPTION '业务限定归属不存在' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM dh_modeling_logic_model_version v
            JOIN dh_modeling_logic_model m ON m.id=v.model_id AND m.deleted=0 AND m.enabled=1 AND m.status<>'DISCARDED' AND m.data_domain_id=NEW.data_domain_id
            WHERE v.id=NEW.source_version_id AND v.model_id=NEW.source_model_id AND v.deleted=0 AND v.status='PUBLISHED') THEN
            RAISE EXCEPTION '业务限定来源必须为有效逻辑模型的已发布版本' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    ref_field:=CASE TG_TABLE_NAME WHEN 'dh_modeling_logic_model' THEN 'source_model_id'
        WHEN 'dh_modeling_logic_model_version' THEN 'source_version_id'
        WHEN 'dh_modeling_data_domain' THEN 'data_domain_id' ELSE 'subject_domain_id' END;
    EXECUTE format('SELECT EXISTS(SELECT 1 FROM dh_modeling_business_condition WHERE %I=$1 AND deleted=0)',ref_field)
        INTO referenced USING OLD.id;
    IF referenced THEN
        IF TG_OP='DELETE' THEN RAISE EXCEPTION '对象仍被业务限定引用，不能删除' USING ERRCODE='23514'; END IF;
        IF NEW.deleted<>0 THEN RAISE EXCEPTION '对象仍被业务限定引用，不能删除' USING ERRCODE='23514'; END IF;
        IF TG_TABLE_NAME='dh_modeling_logic_model' AND
            (to_jsonb(NEW)->>'status'='DISCARDED' OR (to_jsonb(NEW)->>'enabled')::integer<>1
             OR (to_jsonb(NEW)->>'data_domain_id') IS DISTINCT FROM (to_jsonb(OLD)->>'data_domain_id')) THEN
            RAISE EXCEPTION '逻辑模型仍被业务限定引用，不能废弃或停用' USING ERRCODE='23514';
        END IF;
        IF TG_TABLE_NAME='dh_modeling_logic_model_version' AND
            ((to_jsonb(NEW)->>'status')<>'PUBLISHED' OR
             (to_jsonb(NEW)-ARRAY['updater','update_time']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['updater','update_time'])) THEN
            RAISE EXCEPTION '已发布版本仍被业务限定引用，不能变更' USING ERRCODE='23514';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $guard$;
COMMENT ON FUNCTION modeling_business_condition_guard() IS '串行保护业务限定的规划归属和不可变来源版本';
DO $install_guards$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['dh_modeling_business_condition','dh_modeling_logic_model',
        'dh_modeling_logic_model_version','dh_modeling_data_domain','dh_modeling_subject_domain'] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS modeling_business_condition_guard ON %I',table_name);
        EXECUTE format('CREATE TRIGGER modeling_business_condition_guard BEFORE %s ON %I FOR EACH ROW EXECUTE FUNCTION modeling_business_condition_guard()',
            CASE WHEN table_name='dh_modeling_business_condition' THEN 'INSERT OR UPDATE OR DELETE' ELSE 'UPDATE OR DELETE' END,table_name);
    END LOOP;
END $install_guards$;

LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;
DO $metric_menus$
DECLARE metric_parent_id bigint; matches integer; page_id bigint; button_id bigint; item record; action text;
    initial_split boolean; navigation_ids bigint[]:=ARRAY[]::bigint[]; menu_high bigint; role_high bigint; inserted integer;
BEGIN
    SELECT count(*),min(id) INTO matches,metric_parent_id FROM system_menu
    WHERE component='modeling/indicator-model/index' OR
        (component_name='ModelingIndicatorModelRoot' AND path='indicator-model');
    IF matches<>1 OR NOT EXISTS(SELECT 1 FROM system_menu WHERE id=metric_parent_id AND deleted=0 AND status=0) THEN
        RAISE EXCEPTION '0185 指标模型菜单缺失、不唯一或已停用，请先核查菜单';
    END IF;
    SELECT type=2 INTO initial_split FROM system_menu WHERE id=metric_parent_id;
    IF initial_split THEN
        UPDATE system_menu SET name='指标模型',type=1,permission='',component=NULL,component_name='ModelingIndicatorModelRoot',
            updater='migration:0185',update_time=CURRENT_TIMESTAMP WHERE id=metric_parent_id;
    ELSIF NOT EXISTS(SELECT 1 FROM system_menu WHERE id=metric_parent_id AND type=1 AND coalesce(permission,'')='' AND component_name='ModelingIndicatorModelRoot') THEN
        RAISE EXCEPTION '0185 指标模型目录身份冲突';
    END IF;
    SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq)) INTO menu_high FROM system_menu;
    FOR item IN SELECT * FROM (VALUES
        ('原子指标','atomic','modeling/indicator-model/atomic/index','ModelingAtomicIndicator','modeling:indicator-model:query',10),
        ('业务限定','condition','modeling/business-condition/index','ModelingBusinessCondition','modeling:business-condition:query',20),
        ('派生指标','derived','modeling/indicator-model/derived/index','ModelingDerivedIndicator','modeling:indicator-model:query',30)
    ) t(label,path,component,component_name,permission,sort) LOOP
        SELECT count(*),min(id) INTO matches,page_id FROM system_menu WHERE component=item.component;
        IF matches>1 THEN RAISE EXCEPTION '0185 指标子页面组件身份重复：%',item.component; END IF;
        IF matches=0 THEN
            IF EXISTS(SELECT 1 FROM system_menu WHERE system_menu.parent_id=metric_parent_id AND path=item.path) THEN
                RAISE EXCEPTION '0185 指标子页面路径已被占用：%',item.path;
            END IF;
            menu_high:=menu_high+1; page_id:=menu_high;
            INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
                visible,keep_alive,always_show,creator,updater,deleted)
            VALUES(page_id,item.label,item.permission,2,item.sort,metric_parent_id,item.path,'carbon:chart-line',item.component,
                item.component_name,0,true,true,true,'migration:0185','migration:0185',0);
        ELSIF NOT EXISTS(SELECT 1 FROM system_menu WHERE id=page_id AND system_menu.parent_id=metric_parent_id AND type=2
            AND path=item.path AND permission=item.permission AND deleted=0) THEN
            RAISE EXCEPTION '0185 指标子页面归属或权限冲突：%',item.component;
        END IF;
        IF item.path IN ('atomic','derived') THEN navigation_ids:=array_append(navigation_ids,page_id); END IF;
        IF item.path='condition' THEN
            FOREACH action IN ARRAY ARRAY['create','update','delete'] LOOP
                SELECT count(*),min(id) INTO matches,button_id FROM system_menu WHERE permission='modeling:business-condition:'||action;
                IF matches>1 THEN RAISE EXCEPTION '0185 业务限定按钮权限重复'; END IF;
                IF matches=0 THEN
                    menu_high:=menu_high+1; button_id:=menu_high;
                    INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show,creator,updater,deleted)
                    VALUES(button_id,'业务限定'||CASE action WHEN 'create' THEN '新增' WHEN 'update' THEN '修改及启停' ELSE '删除' END,
                        'modeling:business-condition:'||action,3,0,page_id,'',0,true,true,true,'migration:0185','migration:0185',0);
                ELSIF NOT EXISTS(SELECT 1 FROM system_menu WHERE id=button_id AND system_menu.parent_id=page_id AND type=3 AND deleted=0) THEN
                    RAISE EXCEPTION '0185 业务限定按钮归属冲突';
                END IF;
            END LOOP;
        END IF;
    END LOOP;
    IF initial_split THEN
        -- 原指标查询授权仅继承给原子和派生导航，新增业务限定权限由管理员明确分配。
        SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_role_menu_seq)) INTO role_high FROM system_role_menu;
        INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,creator,updater,deleted)
        SELECT role_high+row_number() OVER(ORDER BY pending.role_id,pending.tenant_id,pending.menu_id),
            pending.role_id,pending.menu_id,pending.tenant_id,'migration:0185','migration:0185',0
        FROM (SELECT DISTINCT source.role_id,source.tenant_id,navigation.id AS menu_id
            FROM system_role_menu source CROSS JOIN unnest(navigation_ids) navigation(id)
            WHERE source.deleted=0 AND source.menu_id=metric_parent_id AND NOT EXISTS(SELECT 1 FROM system_role_menu existing
                WHERE existing.deleted=0 AND existing.role_id=source.role_id AND existing.tenant_id=source.tenant_id AND existing.menu_id=navigation.id)) pending;
        GET DIAGNOSTICS inserted=ROW_COUNT;
        IF inserted>0 THEN PERFORM setval('system_role_menu_seq',greatest((SELECT last_value FROM system_role_menu_seq),role_high+inserted)); END IF;
        IF EXISTS(SELECT 1 FROM system_tenant_package WHERE jsonb_typeof(menu_ids::jsonb) IS DISTINCT FROM 'array') THEN
            RAISE EXCEPTION '0185 套餐菜单编号必须为JSON数组';
        END IF;
        WITH permitted AS (SELECT package.id,package.menu_ids::jsonb AS original FROM system_tenant_package package
            WHERE package.deleted=0 AND EXISTS(SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value) WHERE entry.value=metric_parent_id::text)),
        expanded AS (SELECT permitted.id,permitted.original||to_jsonb(ARRAY(SELECT navigation.id FROM unnest(navigation_ids) navigation(id)
            WHERE NOT EXISTS(SELECT 1 FROM jsonb_array_elements_text(permitted.original) entry(value) WHERE entry.value=navigation.id::text))) entries FROM permitted)
        UPDATE system_tenant_package package SET menu_ids=expanded.entries::text,updater='migration:0185',update_time=CURRENT_TIMESTAMP
            FROM expanded WHERE package.id=expanded.id;
    END IF;
    PERFORM setval('system_menu_seq',greatest((SELECT last_value FROM system_menu_seq),menu_high));
    PERFORM setval('system_menu_id_seq',greatest((SELECT last_value FROM system_menu_id_seq),menu_high));
END $metric_menus$;
COMMIT;
