-- Migration: 0161 数据推送统一任务入口。
-- Purpose: 隐藏独立推送配置页，将原配置按钮归入任务页，并保持原角色与租户套餐的同权限入口可用；不修改推送配置、任务或日志数据。
-- Depends on: 0160_dataforge_remove-data-exchange.sql 已执行，或已核验平台达到对应状态；系统菜单、角色菜单和租户套餐表已存在。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；按组件标识定位原菜单，仅补齐缺少的同权限任务入口，按钮归属和可见性重复更新不会产生新授权。
-- Rollback: 失败整笔回滚；提交后可从执行前备份恢复三个系统权限表中的受影响记录，并回退前端版本；推送业务数据未变更。
-- 菜单与授权调整期间阻止并发写入；锁等待超过十秒或单语句超过两分钟则回滚。部署后重新登录以刷新菜单及权限缓存。
-- 不启用被管理员禁用的菜单，不授予新增权限；遇到缺失任务入口、同父级重复入口或混挂其他功能子项时拒绝执行。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMPORARY TABLE dataforge_0161_push_entries (
    config_id bigint PRIMARY KEY,
    task_id bigint NOT NULL
) ON COMMIT DROP;

INSERT INTO dataforge_0161_push_entries (config_id, task_id)
SELECT config.id, task.id
FROM system_menu config
JOIN system_menu task ON task.parent_id = config.parent_id
WHERE config.deleted = 0 AND config.type = 2 AND task.deleted = 0 AND task.type = 2
  AND config.permission = 'dataforge:data-push:query'
  AND task.permission = config.permission
  AND trim(both '/' from coalesce(config.component, '')) = 'dataforge/datapush/config/index'
  AND trim(both '/' from coalesce(task.component, '')) = 'dataforge/datapush/task/index';

DO $entry_preflight$
BEGIN
    IF EXISTS (
        SELECT 1 FROM system_menu config
        WHERE config.deleted = 0 AND config.type = 2
          AND trim(both '/' from coalesce(config.component, '')) = 'dataforge/datapush/config/index'
          AND NOT EXISTS (SELECT 1 FROM dataforge_0161_push_entries entry WHERE entry.config_id = config.id)
    ) THEN
        RAISE EXCEPTION '0161 推送配置页缺少同父级、同查询权限的推送任务入口，请先核验菜单';
    END IF;
    IF EXISTS (SELECT 1 FROM dataforge_0161_push_entries GROUP BY task_id HAVING count(*) > 1) THEN
        RAISE EXCEPTION '0161 同父级存在重复推送配置入口，请先核验菜单';
    END IF;
    IF EXISTS (
        SELECT 1 FROM system_menu child
        JOIN dataforge_0161_push_entries entry ON child.parent_id = entry.config_id
        WHERE child.deleted = 0 AND (child.type <> 3 OR child.permission NOT LIKE 'dataforge:data-push:%')
    ) THEN
        RAISE EXCEPTION '0161 推送配置页混挂其他功能子项，禁止隐藏或移动其他业务权限';
    END IF;
END $entry_preflight$;

-- 原按钮编号和权限码保持不变，原角色授权与租户套餐引用仍有效。
UPDATE system_menu menu
SET parent_id = entry.task_id, updater = 'migration:0161', update_time = CURRENT_TIMESTAMP
FROM dataforge_0161_push_entries entry
WHERE menu.parent_id = entry.config_id AND menu.type = 3
  AND menu.permission LIKE 'dataforge:data-push:%';

-- 仅有配置页查询授权的角色改用同权限任务入口；已有任务授权时保留旧记录，避免重复授权。
WITH candidates AS (
    SELECT grant_row.id, entry.task_id,
           row_number() OVER (PARTITION BY grant_row.tenant_id, grant_row.role_id, entry.task_id ORDER BY grant_row.id) AS ordinal
    FROM system_role_menu grant_row
    JOIN dataforge_0161_push_entries entry ON grant_row.menu_id = entry.config_id
    WHERE grant_row.deleted = 0
      AND NOT EXISTS (
          SELECT 1 FROM system_role_menu current_grant
          WHERE current_grant.tenant_id = grant_row.tenant_id AND current_grant.role_id = grant_row.role_id
            AND current_grant.menu_id = entry.task_id AND current_grant.deleted = 0
      )
)
UPDATE system_role_menu grant_row
SET menu_id = candidate.task_id, updater = 'migration:0161', update_time = CURRENT_TIMESTAMP
FROM candidates candidate
WHERE grant_row.id = candidate.id AND candidate.ordinal = 1;

-- 套餐保留原编号、顺序和数字或字符串类型，只追加缺少的同查询权限任务入口。
WITH additions AS (
    SELECT package.id, string_agg(entry.task_id::text, ',' ORDER BY entry.task_id) AS task_ids
    FROM system_tenant_package package
    JOIN dataforge_0161_push_entries entry ON EXISTS (
        SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) item(value) WHERE item.value = entry.config_id::text
    )
    WHERE NOT EXISTS (
        SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) item(value) WHERE item.value = entry.task_id::text
    )
    GROUP BY package.id
)
UPDATE system_tenant_package package
SET menu_ids = (
        SELECT '[' || coalesce(string_agg(item.value::text, ',' ORDER BY item.ordinality) || ',', '') || addition.task_ids || ']'
        FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY item(value, ordinality)
    ),
    updater = 'migration:0161', update_time = CURRENT_TIMESTAMP
FROM additions addition
WHERE package.id = addition.id;

-- 使用可见性隐藏旧页面，保留启用状态及查询权限，兼容旧路由和存量配置。
UPDATE system_menu menu
SET visible = false, updater = 'migration:0161', update_time = CURRENT_TIMESTAMP
FROM dataforge_0161_push_entries entry
WHERE menu.id = entry.config_id AND menu.visible;

UPDATE system_menu menu
SET visible = true, updater = 'migration:0161', update_time = CURRENT_TIMESTAMP
FROM dataforge_0161_push_entries entry
WHERE menu.id = entry.task_id AND NOT menu.visible;

COMMIT;
