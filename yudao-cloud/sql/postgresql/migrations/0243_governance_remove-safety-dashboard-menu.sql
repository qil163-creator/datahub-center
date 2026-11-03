-- Migration: 0243 移除安全大盘菜单并迁移治理总览安全统计权限。
-- Purpose: 保留原安全统计权限的角色授权，删除已移除页面入口；不修改其他安全功能及业务数据。
-- Depends on: 0242_governance_term-category-link.sql；菜单、角色授权、租户套餐表及对应序列存在。
-- Target: 已有 PostgreSQL 数据库；新库使用同步后的 datahub-center.sql。
-- Repeatable: 是；已存在的权限和授权不会重复添加，已删除菜单不再处理。
-- Rollback: 从执行前备份恢复菜单、角色授权、租户套餐，并恢复原页面及接口代码。
-- 事务边界：单事务执行；菜单身份异常时全部回滚。
-- 执行前备份菜单、角色菜单授权和租户套餐；提交后刷新权限缓存并重新登录。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

DO $migration$
DECLARE
    overview_id bigint;
    new_permission_id bigint;
    menu_high bigint;
    grant_high bigint;
BEGIN
    IF (SELECT count(*) FROM system_menu WHERE deleted = 0 AND type = 2
        AND trim(both '/' from coalesce(component, '')) = 'governance/overview/index') <> 1 THEN
        RAISE EXCEPTION '0243 未找到唯一的治理总览菜单';
    END IF;
    SELECT id INTO overview_id FROM system_menu WHERE deleted = 0 AND type = 2
        AND trim(both '/' from coalesce(component, '')) = 'governance/overview/index';

    IF EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0
        AND trim(both '/' from coalesce(component, '')) = 'governance/security/dashboard/index'
        AND type IS DISTINCT FROM 2)
       OR EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0
        AND permission = 'governance:safety-dashboard:query'
        AND (type NOT IN (2, 3) OR (type = 2 AND
          trim(both '/' from coalesce(component, '')) <> 'governance/security/dashboard/index')))
       OR EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0
        AND permission = 'governance:dashboard:safety-query'
        AND (type IS DISTINCT FROM 3 OR parent_id IS DISTINCT FROM overview_id)) THEN
        RAISE EXCEPTION '0243 安全大盘或治理总览权限菜单身份冲突';
    END IF;
    IF EXISTS (
        SELECT 1 FROM system_menu parent JOIN system_menu child
          ON child.parent_id = parent.id AND child.deleted = 0
        WHERE parent.deleted = 0 AND parent.type = 2
          AND trim(both '/' from coalesce(parent.component, '')) = 'governance/security/dashboard/index'
          AND (child.type IS DISTINCT FROM 3
               OR child.permission IS DISTINCT FROM 'governance:safety-dashboard:query')
    ) THEN
        RAISE EXCEPTION '0243 安全大盘存在其他子菜单，请核对后再执行';
    END IF;
    IF EXISTS (SELECT 1 FROM system_tenant_package
               WHERE deleted = 0 AND jsonb_typeof(menu_ids::jsonb) <> 'array') THEN
        RAISE EXCEPTION '0243 租户套餐菜单必须为数组';
    END IF;

    -- 沿用旧按钮身份与授权，使每个角色继续拥有原来的统计查询能力。
    UPDATE system_menu SET name = '治理总览安全统计查询',
        permission = 'governance:dashboard:safety-query', parent_id = overview_id,
        updater = 'migration:0243', update_time = CURRENT_TIMESTAMP
    WHERE deleted = 0 AND type = 3 AND permission = 'governance:safety-dashboard:query';

    SELECT id INTO new_permission_id FROM system_menu WHERE deleted = 0
      AND permission = 'governance:dashboard:safety-query' ORDER BY id LIMIT 1;
    IF new_permission_id IS NULL THEN
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                        (SELECT last_value FROM system_menu_id_seq)) + 1 INTO new_permission_id
          FROM system_menu;
        INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, status,
                                 visible, keep_alive, always_show, creator, updater, deleted)
        VALUES (new_permission_id, '治理总览安全统计查询', 'governance:dashboard:safety-query',
                3, 1, overview_id, '', 0, true, true, true, 'migration:0243', 'migration:0243', 0);
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                        (SELECT last_value FROM system_menu_id_seq)) INTO menu_high FROM system_menu;
        PERFORM setval('system_menu_seq', menu_high, true);
        PERFORM setval('system_menu_id_seq', menu_high, true);
    END IF;

    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq))
      INTO grant_high FROM system_role_menu;
    INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id, creator, updater, deleted)
    SELECT grant_high + row_number() OVER (ORDER BY source.role_id, source.tenant_id),
           source.role_id, new_permission_id, source.tenant_id, 'migration:0243', 'migration:0243', 0
    FROM (
        SELECT DISTINCT grant_row.role_id, grant_row.tenant_id
        FROM system_role_menu grant_row JOIN system_menu old ON old.id = grant_row.menu_id
        WHERE grant_row.deleted = 0 AND old.deleted = 0 AND old.type = 2
          AND old.permission = 'governance:safety-dashboard:query'
          AND trim(both '/' from coalesce(old.component, '')) = 'governance/security/dashboard/index'
    ) source
    WHERE NOT EXISTS (SELECT 1 FROM system_role_menu existing
                      WHERE existing.deleted = 0 AND existing.role_id = source.role_id
                        AND existing.tenant_id = source.tenant_id AND existing.menu_id = new_permission_id);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq))
      INTO grant_high FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', grant_high, true);

    -- 页面本身携带旧查询权限时，套餐需保留对应的新按钮；其他套餐仅移除旧页面。
    UPDATE system_tenant_package package
    SET menu_ids = (
        SELECT (coalesce(jsonb_agg(item.value ORDER BY item.ordinality)
                         FILTER (WHERE old_page.id IS NULL), '[]'::jsonb)
                || CASE WHEN bool_or(old_page.permission = 'governance:safety-dashboard:query')
                             AND NOT bool_or(item.value #>> '{}' = new_permission_id::text)
                        THEN jsonb_build_array(new_permission_id) ELSE '[]'::jsonb END)::text
        FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY item(value, ordinality)
        LEFT JOIN system_menu old_page ON old_page.id::text = item.value #>> '{}'
          AND old_page.deleted = 0 AND old_page.type = 2
          AND trim(both '/' from coalesce(old_page.component, '')) = 'governance/security/dashboard/index'
    ), updater = 'migration:0243', update_time = CURRENT_TIMESTAMP
    WHERE package.deleted = 0 AND EXISTS (
        SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) item(value)
        JOIN system_menu old_page ON old_page.id::text = item.value
        WHERE old_page.deleted = 0 AND old_page.type = 2
          AND trim(both '/' from coalesce(old_page.component, '')) = 'governance/security/dashboard/index'
    );

    UPDATE system_role_menu SET deleted = 1, updater = 'migration:0243', update_time = CURRENT_TIMESTAMP
    WHERE deleted = 0 AND menu_id IN (
        SELECT id FROM system_menu WHERE deleted = 0
          AND type = 2 AND trim(both '/' from coalesce(component, '')) = 'governance/security/dashboard/index'
    );
    UPDATE system_menu SET deleted = 1, updater = 'migration:0243', update_time = CURRENT_TIMESTAMP
    WHERE deleted = 0
      AND type = 2 AND trim(both '/' from coalesce(component, '')) = 'governance/security/dashboard/index';
END $migration$;

COMMIT;
