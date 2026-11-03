-- Migration: 0151 下线资产目录交付运营页面
-- Purpose: 删除目录交付页面、查询等退役按钮及其授权；将原人工恢复按钮迁入资产管理，保留按钮编号、角色授权、套餐引用和共享挂接账本。
-- Depends on: 0150_governance_master-data-workbench.sql 已执行，或对应完整平台状态；系统菜单、角色菜单和租户套餐表已存在。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；只清理仍存在的目标菜单关联，保留其他菜单、套餐顺序及编号值类型，重复执行不改变保留记录。
-- Rollback: 失败整笔回滚；提交后删除的菜单和授权不可恢复，须从执行前备份恢复系统菜单、角色菜单和租户套餐，应用回退也需恢复页面代码。
-- 执行前停止使用目录交付页面的旧应用实例并备份上述权限表；菜单锁等待超过十秒即失败。
-- 本迁移保留人工恢复权限及其原授权；不删除目录资产、挂接操作表、序列、索引、历史处置原因或未决回执。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

-- 避免清理期间新增指向退役菜单的角色授权或租户套餐引用。
LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

-- 恢复接口仍使用原权限；只移动已有按钮，不创建权限或授予新的访问范围。
DO $handle_parent$
DECLARE
    asset_menu bigint;
    matches integer;
BEGIN
    SELECT count(*), min(id) INTO matches, asset_menu FROM system_menu
    WHERE deleted = 0 AND status = 0 AND type = 2
      AND trim(both '/' from coalesce(component, '')) = 'asset/dataasset/index';
    IF matches <> 1 THEN
        RAISE EXCEPTION '0151 需要唯一有效的资产管理菜单，人工恢复权限不能迁移';
    END IF;
    SELECT count(*) INTO matches FROM system_menu WHERE permission = 'asset:catalog-delivery:handle';
    IF matches > 1 OR EXISTS (
        SELECT 1 FROM system_menu WHERE permission = 'asset:catalog-delivery:handle' AND type IS DISTINCT FROM 3
    ) THEN
        RAISE EXCEPTION '0151 人工恢复权限重复或不是按钮，禁止自动修改';
    END IF;
    UPDATE system_menu SET parent_id = asset_menu, updater = 'migration:0151', update_time = CURRENT_TIMESTAMP
    WHERE permission = 'asset:catalog-delivery:handle' AND parent_id IS DISTINCT FROM asset_menu;
END $handle_parent$;

CREATE TEMPORARY TABLE asset_0151_retained_menus (id bigint PRIMARY KEY) ON COMMIT DROP;
WITH RECURSIVE buttons (id) AS (
    SELECT id FROM system_menu WHERE permission = 'asset:catalog-delivery:handle'
    UNION
    SELECT child.id FROM system_menu child JOIN buttons parent ON child.parent_id = parent.id
    WHERE child.type = 3 AND coalesce(child.permission, '') = ''
)
INSERT INTO asset_0151_retained_menus (id) SELECT id FROM buttons;

-- 保留恢复按钮的专属子按钮；未知权限或页面子树须先明确归属，不能连带删除或扩大恢复权限。
DO $handle_subtree$
BEGIN
    IF EXISTS (
        SELECT 1 FROM system_menu child JOIN asset_0151_retained_menus parent ON child.parent_id = parent.id
        WHERE NOT EXISTS (SELECT 1 FROM asset_0151_retained_menus retained WHERE retained.id = child.id)
    ) THEN
        RAISE EXCEPTION '0151 人工恢复按钮下存在未知权限或页面子树，禁止自动清理';
    END IF;
END $handle_subtree$;

CREATE TEMPORARY TABLE asset_0151_retired_menus (id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO asset_0151_retired_menus (id)
SELECT menu.id FROM system_menu menu
WHERE (trim(both '/' from coalesce(component, '')) = 'asset/catalogdelivery/index'
   OR component_name = 'AssetCatalogDelivery'
   OR (type = 2 AND trim(both '/' from coalesce(path, '')) IN ('catalog-delivery', 'asset/catalog-delivery'))
   OR permission LIKE 'asset:catalog-delivery:%')
  AND NOT EXISTS (SELECT 1 FROM asset_0151_retained_menus retained WHERE retained.id = menu.id);

-- 包括软删除菜单及任意层级专属子按钮；不能连带删除其他页面或目录。
WITH RECURSIVE buttons (id) AS (
    SELECT id FROM asset_0151_retired_menus
    UNION
    SELECT child.id FROM system_menu child JOIN buttons parent ON child.parent_id = parent.id
    WHERE child.type = 3
      AND NOT EXISTS (SELECT 1 FROM asset_0151_retained_menus retained WHERE retained.id = child.id)
)
INSERT INTO asset_0151_retired_menus (id)
SELECT id FROM buttons ON CONFLICT (id) DO NOTHING;

DO $menu_preflight$
BEGIN
    IF EXISTS (
        SELECT 1 FROM system_menu child JOIN asset_0151_retired_menus parent ON child.parent_id = parent.id
        WHERE NOT EXISTS (SELECT 1 FROM asset_0151_retired_menus retired WHERE retired.id = child.id)
    ) THEN
        RAISE EXCEPTION '0151 目录交付菜单下仍有其他页面或目录，禁止连带删除';
    END IF;
END $menu_preflight$;

-- 仅移除目标编号；其他数字或字符串编号保持原值类型及顺序，空套餐保持空数组。
UPDATE system_tenant_package package
SET menu_ids = (
        SELECT coalesce(jsonb_agg(entry.value ORDER BY entry.ordinality), '[]'::jsonb)::text
        FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY entry(value, ordinality)
        WHERE NOT EXISTS (
            SELECT 1 FROM asset_0151_retired_menus retired WHERE retired.id::text = entry.value #>> '{}'
        )
    ),
    updater = 'migration:0151', update_time = CURRENT_TIMESTAMP
WHERE EXISTS (
    SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value)
    JOIN asset_0151_retired_menus retired ON retired.id::text = entry.value
);
DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM asset_0151_retired_menus);
DELETE FROM system_menu WHERE id IN (SELECT id FROM asset_0151_retired_menus);

COMMIT;
