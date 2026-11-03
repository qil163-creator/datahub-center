-- Migration: 0152 调用记录合并写操作台账页签
-- Purpose: 将写操作查询和核查权限移到调用记录，删除独立台账入口；原有业务权限、核查记录和操作账本保持不变。
-- Depends on: 0151_asset_catalog-delivery-removal.sql 已执行，或等价完整平台状态；调用记录菜单及开放写操作查询、核查权限已存在。
-- Target: 已有 PostgreSQL 平台库，固定结构为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；只在原入口仍绑定日志查询权限时迁移该授权，重放不会向仅有合并入口的角色或套餐授予日志查询权限。
-- Rollback: 失败整笔回滚，序列已取值可能留下间隔；提交后须从执行前备份恢复菜单、角色菜单及租户套餐，并回退前端页面。
-- 执行前备份上述权限表并停止旧应用实例；迁移后刷新系统权限缓存并重新登录。事务内菜单锁等待超过十秒即失败。
-- 本迁移只删除独立菜单和该菜单的角色关联，不删除写操作账本、核查证据或查询与核查权限，也不自动扩大业务权限。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

DO $merge_operation_tabs$
DECLARE
    log_menu system_menu%ROWTYPE;
    query_menu bigint;
    matches integer;
    query_was_on_page boolean;
    retired_ids bigint[];
    active_retired_ids bigint[];
    operation_permission_ids bigint[];
    role_grant record;
    package record;
    entries jsonb;
    next_entries jsonb;
    original_log_entry jsonb;
    operation_entry jsonb;
BEGIN
    SELECT count(*) INTO matches FROM system_menu
    WHERE deleted = 0 AND type = 2 AND trim(both '/' from coalesce(component, '')) = 'supply/apilog/index';
    IF matches <> 1 THEN RAISE EXCEPTION '0152 需要唯一有效的调用记录菜单'; END IF;
    SELECT * INTO log_menu FROM system_menu
    WHERE deleted = 0 AND type = 2 AND trim(both '/' from coalesce(component, '')) = 'supply/apilog/index';
    IF log_menu.status <> 0 OR log_menu.permission NOT IN ('', 'supply:api-log:query') THEN
        RAISE EXCEPTION '0152 调用记录菜单已停用或绑定了其他权限，禁止自动覆盖';
    END IF;
    query_was_on_page := log_menu.permission = 'supply:api-log:query';

    SELECT coalesce(array_agg(id), ARRAY[]::bigint[]),
           coalesce(array_agg(id) FILTER (WHERE deleted = 0 AND status = 0), ARRAY[]::bigint[])
    INTO retired_ids, active_retired_ids FROM system_menu
    WHERE trim(both '/' from coalesce(component, '')) = 'supply/operation/index'
       OR component_name = 'SupplyWriteOperation';
    IF cardinality(active_retired_ids) > 1 OR EXISTS (
        SELECT 1 FROM system_menu WHERE id = ANY(retired_ids)
        AND (type <> 2 OR deleted <> 0 OR status <> 0 OR parent_id <> log_menu.parent_id OR permission <> '')
    ) THEN RAISE EXCEPTION '0152 写操作台账菜单重复或归属冲突'; END IF;
    IF EXISTS (
        SELECT 1 FROM system_menu WHERE parent_id = ANY(retired_ids)
        AND (type <> 3 OR permission NOT IN ('supply:data-api:operation-query', 'supply:data-api:operation-resolve'))
    ) THEN RAISE EXCEPTION '0152 台账菜单下存在其他页面或权限，禁止连带删除'; END IF;

    SELECT coalesce(array_agg(id), ARRAY[]::bigint[]) INTO operation_permission_ids FROM system_menu
    WHERE deleted = 0 AND type = 3 AND permission IN ('supply:data-api:operation-query', 'supply:data-api:operation-resolve');
    IF cardinality(operation_permission_ids) <> 2 OR EXISTS (
        SELECT 1 FROM system_menu WHERE id = ANY(operation_permission_ids)
        AND parent_id <> log_menu.id AND NOT (parent_id = ANY(retired_ids))
    ) THEN RAISE EXCEPTION '0152 缺少台账查询、核查权限或权限归属冲突'; END IF;
    IF EXISTS (
        SELECT 1 FROM system_menu WHERE deleted = 0
        AND permission IN ('supply:data-api:operation-query', 'supply:data-api:operation-resolve')
        GROUP BY permission HAVING count(*) <> 1
    ) THEN RAISE EXCEPTION '0152 台账权限重复'; END IF;

    SELECT count(*), min(id) INTO matches, query_menu FROM system_menu
    WHERE id <> log_menu.id AND permission = 'supply:api-log:query';
    IF matches > 1 OR (matches = 1 AND NOT EXISTS (
        SELECT 1 FROM system_menu WHERE id = query_menu AND type = 3 AND deleted = 0 AND parent_id = log_menu.id
        AND (NOT query_was_on_page OR status = 0)
    )) THEN RAISE EXCEPTION '0152 日志查询子权限重复或归属冲突'; END IF;
    IF matches = 0 AND NOT query_was_on_page THEN
        RAISE EXCEPTION '0152 合并入口缺少日志查询子权限，禁止猜测授权';
    END IF;

    -- 同步数据库默认序列和应用使用的序列，避免新菜单及角色关联编号碰撞。
    PERFORM setval('system_menu_id_seq', greatest((SELECT last_value FROM system_menu_id_seq), coalesce((SELECT max(id) FROM system_menu), 1)), true);
    PERFORM setval('system_menu_seq', greatest((SELECT last_value FROM system_menu_seq), coalesce((SELECT max(id) FROM system_menu), 1)), true);
    PERFORM setval('system_role_menu_seq', greatest((SELECT last_value FROM system_role_menu_seq), coalesce((SELECT max(id) FROM system_role_menu), 1)), true);
    IF query_menu IS NULL THEN
        INSERT INTO system_menu (name, permission, type, sort, parent_id, creator, updater)
        VALUES ('查询调用日志', 'supply:api-log:query', 3, 0, log_menu.id, 'migration:0152', 'migration:0152')
        RETURNING id INTO query_menu;
    END IF;
    PERFORM setval('system_menu_seq', greatest((SELECT last_value FROM system_menu_seq), coalesce((SELECT max(id) FROM system_menu), 1)), true);

    -- 先继承原日志查询授权，再合并台账入口；两个页签的查询权限继续独立。
    IF query_was_on_page THEN
        FOR role_grant IN SELECT DISTINCT role_id, tenant_id FROM system_role_menu
            WHERE menu_id = log_menu.id AND deleted = 0
        LOOP
            IF NOT EXISTS (SELECT 1 FROM system_role_menu WHERE role_id = role_grant.role_id
                AND tenant_id = role_grant.tenant_id AND menu_id = query_menu AND deleted = 0) THEN
                INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id, creator, updater)
                VALUES (nextval('system_role_menu_seq'), role_grant.role_id, query_menu, role_grant.tenant_id, 'migration:0152', 'migration:0152');
            END IF;
        END LOOP;
    END IF;
    FOR role_grant IN SELECT DISTINCT role_id, tenant_id FROM system_role_menu
        WHERE deleted = 0 AND (menu_id = ANY(active_retired_ids) OR menu_id IN (
            SELECT id FROM system_menu WHERE id = ANY(operation_permission_ids) AND status = 0
        ))
    LOOP
        IF NOT EXISTS (SELECT 1 FROM system_role_menu WHERE role_id = role_grant.role_id
            AND tenant_id = role_grant.tenant_id AND menu_id = log_menu.id AND deleted = 0) THEN
            INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id, creator, updater)
            VALUES (nextval('system_role_menu_seq'), role_grant.role_id, log_menu.id, role_grant.tenant_id, 'migration:0152', 'migration:0152');
        END IF;
    END LOOP;

    -- 套餐仅替换入口并继承原日志权限；保留其他编号的顺序及数字、字符串类型。
    FOR package IN SELECT id, menu_ids FROM system_tenant_package LOOP
        entries := package.menu_ids::jsonb;
        original_log_entry := NULL;
        operation_entry := NULL;
        SELECT value INTO original_log_entry FROM jsonb_array_elements(entries) WITH ORDINALITY entry(value, ordinal)
        WHERE value #>> '{}' = log_menu.id::text ORDER BY ordinal LIMIT 1;
        SELECT value INTO operation_entry FROM jsonb_array_elements(entries) WITH ORDINALITY entry(value, ordinal)
        WHERE value #>> '{}' IN (SELECT id::text FROM unnest(active_retired_ids || operation_permission_ids) id)
        ORDER BY ordinal LIMIT 1;

        SELECT coalesce(jsonb_agg(value ORDER BY ordinal), '[]'::jsonb) INTO next_entries
        FROM jsonb_array_elements(entries) WITH ORDINALITY entry(value, ordinal)
        WHERE value #>> '{}' NOT IN (SELECT id::text FROM unnest(retired_ids) id);
        IF original_log_entry IS NULL AND operation_entry IS NOT NULL THEN
            next_entries := next_entries || jsonb_build_array(CASE WHEN jsonb_typeof(operation_entry) = 'string'
                THEN to_jsonb(log_menu.id::text) ELSE to_jsonb(log_menu.id) END);
        END IF;
        IF query_was_on_page AND original_log_entry IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM jsonb_array_elements(next_entries) entry(value) WHERE value #>> '{}' = query_menu::text
        ) THEN
            next_entries := next_entries || jsonb_build_array(CASE WHEN jsonb_typeof(original_log_entry) = 'string'
                THEN to_jsonb(query_menu::text) ELSE to_jsonb(query_menu) END);
        END IF;
        IF next_entries IS DISTINCT FROM entries THEN
            UPDATE system_tenant_package SET menu_ids = next_entries::text,
                updater = 'migration:0152', update_time = CURRENT_TIMESTAMP WHERE id = package.id;
        END IF;
    END LOOP;

    UPDATE system_menu SET permission = '', updater = 'migration:0152', update_time = CURRENT_TIMESTAMP
    WHERE id = log_menu.id AND permission <> '';
    UPDATE system_menu SET parent_id = log_menu.id, updater = 'migration:0152', update_time = CURRENT_TIMESTAMP
    WHERE parent_id = ANY(retired_ids) AND type = 3
      AND permission IN ('supply:data-api:operation-query', 'supply:data-api:operation-resolve');
    DELETE FROM system_role_menu WHERE menu_id = ANY(retired_ids);
    DELETE FROM system_menu WHERE id = ANY(retired_ids);
END $merge_operation_tabs$;

COMMIT;
