-- Migration: 0144 删除已退役的物理模型与版本表
-- Purpose: 完成物理模型下线，删除两张平台表、专属序列与守卫函数，清理残留菜单、角色授权及租户套餐菜单引用。
-- Depends on: 0143_modeling_drop-physical-menu.sql 已执行；0140 的物化发布表与 0142 的逻辑模型字段表已存在，应用已停止使用物理模型接口。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；对象使用 IF EXISTS，菜单清理只处理仍存在的退役记录，重放不修改保留记录。
-- Rollback: 失败整笔回滚；提交后两表数据、序列状态与授权不可恢复，须从执行前的独立备份恢复。执行前停止旧应用并备份两表及菜单、角色授权、租户套餐。
-- DROP TABLE 需要排他锁，超过十秒即失败；不使用级联删除，未知外键、视图或函数依赖会阻止整笔提交。
-- 仅删除平台的旧物理模型元数据，不操作任何已发布模型对应的外部数据源表。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

-- 明确核对新发布链路前置结构，避免在旧平台状态下直接删掉唯一的模型定义。
DO $preflight$
BEGIN
    IF to_regclass('"datahub-center".dh_modeling_logic_model') IS NULL
       OR to_regclass('"datahub-center".dh_modeling_logic_model_version') IS NULL
       OR to_regclass('"datahub-center".dh_modeling_logic_model_column') IS NULL
       OR to_regclass('"datahub-center".dh_modeling_model_materialized') IS NULL
       OR to_regclass('"datahub-center".dh_modeling_model_materialized_log') IS NULL THEN
        RAISE EXCEPTION '0144 前置结构缺失：须先完成逻辑模型字段与物化发布迁移';
    END IF;
END $preflight$;

-- 不按共享的 modeling:model:* 权限删除；这些权限仍属于逻辑模型。
CREATE TEMPORARY TABLE modeling_0144_retired_menus (id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO modeling_0144_retired_menus (id)
SELECT id FROM system_menu
WHERE trim(both '/' from coalesce(component, '')) = 'modeling/physicalmodel/index'
   OR component_name = 'ModelingPhysicalModel'
   OR (type = 2 AND trim(both '/' from coalesce(path, '')) IN ('physical-model', 'modeling/physical-model'))
   OR permission LIKE 'modeling:physical-model:%';

WITH RECURSIVE buttons (id) AS (
    SELECT id FROM modeling_0144_retired_menus
    UNION
    SELECT child.id FROM system_menu child JOIN buttons parent ON child.parent_id = parent.id
    WHERE child.type = 3
)
INSERT INTO modeling_0144_retired_menus (id)
SELECT id FROM buttons ON CONFLICT (id) DO NOTHING;

DO $menu_preflight$
BEGIN
    IF EXISTS (
        SELECT 1 FROM system_menu child JOIN modeling_0144_retired_menus parent ON child.parent_id = parent.id
        WHERE NOT EXISTS (SELECT 1 FROM modeling_0144_retired_menus retired WHERE retired.id = child.id)
    ) THEN
        RAISE EXCEPTION '0144 退役菜单仍挂有其他页面或目录，禁止连带删除';
    END IF;
END $menu_preflight$;

-- 同时处理软删除菜单；保留套餐中其他菜单的值类型和顺序。
UPDATE system_tenant_package package
SET menu_ids = (
        SELECT coalesce(jsonb_agg(entry.value ORDER BY entry.ordinality), '[]'::jsonb)::text
        FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY entry(value, ordinality)
        WHERE NOT EXISTS (
            SELECT 1 FROM modeling_0144_retired_menus retired WHERE retired.id::text = entry.value #>> '{}'
        )
    ),
    updater = 'migration:0144', update_time = CURRENT_TIMESTAMP
WHERE EXISTS (
    SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value)
    JOIN modeling_0144_retired_menus retired ON retired.id::text = entry.value
);
DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM modeling_0144_retired_menus);
DELETE FROM system_menu WHERE id IN (SELECT id FROM modeling_0144_retired_menus);

-- 先删版本表再删模型头；两表自有触发器、索引及约束随表删除。
DROP TABLE IF EXISTS "datahub-center".dh_modeling_physical_model_version;
DROP TABLE IF EXISTS "datahub-center".dh_modeling_physical_model;
DROP SEQUENCE IF EXISTS "datahub-center".dh_modeling_physical_model_version_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_modeling_physical_model_seq;
DROP FUNCTION IF EXISTS "datahub-center".dh_modeling_unified_version_guard();
DROP FUNCTION IF EXISTS "datahub-center".dh_modeling_unified_binding_guard();

-- 拒绝残留函数引用；未知自定义依赖必须单独评估，不能静默保留一个运行时报错的函数。
DO $postflight$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_proc function JOIN pg_namespace namespace ON namespace.oid = function.pronamespace
        WHERE namespace.nspname = 'datahub-center'
          AND function.prokind IN ('f', 'p')
          AND function.prosrc ~ '\mdh_modeling_physical_model(_version)?\M'
    ) THEN
        RAISE EXCEPTION '0144 仍有函数引用退役物理模型表，整笔回滚；请核对自定义依赖';
    END IF;
END $postflight$;
COMMIT;
