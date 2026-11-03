-- Migration: 0169 数据中台业务切换为单租户。
-- Purpose: 删除 dh_* 业务表的 tenant_id，保留全局业务唯一性、复合引用、排他约束和守卫；全局命名策略替换 TENANT 范围。
-- Depends on: 0168_supply_retire-legacy-invoke-menu.sql 已执行，或已核验平台达到对应结构；旧应用和后台任务全部停止。
-- Target: datahub-center 开发平台库；新库使用包含本迁移的全量基线。system、infra、bpm 等脚手架表及外部引擎 tenant_code 不在删除范围内。
-- Repeatable: 是；已移除的列不再处理，命名范围转换幂等，全局扫描游标只在首次转换时重置。
-- Rollback: 单事务失败完整回滚；提交后原租户归属不可恢复，恢复必须使用执行前备份和旧应用。全局扫描游标首次重置，下一轮重新扫描，业务记录不删除。
-- 风险：事务对全部受影响业务表获取排他锁并重建索引，执行前停机并备份；十秒内无法取得锁即失败。新全局唯一键或有效期区间冲突会在删除列前阻断，不自动合并或删除业务数据。
-- 开发状态：旧分组/整表恢复材料的认证摘要及类型化检查点绑定包含租户，不能自动重签或沿用。发现这些旧运行状态即阻断；未上线开发环境推荐使用新基线新建数据库，不将失效认证材料视为迁移成功。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '15min';

CREATE TEMP TABLE single_tenant_tables ON COMMIT DROP AS
SELECT c.oid AS table_oid, c.relname AS table_name, a.attnum AS tenant_attnum
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
WHERE n.nspname = 'datahub-center' AND c.relname LIKE 'dh\_%' ESCAPE '\' AND c.relkind = 'r';

CREATE TEMP TABLE single_tenant_constraints (
    table_oid oid, constraint_name name, constraint_type "char", definition text
) ON COMMIT DROP;
CREATE TEMP TABLE single_tenant_indexes (
    table_oid oid, index_oid oid, index_name name, is_unique boolean, is_constraint boolean, definition text
) ON COMMIT DROP;

DO $single_tenant_plan$
DECLARE
    item record;
    definition text;
    probe_name text;
    has_legacy_material boolean;
BEGIN
    -- 在读取约束和验证唯一性前阻止并发写入，避免预检和重建之间出现新冲突。
    FOR item IN SELECT * FROM single_tenant_tables ORDER BY table_name LOOP
        EXECUTE format('LOCK TABLE %s IN ACCESS EXCLUSIVE MODE', item.table_oid::regclass);
    END LOOP;

    -- 当前权限由 database + schema + table 精确定位；退役已被精确索引替代的旧无 schema 唯一键。
    -- 某些历史基线在精确索引之后又重建旧索引，不能把该残留带入全局唯一性预检。
    IF to_regclass('"datahub-center".uk_safety_table_exact_role') IS NOT NULL THEN
        DROP INDEX IF EXISTS "datahub-center".uk_dh_safety_table_permission_role;
    END IF;
    IF to_regclass('"datahub-center".uk_safety_field_exact_role') IS NOT NULL THEN
        DROP INDEX IF EXISTS "datahub-center".uk_dh_safety_field_permission_role;
    END IF;

    FOR item IN SELECT * FROM single_tenant_tables WHERE table_name IN(
        'dh_dataforge_access_group_pin','dh_dataforge_access_table_pin','dh_dataforge_access_checkpoint') LOOP
        EXECUTE format('SELECT EXISTS(SELECT 1 FROM %s)',item.table_oid::regclass) INTO has_legacy_material;
        IF has_legacy_material THEN
            RAISE EXCEPTION '单租户迁移发现无法沿用认证绑定的旧开发运行材料：%；请先完成原任务恢复和核验，或使用新基线新建开发库，不得自动重签或清空',item.table_name;
        END IF;
    END LOOP;

    -- 未知的租户表达式、分区或跨脚手架引用不作猜测性改写，直接要求先核验实际结构。
    IF EXISTS (
        SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        JOIN pg_attribute a ON a.attrelid=c.oid AND a.attname='tenant_id' AND NOT a.attisdropped
        WHERE n.nspname='datahub-center' AND c.relname LIKE 'dh\_%' ESCAPE '\' AND c.relkind='p'
    ) THEN RAISE EXCEPTION '单租户迁移不接受未核验的租户分区表'; END IF;

    FOR item IN
        SELECT i.*, t.tenant_attnum, t.table_name, ci.relname AS index_name,
               EXISTS(SELECT 1 FROM pg_constraint con WHERE con.conindid=i.indexrelid AND con.contype IN ('p','u','x')) AS is_constraint
        FROM single_tenant_tables t JOIN pg_index i ON i.indrelid=t.table_oid
        JOIN pg_class ci ON ci.oid=i.indexrelid
        WHERE t.tenant_attnum=ANY(i.indkey)
           OR pg_get_expr(i.indexprs,i.indrelid) ~ '\mtenant_id\M'
           OR pg_get_expr(i.indpred,i.indrelid) ~ '\mtenant_id\M'
    LOOP
        IF COALESCE(pg_get_expr(item.indexprs,item.indrelid),'') ~ '\mtenant_id\M'
           OR COALESCE(pg_get_expr(item.indpred,item.indrelid),'') ~ '\mtenant_id\M' THEN
            RAISE EXCEPTION '单租户迁移遇到未支持的租户表达式索引：%', item.index_name;
        END IF;
        IF EXISTS(SELECT 1 FROM generate_series(1,item.indnatts) p
                  WHERE item.indkey[p-1]=item.tenant_attnum AND pg_get_indexdef(item.indexrelid,p,true)<>'tenant_id') THEN
            RAISE EXCEPTION '单租户迁移遇到未核验的租户索引排序或操作符：%', item.index_name;
        END IF;
        IF item.indnkeyatts=1 AND item.indkey[0]=item.tenant_attnum THEN
            IF item.table_name<>'dh_governance_quality_exception_expiry_cursor' THEN
                RAISE EXCEPTION '单租户迁移遇到未核验的仅租户索引：%', item.index_name;
            END IF;
            CONTINUE;
        END IF;
        definition:=pg_get_indexdef(item.indexrelid);
        definition:=regexp_replace(definition,'\mtenant_id\M, ','','g');
        definition:=regexp_replace(definition,', tenant_id\M','','g');
        definition:=replace(definition,' INCLUDE (tenant_id)','');
        IF definition ~ '\mtenant_id\M' THEN
            RAISE EXCEPTION '单租户索引仍引用租户列：%', item.index_name;
        END IF;
        INSERT INTO single_tenant_indexes VALUES(item.indrelid,item.indexrelid,item.index_name,item.indisunique,item.is_constraint,definition);
        IF item.indisunique THEN
            -- 由 PostgreSQL 原生唯一索引预检，保留表达式、排序规则、空值语义和部分索引条件。
            probe_name:='single_tenant_probe_'||item.indexrelid;
            BEGIN
                EXECUTE replace(definition,'INDEX '||quote_ident(item.index_name)||' ON ',
                                'INDEX '||quote_ident(probe_name)||' ON ');
            EXCEPTION WHEN unique_violation THEN
                RAISE EXCEPTION '单租户预检失败：表 % 的全局唯一键 % 存在冲突，请先明确处理冲突记录',item.table_name,item.index_name;
            END;
            EXECUTE format('DROP INDEX %I',probe_name);
        END IF;
    END LOOP;

    FOR item IN
        SELECT con.*, t.tenant_attnum, t.table_name
        FROM single_tenant_tables t JOIN pg_constraint con ON con.conrelid=t.table_oid
        WHERE t.tenant_attnum=ANY(con.conkey) AND con.contype IN ('p','u','f','x','c')
    LOOP
        definition:=pg_get_constraintdef(item.oid);
        IF item.contype='c' THEN
            IF item.conkey=ARRAY[item.tenant_attnum]::smallint[] THEN CONTINUE; END IF;
            IF item.conname='ck_safety_write_audit_identity' AND item.table_name='dh_governance_safety_write_audit' THEN
                definition:='CHECK (database_id > 0 AND role_id > 0 AND actor_id > 0)';
            ELSE
                RAISE EXCEPTION '单租户迁移遇到未核验的混合检查约束：%',item.conname;
            END IF;
        ELSE
            IF item.contype='f' AND NOT EXISTS(SELECT 1 FROM single_tenant_tables WHERE table_oid=item.confrelid) THEN
                RAISE EXCEPTION '业务租户外键引用范围外对象：%',item.conname;
            END IF;
            IF item.conkey=ARRAY[item.tenant_attnum]::smallint[] THEN
                IF item.table_name='dh_governance_quality_exception_expiry_cursor' AND item.contype='u' THEN CONTINUE; END IF;
                RAISE EXCEPTION '单租户迁移遇到未核验的仅租户约束：%',item.conname;
            END IF;
            definition:=regexp_replace(definition,'\mtenant_id\M WITH =, ','','g');
            definition:=regexp_replace(definition,'\mtenant_id\M, ','','g');
            definition:=regexp_replace(definition,', tenant_id\M','','g');
        END IF;
        IF definition ~ '\mtenant_id\M' THEN RAISE EXCEPTION '单租户约束仍引用租户列：%',item.conname; END IF;
        INSERT INTO single_tenant_constraints VALUES(item.conrelid,item.conname,item.contype,definition);
        IF item.contype='x' THEN
            probe_name:='single_tenant_probe_'||item.oid;
            BEGIN
                EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I %s',item.conrelid::regclass,probe_name,definition);
            EXCEPTION WHEN exclusion_violation THEN
                RAISE EXCEPTION '单租户预检失败：表 % 的全局有效期排他约束 % 存在冲突',item.table_name,item.conname;
            END;
            EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I',item.conrelid::regclass,probe_name);
        END IF;
    END LOOP;
END $single_tenant_plan$;

DO $single_tenant_apply$
DECLARE item record;
BEGIN
    -- 先解除外键，再移除其依赖的唯一键；重建时顺序相反。
    FOR item IN SELECT * FROM single_tenant_constraints ORDER BY (constraint_type='f') DESC,table_oid,constraint_name LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I',item.table_oid::regclass,item.constraint_name);
    END LOOP;
    FOR item IN SELECT * FROM single_tenant_indexes WHERE NOT is_constraint LOOP
        EXECUTE format('DROP INDEX %I',item.index_name);
    END LOOP;
    IF EXISTS(SELECT 1 FROM single_tenant_tables WHERE table_name='dh_governance_standard_code_item') THEN
        DROP TRIGGER IF EXISTS trg_std_code_item_validate_hierarchy ON dh_governance_standard_code_item;
    END IF;
    FOR item IN SELECT * FROM single_tenant_tables ORDER BY table_name LOOP
        -- 不使用 CASCADE；未知视图或外部依赖会阻止迁移并使整笔事务回滚。
        EXECUTE format('ALTER TABLE %s DROP COLUMN tenant_id',item.table_oid::regclass);
    END LOOP;
    FOR item IN SELECT * FROM single_tenant_constraints WHERE constraint_type<>'f' ORDER BY table_oid,constraint_name LOOP
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I %s',item.table_oid::regclass,item.constraint_name,item.definition);
    END LOOP;
    FOR item IN SELECT * FROM single_tenant_indexes WHERE NOT is_constraint LOOP EXECUTE item.definition; END LOOP;
    FOR item IN SELECT * FROM single_tenant_constraints WHERE constraint_type='f' ORDER BY table_oid,constraint_name LOOP
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I %s',item.table_oid::regclass,item.constraint_name,item.definition);
    END LOOP;

    IF EXISTS(SELECT 1 FROM single_tenant_tables WHERE table_name='dh_governance_quality_exception_expiry_cursor') THEN
        -- 扫描位置属于可重建内部状态，重置后从头扫描，不改变提交或审批业务数据。
        DELETE FROM dh_governance_quality_exception_expiry_cursor;
        INSERT INTO dh_governance_quality_exception_expiry_cursor(id) VALUES(1);
        ALTER TABLE dh_governance_quality_exception_expiry_cursor ALTER COLUMN id SET DEFAULT 1;
        ALTER TABLE dh_governance_quality_exception_expiry_cursor ADD CONSTRAINT ck_quality_expiry_cursor_singleton CHECK(id=1);
        COMMENT ON COLUMN dh_governance_quality_exception_expiry_cursor.id IS '全局扫描游标，固定为一';
    END IF;
END $single_tenant_apply$;

CREATE OR REPLACE FUNCTION dh_validate_standard_code_item_hierarchy() RETURNS trigger LANGUAGE plpgsql AS $guard$
DECLARE resolved_type varchar(100); has_cycle boolean;
BEGIN
    IF NEW.deleted=0 THEN
        -- 码集作为聚合锁根，串行化层级修改，阻止并发父节点更新形成环。
        SELECT code_set.type INTO resolved_type FROM "datahub-center".dh_governance_standard_code_set code_set
        WHERE code_set.id=NEW.code_set_id AND code_set.deleted=0 FOR UPDATE;
        IF NOT FOUND THEN RAISE EXCEPTION '标准码项必须引用有效码集'; END IF;
        IF NEW.dict_type<>resolved_type THEN RAISE EXCEPTION '标准码项类型必须与所属码集一致'; END IF;
    END IF;
    IF NEW.parent_id IS NULL THEN RETURN NEW; END IF;
    IF NEW.parent_id=NEW.id THEN RAISE EXCEPTION '标准码项不能以自身为父节点'; END IF;
    IF NOT EXISTS(SELECT 1 FROM "datahub-center".dh_governance_standard_code_item p
                  WHERE p.id=NEW.parent_id AND p.code_set_id=NEW.code_set_id AND p.deleted=0) THEN
        RAISE EXCEPTION '标准码项父节点必须为同一码集的有效节点';
    END IF;
    WITH RECURSIVE ancestors AS (
        SELECT p.id,p.parent_id,ARRAY[p.id]::int4[] AS path FROM "datahub-center".dh_governance_standard_code_item p
        WHERE p.id=NEW.parent_id AND p.code_set_id=NEW.code_set_id AND p.deleted=0
        UNION ALL
        SELECT p.id,p.parent_id,a.path||p.id FROM ancestors a
        JOIN "datahub-center".dh_governance_standard_code_item p ON p.id=a.parent_id
        AND p.code_set_id=NEW.code_set_id AND p.deleted=0 WHERE NOT p.id=ANY(a.path)
    ) SELECT EXISTS(SELECT 1 FROM ancestors WHERE id=NEW.id) INTO has_cycle;
    IF has_cycle THEN RAISE EXCEPTION '标准码项层级存在循环'; END IF;
    RETURN NEW;
END $guard$;
COMMENT ON FUNCTION dh_validate_standard_code_item_hierarchy() IS '保护标准码集层级引用、类型一致性和无环约束';
DO $single_tenant_code_hierarchy$
BEGIN
    IF to_regclass('"datahub-center".dh_governance_standard_code_item') IS NOT NULL THEN
        DROP TRIGGER IF EXISTS trg_std_code_item_validate_hierarchy ON dh_governance_standard_code_item;
        CREATE TRIGGER trg_std_code_item_validate_hierarchy BEFORE INSERT OR UPDATE OF code_set_id,dict_type,parent_id,deleted
        ON dh_governance_standard_code_item FOR EACH ROW EXECUTE FUNCTION dh_validate_standard_code_item_hierarchy();
    END IF;
END $single_tenant_code_hierarchy$;

CREATE OR REPLACE FUNCTION modeling_guard_layer_reference() RETURNS trigger LANGUAGE plpgsql AS $guard$
DECLARE v_layer record;
BEGIN
    IF NEW.deleted<>0 OR NEW.layer_id IS NULL THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' AND NEW.layer_id IS NOT DISTINCT FROM OLD.layer_id THEN RETURN NEW; END IF;
    SELECT node_type,status INTO v_layer FROM "datahub-center".dh_modeling_dw_layer
    WHERE id=NEW.layer_id AND deleted=0 FOR SHARE;
    IF NOT FOUND OR v_layer.node_type<>'LAYER' OR v_layer.status<>0 THEN
        RAISE EXCEPTION '分层不存在、不是实际分层或已停用' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $guard$;
COMMENT ON FUNCTION modeling_guard_layer_reference() IS '保护模型对有效实际分层的引用';

CREATE OR REPLACE FUNCTION modeling_ownership_reference_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $guard$
DECLARE row_data jsonb; object_id bigint; deleting boolean; ref_field text; ref_table text; found_row boolean;
BEGIN
    row_data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    object_id:=(row_data->>'id')::bigint;
    PERFORM pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0));
    deleting:=TG_OP='DELETE' OR COALESCE((row_data->>'deleted')::integer,0)<>0;
    IF TG_TABLE_NAME='dh_modeling_logic_model' AND NOT deleting THEN
        FOR ref_field,ref_table IN SELECT * FROM(VALUES('data_domain_id','dh_modeling_data_domain'),('subject_domain_id','dh_modeling_subject_domain'),('layer_id','dh_modeling_dw_layer')) r(f,t) LOOP
            IF row_data->>ref_field IS NOT NULL THEN
                EXECUTE format('SELECT EXISTS(SELECT 1 FROM "datahub-center".%I WHERE id=$1 AND deleted=0)',ref_table)
                    INTO found_row USING (row_data->>ref_field)::bigint;
                IF NOT found_row THEN RAISE EXCEPTION '模型归属不存在：%',ref_field USING ERRCODE='23514'; END IF;
            END IF;
        END LOOP;
    ELSIF TG_TABLE_NAME IN('dh_modeling_data_domain','dh_modeling_subject_domain','dh_modeling_dw_layer') AND deleting THEN
        ref_field:=CASE TG_TABLE_NAME WHEN 'dh_modeling_data_domain' THEN 'data_domain_id' WHEN 'dh_modeling_subject_domain' THEN 'subject_domain_id' ELSE 'layer_id' END;
        EXECUTE format('SELECT EXISTS(SELECT 1 FROM "datahub-center".dh_modeling_logic_model WHERE %I=$1 AND deleted=0)',ref_field)
            INTO found_row USING object_id;
        IF found_row THEN RAISE EXCEPTION '规划对象仍被模型引用，不能删除' USING ERRCODE='23514'; END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $guard$;
COMMENT ON FUNCTION modeling_ownership_reference_guard() IS '保护模型与数据域、主题域、分层的有效归属和删除引用';

CREATE OR REPLACE FUNCTION modeling_guard_layer_change() RETURNS trigger LANGUAGE plpgsql SET search_path="datahub-center",pg_catalog AS $guard$
BEGIN
    IF TG_OP='UPDATE' AND ROW(NEW.deleted,NEW.status,NEW.node_type,NEW.layer_code,NEW.table_prefix,NEW.default_table_type)
       IS NOT DISTINCT FROM ROW(OLD.deleted,OLD.status,OLD.node_type,OLD.layer_code,OLD.table_prefix,OLD.default_table_type) THEN RETURN NEW; END IF;
    IF EXISTS(SELECT 1 FROM dh_modeling_logic_model WHERE layer_id=OLD.id AND deleted=0) THEN
        RAISE EXCEPTION '分层已被模型引用，不能删除、停用或改变技术配置' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $guard$;
COMMENT ON FUNCTION modeling_guard_layer_change() IS '阻止修改仍被模型引用的分层技术配置';

CREATE OR REPLACE FUNCTION dh_modeling_unified_relation_guard() RETURNS trigger LANGUAGE plpgsql SET search_path="datahub-center",pg_catalog AS $guard$
DECLARE pair jsonb;
BEGIN
    IF NEW.deleted<>0 THEN RETURN NEW; END IF;
    IF NOT EXISTS(SELECT 1 FROM dh_modeling_logic_model WHERE id=NEW.source_model_id AND deleted=0)
       OR NOT EXISTS(SELECT 1 FROM dh_modeling_logic_model WHERE id=NEW.target_model_id AND deleted=0) THEN
        RAISE EXCEPTION 'ER 关系两端必须是有效模型';
    END IF;
    IF NEW.field_pairs IS NULL THEN RAISE EXCEPTION '新建或编辑关系须提供稳定字段标识，不得仅按字段名称保存'; END IF;
    FOR pair IN SELECT value FROM jsonb_array_elements(NEW.field_pairs) LOOP
        IF jsonb_typeof(pair)<>'object' OR NULLIF(btrim(pair->>'sourceFieldUid'),'') IS NULL OR NULLIF(btrim(pair->>'targetFieldUid'),'') IS NULL THEN
            RAISE EXCEPTION '复合关联键必须提供非空的两端字段标识';
        END IF;
    END LOOP;
    IF EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.field_pairs) p GROUP BY p->>'sourceFieldUid' HAVING count(*)>1)
       OR EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.field_pairs) p GROUP BY p->>'targetFieldUid' HAVING count(*)>1) THEN
        RAISE EXCEPTION '复合关联键不能重复使用同一端字段';
    END IF;
    RETURN NEW;
END $guard$;
COMMENT ON FUNCTION dh_modeling_unified_relation_guard() IS '校验 ER 关系有效模型和复合字段标识；字段存在性及双端权限由应用事务校验';

DO $single_tenant_scope$
BEGIN
    IF to_regclass('"datahub-center".dh_governance_standard_naming_policy') IS NOT NULL THEN
        ALTER TABLE dh_governance_standard_naming_policy DROP CONSTRAINT IF EXISTS ck_std_naming_policy_scope;
        ALTER TABLE dh_governance_standard_naming_policy DROP CONSTRAINT IF EXISTS ck_std_naming_policy_scope_id;
        UPDATE dh_governance_standard_naming_policy SET scope_type='GLOBAL' WHERE scope_type='TENANT';
        ALTER TABLE dh_governance_standard_naming_policy ADD CONSTRAINT ck_std_naming_policy_scope
            CHECK(scope_type IN('GLOBAL','DATASOURCE','DATABASE','DATA_DOMAIN','THEME'));
        ALTER TABLE dh_governance_standard_naming_policy ADD CONSTRAINT ck_std_naming_policy_scope_id
            CHECK((scope_type='GLOBAL' AND scope_id=0) OR (scope_type<>'GLOBAL' AND scope_id>0));
        COMMENT ON COLUMN dh_governance_standard_naming_policy.scope_type IS '策略范围：全局、数据源、数据库、数据域或主题域';
    END IF;
    IF to_regclass('"datahub-center".dh_dataforge_dev_schedule') IS NOT NULL THEN
        COMMENT ON COLUMN dh_dataforge_dev_schedule.tenant_code IS '外部调度引擎的执行用户代码，属于引擎运行配置';
    END IF;
END $single_tenant_scope$;

COMMIT;
