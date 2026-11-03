-- Migration: 0249 移除元数据概览页面入口。
-- Purpose: 将承载共享查询权限的概览菜单转为不可见权限按钮，保留既有角色和套餐授权。
-- Depends on: 0248_governance_remove-quality-monitor-menu.sql；系统菜单、角色菜单和租户套餐表存在。
-- Target: 已有 PostgreSQL 平台库；新库使用同步后的 datahub-center.sql。
-- Repeatable: 是；只处理尚未转换或移除的目标页面，不改变其他菜单授权。
-- Rollback: 从执行前备份恢复目标菜单、角色授权和租户套餐，再恢复页面及接口代码。
-- 事务边界：单事务执行；菜单身份或子菜单异常时回滚。执行后需刷新权限缓存。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1 FROM system_menu WHERE deleted = 0
          AND trim(both '/' from coalesce(component, '')) = 'governance/metaoverview/index'
          AND (type IS DISTINCT FROM 2 OR coalesce(permission, '') NOT IN
              ('', 'governance:meta-object:query'))
    ) THEN
        RAISE EXCEPTION '0249 元数据概览菜单身份冲突，请核对菜单权限';
    END IF;
    IF EXISTS (
        SELECT 1 FROM system_menu parent JOIN system_menu child ON child.parent_id = parent.id
        WHERE parent.deleted = 0 AND child.deleted = 0 AND parent.type = 2
          AND trim(both '/' from coalesce(parent.component, '')) = 'governance/metaoverview/index'
    ) THEN
        RAISE EXCEPTION '0249 元数据概览存在子菜单，请核对后再执行';
    END IF;
    IF EXISTS (SELECT 1 FROM system_tenant_package
               WHERE deleted = 0 AND jsonb_typeof(menu_ids::jsonb) <> 'array') THEN
        RAISE EXCEPTION '0249 租户套餐菜单必须为数组';
    END IF;

    -- 元数据树与其他页面仍使用此权限，保留原菜单编号及角色、套餐授权。
    UPDATE system_menu SET name = '元数据查询', type = 3, path = '', component = '',
        component_name = '', visible = false, keep_alive = false, always_show = false,
        updater = 'migration:0249', update_time = CURRENT_TIMESTAMP
    WHERE deleted = 0 AND type = 2 AND permission = 'governance:meta-object:query'
      AND trim(both '/' from coalesce(component, '')) = 'governance/metaoverview/index';

    -- 没有权限的兼容页面仅移除入口及其关联，不触及共享权限按钮。
    UPDATE system_tenant_package package SET menu_ids = (
        SELECT coalesce(jsonb_agg(item.value ORDER BY item.ordinality)
                        FILTER (WHERE old_page.id IS NULL), '[]'::jsonb)::text
        FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY item(value, ordinality)
        LEFT JOIN system_menu old_page ON old_page.id::text = item.value #>> '{}'
          AND old_page.deleted = 0 AND old_page.type = 2
          AND trim(both '/' from coalesce(old_page.component, '')) = 'governance/metaoverview/index'
    ), updater = 'migration:0249', update_time = CURRENT_TIMESTAMP
    WHERE package.deleted = 0 AND EXISTS (
        SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) item(value)
        JOIN system_menu old_page ON old_page.id::text = item.value
        WHERE old_page.deleted = 0 AND old_page.type = 2
          AND trim(both '/' from coalesce(old_page.component, '')) = 'governance/metaoverview/index'
    );
    UPDATE system_role_menu SET deleted = 1, updater = 'migration:0249', update_time = CURRENT_TIMESTAMP
    WHERE deleted = 0 AND menu_id IN (
        SELECT id FROM system_menu WHERE deleted = 0 AND type = 2
          AND trim(both '/' from coalesce(component, '')) = 'governance/metaoverview/index'
    );
    UPDATE system_menu SET deleted = 1, updater = 'migration:0249', update_time = CURRENT_TIMESTAMP
    WHERE deleted = 0 AND type = 2
      AND trim(both '/' from coalesce(component, '')) = 'governance/metaoverview/index';
END $migration$;

COMMIT;
