-- Migration: 0278 复合指标与指标数据查询
-- Purpose: 1) dh_modeling_indicator_model 新增 formula 列，指标类型增加 COMPOSITE（复合指标，结果固定 DECIMAL），
--             并重建类型与定义互斥约束；
--          2) 重建 modeling_current_source_guard()：复合指标不绑定来源模型，公式中的 {编码} 必须引用同数据域、
--             已启用的其他指标；被复合指标引用的指标与被派生指标引用一样不能删除、修改或停用；
--          3) 在“指标模型”目录下新增“复合指标”（modeling:indicator-model:query）和
--             “指标查询”（modeling:indicator-model:data-query）两个页面，仅授权超级管理员。
-- Depends on: 0277_governance_data-profile.sql（编号前驱，须先执行；与其无数据依赖）；
--             0276_governance_dynamic-mask-scene.sql（运行时依赖：指标查询结果按 INDICATOR_QUERY 场景调用治理动态脱敏，
--             本脚本不读写其表）；
--             0185_modeling_metric-pages-business-condition.sql（指标模型目录 ModelingIndicatorModelRoot 及原子/派生页面）；
--             0236_modeling_remove-model-versions.sql（当前 ck_modeling_indicator_definition 与 modeling_current_source_guard 定义）。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线 datahub-center.sql 同步包含本迁移。
-- Repeatable: 是；列使用 IF NOT EXISTS，约束先删后建，函数 CREATE OR REPLACE，菜单按组件定位、授权按角色与菜单判重。
-- Rollback: 单事务执行，失败整体回滚。提交后回滚：先删除或改写全部 COMPOSITE 指标；按 0236 定义恢复
--           modeling_current_source_guard() 与 ck_modeling_indicator_definition，恢复类型约束为 ('ATOMIC','DERIVED')；
--           删除 formula 列（不可逆地丢弃公式）；删除组件为 modeling/indicator-model/composite/index 与
--           modeling/indicator-model/query/index 的菜单及其 system_role_menu 授权。原始 SQL 不触发缓存失效，
--           提交后需清理权限菜单相关 Redis 缓存或重启 system 服务。
-- 风险: 约束重建会短暂持有 dh_modeling_indicator_model 的 ACCESS EXCLUSIVE 锁并全表校验（指标表数据量小）；
--       菜单部分持有 system_menu / system_role_menu 共享行排他锁，请在维护窗口执行。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

-- ========== 1. 结构 ==========
ALTER TABLE dh_modeling_indicator_model ADD COLUMN IF NOT EXISTS formula varchar(2000);
COMMENT ON COLUMN dh_modeling_indicator_model.formula IS '复合指标公式：仅数字、+ - * /、括号与 {指标编码} 引用，引用保存为规范编码；原子和派生指标为空';
COMMENT ON COLUMN dh_modeling_indicator_model.indicator_type IS '指标类型：ATOMIC原子、DERIVED派生、COMPOSITE复合';

-- 旧类型约束是建表时的匿名列约束，按定义定位后删除，再以固定名称重建。
DO $type_check$
DECLARE constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT c.conname FROM pg_constraint c
        WHERE c.conrelid = 'dh_modeling_indicator_model'::regclass AND c.contype = 'c'
          AND c.conname NOT IN ('ck_modeling_indicator_definition', 'ck_modeling_indicator_type')
          AND pg_get_constraintdef(c.oid) LIKE '%indicator_type%'
          AND pg_get_constraintdef(c.oid) NOT LIKE '%source_model_id%'
    LOOP
        EXECUTE format('ALTER TABLE dh_modeling_indicator_model DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $type_check$;
ALTER TABLE dh_modeling_indicator_model DROP CONSTRAINT IF EXISTS ck_modeling_indicator_type;
ALTER TABLE dh_modeling_indicator_model ADD CONSTRAINT ck_modeling_indicator_type
    CHECK (indicator_type IN ('ATOMIC', 'DERIVED', 'COMPOSITE'));

ALTER TABLE dh_modeling_indicator_model DROP CONSTRAINT IF EXISTS ck_modeling_indicator_definition;
ALTER TABLE dh_modeling_indicator_model ADD CONSTRAINT ck_modeling_indicator_definition CHECK (
    (indicator_type = 'ATOMIC' AND source_model_id IS NOT NULL
        AND nullif(btrim(value_field_uid), '') IS NOT NULL AND aggregation IS NOT NULL
        AND base_indicator_id IS NULL AND time_period IS NULL AND business_condition IS NULL AND formula IS NULL)
    OR (indicator_type = 'DERIVED' AND base_indicator_id IS NOT NULL AND base_indicator_id <> id
        AND time_period IS NOT NULL AND nullif(btrim(business_condition), '') IS NOT NULL
        AND source_model_id IS NULL AND value_field_uid IS NULL AND time_field_uid IS NULL AND aggregation IS NULL
        AND formula IS NULL)
    OR (indicator_type = 'COMPOSITE' AND nullif(btrim(formula), '') IS NOT NULL
        AND formula ~ '^[0-9+*/(). \t\r\n{}A-Za-z_-]+$' AND formula ~ '\{[A-Za-z][A-Za-z0-9_]*\}'
        AND result_data_type = 'DECIMAL'
        AND source_model_id IS NULL AND value_field_uid IS NULL AND time_field_uid IS NULL AND aggregation IS NULL
        AND base_indicator_id IS NULL AND time_period IS NULL AND business_condition IS NULL));
COMMENT ON CONSTRAINT ck_modeling_indicator_definition ON dh_modeling_indicator_model
    IS '原子、派生、复合指标的定义字段互斥；复合指标只保存公式，结果类型固定 DECIMAL';

-- ========== 2. 引用保护触发器 ==========
-- 在 0236 定义基础上：复合指标跳过来源模型校验并校验公式引用；被复合指标引用的指标获得与派生引用相同的保护。
-- 公式语法与 DAG 环检测由建模服务在同一全局咨询锁内完成，此处兜底保证引用存在、启用且同数据域。
CREATE OR REPLACE FUNCTION modeling_current_source_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path="datahub-center",pg_catalog AS $guard$
DECLARE reference_code text;
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
    IF TG_TABLE_NAME='dh_modeling_indicator_model' AND TG_OP<>'INSERT'
       AND EXISTS(SELECT 1 FROM dh_modeling_indicator_model c WHERE c.deleted=0 AND c.indicator_type='COMPOSITE'
            AND c.id<>OLD.id AND strpos(c.formula,'{'||OLD.code||'}')>0) THEN
        IF TG_OP='DELETE' THEN RAISE EXCEPTION '指标仍被复合指标引用' USING ERRCODE='23514'; END IF;
        IF (to_jsonb(NEW)-ARRAY['updater','update_time','revision']) IS DISTINCT FROM
            (to_jsonb(OLD)-ARRAY['updater','update_time','revision']) THEN
            RAISE EXCEPTION '指标仍被复合指标引用，不能修改或停用' USING ERRCODE='23514';
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
      IF NEW.indicator_type='COMPOSITE' THEN
        FOR reference_code IN SELECT DISTINCT m[1] FROM regexp_matches(NEW.formula,'\{([A-Za-z][A-Za-z0-9_]*)\}','g') m LOOP
            IF NOT EXISTS(SELECT 1 FROM dh_modeling_indicator_model r WHERE r.code=reference_code
                AND r.deleted=0 AND r.status='ACTIVE' AND r.id<>NEW.id AND r.data_domain_id=NEW.data_domain_id) THEN
                RAISE EXCEPTION '复合指标公式引用的指标 % 必须是同数据域的其他已启用指标', reference_code
                    USING ERRCODE='23514';
            END IF;
        END LOOP;
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
COMMENT ON FUNCTION modeling_current_source_guard() IS '在共享事务锁内保护原子、派生、复合指标及业务限定的当前来源绑定与指标间引用';

-- ========== 3. 菜单与权限 ==========
LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE
    metric_parent_id bigint;
    matches integer;
    page_id bigint;
    page_ids bigint[] := ARRAY[]::bigint[];
    item record;
    high_id bigint;
BEGIN
    SELECT count(*), min(id) INTO matches, metric_parent_id FROM system_menu
    WHERE deleted = 0 AND type = 1 AND component_name = 'ModelingIndicatorModelRoot'
      AND trim(both '/' from coalesce(path, '')) = 'indicator-model';
    IF matches <> 1 THEN
        RAISE EXCEPTION '0278 指标模型目录 ModelingIndicatorModelRoot 缺失或不唯一';
    END IF;
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                    (SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
    FOR item IN SELECT * FROM (VALUES
        ('复合指标', 'composite', 'modeling/indicator-model/composite/index', 'ModelingCompositeIndicator',
         'modeling:indicator-model:query', 'carbon:function-math', 40),
        ('指标查询', 'query', 'modeling/indicator-model/query/index', 'ModelingIndicatorQuery',
         'modeling:indicator-model:data-query', 'carbon:chart-multi-line', 50)
    ) t(label, path, component, component_name, permission, icon, sort) LOOP
        SELECT count(*), min(id) INTO matches, page_id FROM system_menu
        WHERE deleted = 0 AND trim(both '/' from coalesce(component, '')) = item.component;
        IF matches > 1 THEN
            RAISE EXCEPTION '0278 菜单组件身份重复：%', item.component;
        END IF;
        IF matches = 0 THEN
            IF EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0 AND parent_id = metric_parent_id
                                                   AND trim(both '/' from coalesce(path, '')) = item.path) THEN
                RAISE EXCEPTION '0278 菜单路径冲突：指标模型/% 已被其他菜单占用', item.path;
            END IF;
            high_id := high_id + 1;
            page_id := high_id;
            INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                                    status, visible, keep_alive, always_show, creator, updater, deleted)
            VALUES (page_id, item.label, item.permission, 2, item.sort, metric_parent_id, item.path, item.icon,
                    item.component, item.component_name, 0, true, true, true, 'migration:0278', 'migration:0278', 0);
        ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = page_id AND parent_id = metric_parent_id AND type = 2
                                                     AND trim(both '/' from coalesce(path, '')) = item.path
                                                     AND permission = item.permission) THEN
            RAISE EXCEPTION '0278 菜单组件身份冲突：% 已挂在其他位置或权限不一致', item.component;
        END IF;
        page_ids := array_append(page_ids, page_id);
    END LOOP;
    PERFORM setval('system_menu_seq', greatest((SELECT last_value FROM system_menu_seq), high_id));
    PERFORM setval('system_menu_id_seq', greatest((SELECT last_value FROM system_menu_id_seq), high_id));

    -- 与 0274 一致，仅授权超级管理员；沿用角色自身的脚手架租户归属。其他角色由管理员按需分配。
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
    SELECT high_id + row_number() OVER (ORDER BY r.id, r.tenant_id, m.id), r.id, m.id, r.tenant_id,
           'migration:0278', 'migration:0278', 0
      FROM system_role r JOIN system_menu m ON m.id = ANY(page_ids) AND m.deleted = 0
     WHERE r.code = 'super_admin' AND r.deleted = 0
       AND NOT EXISTS (SELECT 1 FROM system_role_menu old WHERE old.role_id = r.id AND old.tenant_id = r.tenant_id
                                                              AND old.menu_id = m.id AND old.deleted = 0);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', high_id);
END $menus$;
COMMIT;
