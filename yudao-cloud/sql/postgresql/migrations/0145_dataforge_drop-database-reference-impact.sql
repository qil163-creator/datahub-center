-- Migration: 0145 删除数据库引用影响及引用控制持久对象
-- Purpose: 下线数据库管理的引用影响与引用控制，删除专用屏障、变更、审计、领域回执、检测函数及菜单授权。
-- Depends on: 0144_modeling_drop-physical-tables.sql 已执行；系统菜单、角色菜单及租户套餐结构存在，平台已完成引用控制相关旧应用停机。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；对象使用 IF EXISTS，菜单和授权按仍存在的退役记录清理，重放保留其他菜单及套餐顺序。
-- Rollback: 失败整笔回滚；提交后引用控制记录、审计、回执和授权不可恢复，须从执行前的独立备份还原，应用回退也须恢复专用结构。
-- 执行前停止所有仍使用旧引用控制协议的 DataForge、Modeling、Governance、Asset、Supply 实例及任务，确认无活动事务，并备份专用表和菜单授权。
-- 删除表需要排他锁，锁等待超过十秒即失败；不使用级联删除，未知数据库依赖将阻止整笔提交。
-- 仅删除平台的引用影响辅助数据，不修改数据库记录、建模发布数据或外部业务数据库。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

-- 锁住权限关联，避免清理期间新增指向退役菜单的角色授权或租户套餐引用。
LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMPORARY TABLE dataforge_0145_retired_menus (id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO dataforge_0145_retired_menus (id)
SELECT id FROM system_menu
WHERE trim(both '/' from coalesce(component, '')) = 'dataforge/referencecontrol/index'
   OR component_name = 'DataForgeReferenceControl'
   OR (type = 2 AND trim(both '/' from coalesce(path, '')) IN ('reference-control', 'dataforge/reference-control'))
   OR permission IN ('dataforge:database:reference-control', 'dataforge:database:reference-resolve');

WITH RECURSIVE buttons (id) AS (
    SELECT id FROM dataforge_0145_retired_menus
    UNION
    SELECT child.id FROM system_menu child JOIN buttons parent ON child.parent_id = parent.id
    WHERE child.type = 3
)
INSERT INTO dataforge_0145_retired_menus (id)
SELECT id FROM buttons ON CONFLICT (id) DO NOTHING;

DO $menu_preflight$
BEGIN
    IF EXISTS (
        SELECT 1 FROM system_menu child JOIN dataforge_0145_retired_menus parent ON child.parent_id = parent.id
        WHERE NOT EXISTS (SELECT 1 FROM dataforge_0145_retired_menus retired WHERE retired.id = child.id)
    ) THEN
        RAISE EXCEPTION '0145 引用控制菜单下仍有其他页面或目录，禁止连带删除';
    END IF;
END $menu_preflight$;

-- 包括软删除菜单及关联；保留套餐中其他菜单的值类型和原顺序。
UPDATE system_tenant_package package
SET menu_ids = (
        SELECT coalesce(jsonb_agg(entry.value ORDER BY entry.ordinality), '[]'::jsonb)::text
        FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY entry(value, ordinality)
        WHERE NOT EXISTS (
            SELECT 1 FROM dataforge_0145_retired_menus retired WHERE retired.id::text = entry.value #>> '{}'
        )
    ),
    updater = 'migration:0145', update_time = CURRENT_TIMESTAMP
WHERE EXISTS (
    SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value)
    JOIN dataforge_0145_retired_menus retired ON retired.id::text = entry.value
);
DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM dataforge_0145_retired_menus);
DELETE FROM system_menu WHERE id IN (SELECT id FROM dataforge_0145_retired_menus);

-- 引用回执只服务于已退役的引用控制协议；约束、索引及自有序列随表删除。
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_reference_receipt;
DROP TABLE IF EXISTS "datahub-center".dh_modeling_reference_receipt;
DROP TABLE IF EXISTS "datahub-center".dh_governance_reference_receipt;
DROP TABLE IF EXISTS "datahub-center".dh_asset_reference_receipt;
DROP TABLE IF EXISTS "datahub-center".dh_supply_reference_receipt;
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_reference_mutation;
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_reference_audit;
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_reference_gate;
DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_reference_audit_id_seq;
DROP FUNCTION IF EXISTS "datahub-center".dh_reference_document_has_database(jsonb, bigint, integer);

-- 拒绝残留函数引用；未知自定义依赖需单独评估，避免保留运行时失效的函数。
DO $postflight$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_proc function JOIN pg_namespace namespace ON namespace.oid = function.pronamespace
        WHERE namespace.nspname = 'datahub-center'
          AND function.prokind IN ('f', 'p')
          AND function.prosrc ~ '\m(dh_dataforge_reference_(gate|mutation|audit|receipt)|dh_(modeling|governance|asset|supply)_reference_receipt|dh_reference_document_has_database)\M'
    ) THEN
        RAISE EXCEPTION '0145 仍有函数引用退役引用控制对象，整笔回滚；请核对自定义依赖';
    END IF;
END $postflight$;
COMMIT;
