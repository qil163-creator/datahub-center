-- Migration: 0248 移除数据质量监控页面入口。
-- Purpose: 移除质量监控菜单及其授权，保留质量审计等其他功能所需的共享权限。
-- Depends on: 0247_governance_master-push-category.sql；系统菜单、角色菜单和租户套餐表存在。
-- Target: 已有 PostgreSQL 平台库；新库使用同步后的 datahub-center.sql。
-- Repeatable: 是；只处理尚未移除的目标菜单及其关联，不改变其他菜单授权。
-- Rollback: 从执行前备份恢复目标菜单、角色菜单授权和租户套餐，再恢复对应页面及接口代码。
-- 事务边界：单事务执行；菜单身份或子菜单异常时回滚。执行后需刷新权限缓存。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1 FROM system_menu
        WHERE deleted = 0
          AND trim(both '/' from coalesce(component, '')) IN
              ('governance/quality/overview/index', 'governance/quality-monitor/index')
          AND (type IS DISTINCT FROM 2 OR coalesce(permission, '') NOT IN
              ('', 'governance:quality-dashboard:query', 'governance:quality-audit-log:query'))
    ) THEN
        RAISE EXCEPTION '0248 质量监控菜单身份冲突，请核对菜单权限';
    END IF;
    IF EXISTS (
        SELECT 1 FROM system_menu parent JOIN system_menu child ON child.parent_id = parent.id
        WHERE parent.deleted = 0 AND child.deleted = 0 AND parent.type = 2
          AND trim(both '/' from coalesce(parent.component, '')) IN
              ('governance/quality/overview/index', 'governance/quality-monitor/index')
    ) THEN
        RAISE EXCEPTION '0248 质量监控存在子菜单，请核对后再执行';
    END IF;
    IF EXISTS (SELECT 1 FROM system_tenant_package
               WHERE deleted = 0 AND jsonb_typeof(menu_ids::jsonb) <> 'array') THEN
        RAISE EXCEPTION '0248 租户套餐菜单必须为数组';
    END IF;

    -- 旧页面可能携带其他功能仍使用的审计权限，保留原编号及授权并转换为按钮权限。
    UPDATE system_menu SET name = '质量审计查询', type = 3, path = '', component = '',
        component_name = '', visible = false, keep_alive = false, always_show = false,
        updater = 'migration:0248', update_time = CURRENT_TIMESTAMP
    WHERE deleted = 0 AND type = 2 AND permission = 'governance:quality-audit-log:query'
      AND trim(both '/' from coalesce(component, '')) IN
          ('governance/quality/overview/index', 'governance/quality-monitor/index');

    -- 套餐中仅移除将被删除的页面，保留共享权限及其他菜单。
    UPDATE system_tenant_package package SET menu_ids = (
        SELECT coalesce(jsonb_agg(item.value ORDER BY item.ordinality)
                        FILTER (WHERE old_page.id IS NULL), '[]'::jsonb)::text
        FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY item(value, ordinality)
        LEFT JOIN system_menu old_page ON old_page.id::text = item.value #>> '{}'
          AND old_page.deleted = 0 AND old_page.type = 2
          AND trim(both '/' from coalesce(old_page.component, '')) IN
              ('governance/quality/overview/index', 'governance/quality-monitor/index')
    ), updater = 'migration:0248', update_time = CURRENT_TIMESTAMP
    WHERE package.deleted = 0 AND EXISTS (
        SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) item(value)
        JOIN system_menu old_page ON old_page.id::text = item.value
        WHERE old_page.deleted = 0 AND old_page.type = 2
          AND trim(both '/' from coalesce(old_page.component, '')) IN
              ('governance/quality/overview/index', 'governance/quality-monitor/index')
    );

    UPDATE system_role_menu SET deleted = 1, updater = 'migration:0248', update_time = CURRENT_TIMESTAMP
    WHERE deleted = 0 AND menu_id IN (
        SELECT id FROM system_menu WHERE deleted = 0 AND type = 2
          AND trim(both '/' from coalesce(component, '')) IN
              ('governance/quality/overview/index', 'governance/quality-monitor/index')
    );
    UPDATE system_menu SET deleted = 1, updater = 'migration:0248', update_time = CURRENT_TIMESTAMP
    WHERE deleted = 0 AND type = 2
      AND trim(both '/' from coalesce(component, '')) IN
          ('governance/quality/overview/index', 'governance/quality-monitor/index');
END $migration$;

COMMIT;
