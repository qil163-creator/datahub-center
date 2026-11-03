-- Migration: 0155 删除数据探查功能
-- Purpose: 删除数据探查目录、四个页面及其按钮授权、套餐引用、旧调度配置和六张专属业务表及序列。
-- Depends on: 0154_datahub_remove-domain-scope-fields.sql 已执行，或已核验平台达到对应状态；系统菜单、角色菜单和租户套餐表已存在。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；按功能标识清理当前仍存在的菜单关联，专属表和序列使用存在性保护，其他数据不变。
-- Rollback: 失败整笔回滚；提交后探查任务、目录、版本、运行记录和报告不可恢复，必须从执行前备份恢复专属表、序列及菜单授权、套餐和旧调度配置，并恢复对应应用版本。
-- 不可逆变更：永久删除数据探查历史数据。执行前备份上述对象，停止全部旧探查执行实例，并停用 XXL-Job 的 exploreTaskJob、exploreOwnerReconcileJob。
-- 仅清理平台自身配置；外部 XXL-Job 管理库中的任务须在部署时停用，本脚本不连接或修改该管理库。
-- 删除表会获取排他锁，菜单与授权清理期间阻止并发写入；锁等待超过十秒或语句执行超过两分钟则回滚。
-- 不使用级联删除；存在其他功能的菜单子项或数据库依赖时拒绝执行，避免连带删除。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMPORARY TABLE dataforge_0155_retired_menus (id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO dataforge_0155_retired_menus (id)
SELECT menu.id FROM system_menu menu
WHERE permission LIKE 'dataforge:explore:%'
   OR trim(both '/' from coalesce(component, '')) LIKE 'dataforge/explore/%'
   OR component_name LIKE 'DataforgeExplore%'
   OR (type = 1 AND name = '数据探查'
       AND trim(both '/' from coalesce(path, '')) IN ('explore', 'probe'));

-- 补齐专属页面下未填写权限码的按钮；软删除菜单一并处理，不能保留孤立授权。
WITH RECURSIVE buttons (id) AS (
    SELECT id FROM dataforge_0155_retired_menus
    UNION
    SELECT child.id FROM system_menu child JOIN buttons parent ON child.parent_id = parent.id
    WHERE child.type = 3 AND coalesce(child.permission, '') = ''
)
INSERT INTO dataforge_0155_retired_menus (id)
SELECT id FROM buttons ON CONFLICT (id) DO NOTHING;

DO $menu_preflight$
BEGIN
    IF EXISTS (
        SELECT 1 FROM system_menu child JOIN dataforge_0155_retired_menus parent ON child.parent_id = parent.id
        WHERE NOT EXISTS (SELECT 1 FROM dataforge_0155_retired_menus retired WHERE retired.id = child.id)
    ) THEN
        RAISE EXCEPTION '0155 数据探查菜单下存在其他功能子项，禁止连带删除';
    END IF;
END $menu_preflight$;

-- 仅去除目标菜单编号，保留其余套餐条目的顺序和数字或字符串类型，空套餐仍为空数组。
-- 按元素拼接紧凑 JSON，避免重新序列化增加分隔空格而超过 menu_ids 的长度限制；字符串内部空格不变。
UPDATE system_tenant_package package
SET menu_ids = (
        SELECT '[' || coalesce(string_agg(entry.value::text, ',' ORDER BY entry.ordinality), '') || ']'
        FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY entry(value, ordinality)
        WHERE NOT EXISTS (
            SELECT 1 FROM dataforge_0155_retired_menus retired WHERE retired.id::text = entry.value #>> '{}'
        )
    ),
    updater = 'migration:0155', update_time = CURRENT_TIMESTAMP
WHERE EXISTS (
    SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value)
    JOIN dataforge_0155_retired_menus retired ON retired.id::text = entry.value
);

DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM dataforge_0155_retired_menus);
DELETE FROM system_menu WHERE id IN (SELECT id FROM dataforge_0155_retired_menus);

-- 清理平台中可能保留的旧调度配置；其他任务和历史审计日志不受影响。
DO $legacy_jobs$
BEGIN
    IF to_regclass('"datahub-center".infra_job') IS NOT NULL THEN
        DELETE FROM infra_job WHERE handler_name IN ('exploreTaskJob', 'exploreOwnerReconcileJob');
    END IF;
END $legacy_jobs$;

-- 先删除画像、报告与运行记录，再删除版本、任务和目录；外部依赖会使整笔事务失败。
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_explore_column;
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_explore_report;
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_explore_record;
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_explore_task_version;
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_explore_task;
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_explore_dir;

DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_explore_column_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_explore_report_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_explore_record_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_explore_task_version_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_explore_task_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_explore_dir_seq;

COMMIT;
