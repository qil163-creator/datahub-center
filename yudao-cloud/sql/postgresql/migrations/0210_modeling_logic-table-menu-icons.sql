-- Migration: 0210 补齐逻辑模型下五个逻辑表菜单图标。
-- Purpose: 为贴源、维度、事实、汇总及 ADS 应用表菜单补齐图标，保留已配置的自定义图标。
-- Depends on: 0208_modeling_logic-table-qdata.sql 已完整执行；按全局顺序在 0209_dataforge_connection-icons-png.sql 之后执行。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；仅修改空白图标，重复执行不改写图标及审计时间。
-- Rollback: 本事务失败时全部回滚；提交后如需撤销，按执行前快照恢复这五个菜单的 icon、updater 和 update_time。
-- 事务边界：只更新菜单展示图标，不新增菜单、不变更权限或角色授权；短事务并限制锁等待。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $icons$
DECLARE
    page record;
    menu_matches integer;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 6216 AND deleted = 0) THEN
        RAISE EXCEPTION '缺少逻辑模型目录菜单 6216，请先确认 0208 迁移已完整执行';
    END IF;
    FOR page IN SELECT * FROM (VALUES
            ('source-table', 'modeling/logictable/source/index', 'lucide:database'),
            ('dimension-table', 'modeling/logictable/dimension/index', 'lucide:boxes'),
            ('fact-table', 'modeling/logictable/fact/index', 'lucide:table-properties'),
            ('summary-table', 'modeling/logictable/summary/index', 'lucide:chart-column'),
            ('application-table', 'modeling/logictable/application/index', 'lucide:layout-dashboard')
        ) AS p(path, component, icon)
    LOOP
        SELECT count(*) INTO menu_matches FROM system_menu
        WHERE parent_id = 6216 AND type = 2 AND deleted = 0
          AND path = page.path AND component = page.component;
        IF menu_matches <> 1 THEN
            RAISE EXCEPTION '逻辑模型子菜单 % 匹配到 % 条，预期恰好一条；请先确认 0208 迁移已完整执行',
                page.path, menu_matches;
        END IF;
        UPDATE system_menu AS menu
        SET icon = page.icon, updater = 'migration:0210', update_time = CURRENT_TIMESTAMP
        WHERE menu.parent_id = 6216 AND menu.type = 2 AND menu.deleted = 0
          AND menu.path = page.path AND menu.component = page.component
          AND nullif(btrim(menu.icon), '') IS NULL;
    END LOOP;
END $icons$;
COMMIT;
