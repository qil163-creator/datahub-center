-- Migration: 0264 移除通用数据推送菜单及专用授权。
-- Purpose: 退役数据研发中的推送任务、配置和类目入口，清理专用角色授权、套餐引用及平台遗留调度配置。
-- Depends on: 0161_dataforge_single-push-task-entry.sql、0246_dataforge_push-categories.sql 所建立的菜单状态；平台库已升级至 0263。
-- Target: 已有 PostgreSQL 平台库；新库使用同步后的 datahub-center.sql。
-- Repeatable: 是；仅清理与通用数据推送身份匹配的记录，重复执行不再产生变更。
-- Rollback: 事务失败自动回滚；提交后恢复执行前备份中的目标菜单、角色授权、套餐及旧调度配置，并恢复应用版本。
-- 部署前在外部 XXL-Job 管理端停用 dataforge-server 的 dataPushSyncJob、dataPushAlertRelayJob、
-- dataPushAlertDispositionJob 和 dataPushAlertCleanJob，确认旧执行已结束。保留任务配置数据及运行历史。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMPORARY TABLE dataforge_0264_retired_menus (id bigint PRIMARY KEY) ON COMMIT DROP;

INSERT INTO dataforge_0264_retired_menus (id)
SELECT id FROM system_menu
WHERE permission LIKE 'dataforge:data-push:%'
   OR permission LIKE 'dataforge:push-category:%'
   OR trim(both '/' from coalesce(component, '')) IN (
       'dataforge/datapush/task/index', 'dataforge/datapush/config/index', 'dataforge/categorypush/index'
   )
   OR component_name IN ('DataforgeDataPushTask', 'DataforgeDataPushConfig', 'DataforgePushCategory');

-- 一并清理目标页面下无权限码的专用按钮。
INSERT INTO dataforge_0264_retired_menus (id)
SELECT child.id FROM system_menu child
JOIN dataforge_0264_retired_menus parent ON child.parent_id = parent.id
WHERE child.type = 3 AND child.permission = ''
ON CONFLICT (id) DO NOTHING;

DO $menu_preflight$
BEGIN
    IF EXISTS (
        SELECT 1 FROM system_menu child
        JOIN dataforge_0264_retired_menus parent ON child.parent_id = parent.id
        WHERE NOT EXISTS (SELECT 1 FROM dataforge_0264_retired_menus retired WHERE retired.id = child.id)
    ) THEN
        RAISE EXCEPTION '0264 通用数据推送菜单下存在其他功能子项，请核对后再执行';
    END IF;
END $menu_preflight$;

-- 保留套餐中其他条目的顺序及数字或字符串类型。
UPDATE system_tenant_package package
SET menu_ids = (
        SELECT '[' || coalesce(string_agg(item.value::text, ',' ORDER BY item.ordinality), '') || ']'
        FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY item(value, ordinality)
        WHERE NOT EXISTS (
            SELECT 1 FROM dataforge_0264_retired_menus retired WHERE retired.id::text = item.value #>> '{}'
        )
    ),
    updater = 'migration:0264', update_time = CURRENT_TIMESTAMP
WHERE EXISTS (
    SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) item(value)
    JOIN dataforge_0264_retired_menus retired ON retired.id::text = item.value
);

DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM dataforge_0264_retired_menus);
DELETE FROM system_menu WHERE id IN (SELECT id FROM dataforge_0264_retired_menus);

-- 仅移除平台中可能残留的专用调度配置，保留日志和外部调度库。
DO $legacy_jobs$
BEGIN
    IF to_regclass('"datahub-center".infra_job') IS NOT NULL THEN
        DELETE FROM infra_job WHERE handler_name IN (
            'dataPushSyncJob', 'dataPushAlertRelayJob', 'dataPushAlertDispositionJob', 'dataPushAlertCleanJob'
        );
    END IF;
END $legacy_jobs$;

COMMIT;
