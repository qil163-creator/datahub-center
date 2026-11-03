-- Migration: 0177 退役独立资产管理入口。
-- Purpose: 由资产地图承接旧资产管理导航和查询授权，保留细粒度按钮编号、原授权及共享资产数据。
-- Depends on: 0176_asset_market-catalog-and-portal.sql 已执行；资产地图及其数据资产、数据集市祖先目录存在。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；按组件身份定位，旧入口删除后再次执行不新增授权、不改套餐、不移动已迁按钮。
-- Rollback: 表变更在单事务内失败回滚；提交后旧入口及其关联不能凭空恢复，须按执行前菜单、角色菜单及套餐备份恢复。
-- 执行前备份三个权限表并停用旧前端实例；不删除资产、注册、详情、申请、交付、类目或其他业务表。
-- 若新增角色授权需推进现有编号序列；序列不回退，异常时可能留下安全跳号，不能据此回退其他会话的编号。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;
DO $retire_asset_management$
DECLARE
    old_id bigint;
    map_id bigint;
    market_root bigint;
    asset_root bigint;
    matches integer;
    old_enabled boolean;
    target_id bigint;
    inserted integer;
    inserted_total integer := 0;
BEGIN
    -- 包括停用和软删除的同组件记录；身份不唯一时不能猜测哪一项属于本功能。
    SELECT count(*), min(id) INTO matches, map_id FROM system_menu
    WHERE trim(both '/' from coalesce(component, '')) = 'asset/market/map/index';
    IF matches <> 1 OR NOT EXISTS (
        SELECT 1 FROM system_menu WHERE id = map_id AND type = 2 AND deleted = 0 AND status = 0
          AND permission = 'asset:data-asset:query' AND trim(both '/' from coalesce(path, '')) = 'map'
    ) THEN
        RAISE EXCEPTION '0177 需要唯一有效且权限正确的资产地图菜单';
    END IF;
    SELECT parent_id INTO market_root FROM system_menu WHERE id = map_id;
    SELECT parent_id INTO asset_root FROM system_menu WHERE id = market_root;
    IF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = market_root AND type = 1 AND deleted = 0 AND status = 0
                   AND coalesce(permission, '') = '' AND trim(both '/' from coalesce(path, '')) = 'market')
       OR NOT EXISTS (SELECT 1 FROM system_menu WHERE id = asset_root AND type = 1 AND deleted = 0 AND status = 0
                   AND parent_id = 0 AND coalesce(permission, '') = '' AND trim(both '/' from coalesce(path, '')) = 'asset') THEN
        RAISE EXCEPTION '0177 资产地图的无权限导航祖先身份不正确';
    END IF;
    SELECT count(*), min(id) INTO matches, old_id FROM system_menu
    WHERE trim(both '/' from coalesce(component, '')) = 'asset/dataasset/index';
    IF matches > 1 THEN RAISE EXCEPTION '0177 旧资产管理菜单身份不唯一'; END IF;
    IF matches = 0 THEN RETURN; END IF;
    IF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = old_id AND type = 2
                   AND permission = 'asset:data-asset:query' AND parent_id = asset_root) THEN
        RAISE EXCEPTION '0177 旧资产管理菜单归属或权限不正确';
    END IF;
    SELECT deleted = 0 AND status = 0 INTO old_enabled FROM system_menu WHERE id = old_id;

    -- 所有层级的按钮都保留；出现独立页面或目录子树时中止，避免连带移走未知功能。
    IF EXISTS (
        WITH RECURSIVE descendants AS (
            SELECT id, type FROM system_menu WHERE parent_id = old_id
            UNION
            SELECT child.id, child.type FROM system_menu child JOIN descendants parent ON child.parent_id = parent.id
        ) SELECT 1 FROM descendants WHERE type IS DISTINCT FROM 3
    ) THEN
        RAISE EXCEPTION '0177 旧资产管理下存在未知页面或目录子树，禁止连带清理';
    END IF;
    IF EXISTS (SELECT 1 FROM system_tenant_package WHERE jsonb_typeof(menu_ids::jsonb) IS DISTINCT FROM 'array') THEN
        RAISE EXCEPTION '0177 套餐菜单编号必须为 JSON 数组';
    END IF;

    -- 仅承接原有效查询入口的角色授权；按钮授权和消费市场授权不能派生管理查询权限。
    IF old_enabled THEN
        FOREACH target_id IN ARRAY ARRAY[map_id, market_root, asset_root] LOOP
            INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
            SELECT (SELECT coalesce(max(id), 0) FROM system_role_menu)
                       + row_number() OVER (ORDER BY pending.role_id, pending.tenant_id),
                   pending.role_id, target_id, pending.tenant_id, 'migration:0177', 'migration:0177', 0
            FROM (
                SELECT DISTINCT source.role_id, source.tenant_id FROM system_role_menu source
                WHERE source.menu_id = old_id AND source.deleted = 0
                  AND NOT EXISTS (SELECT 1 FROM system_role_menu current_grant WHERE current_grant.deleted = 0
                      AND current_grant.role_id = source.role_id AND current_grant.tenant_id = source.tenant_id
                      AND current_grant.menu_id = target_id)
            ) pending;
            GET DIAGNOSTICS inserted = ROW_COUNT;
            inserted_total := inserted_total + inserted;
        END LOOP;
    END IF;

    -- 只移动旧入口的直接按钮，按钮后代保留原层级；停用及软删除按钮不恢复状态或授权。
    UPDATE system_menu SET parent_id = map_id, updater = 'migration:0177', update_time = CURRENT_TIMESTAMP
    WHERE parent_id = old_id AND type = 3;

    -- 只处理持有旧页编号的套餐。替换保留数字/字符串类型与其他元素顺序，已含地图时仅去旧编号。
    -- 补入的祖先经过上方空权限校验，仅承载导航；停用旧入口只清理引用，不能恢复管理权限。
    WITH candidates AS (
        SELECT package.id, package.menu_ids::jsonb AS original,
               EXISTS (SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) item(value)
                       WHERE item.value = map_id::text) AS has_map
        FROM system_tenant_package package
        WHERE EXISTS (SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) item(value)
                      WHERE item.value = old_id::text)
    ), replacements AS (
        SELECT candidate.id,
               (SELECT coalesce(jsonb_agg(
                   CASE WHEN entry.value #>> '{}' = old_id::text THEN
                       CASE WHEN jsonb_typeof(entry.value) = 'string' THEN to_jsonb(map_id::text) ELSE to_jsonb(map_id) END
                       ELSE entry.value END ORDER BY entry.ordinality), '[]'::jsonb)
                FROM jsonb_array_elements(candidate.original) WITH ORDINALITY entry(value, ordinality)
                WHERE entry.value #>> '{}' IS DISTINCT FROM old_id::text OR (old_enabled AND NOT candidate.has_map)) AS entries
        FROM candidates candidate
    ), navigation AS (
        SELECT replacement.id, replacement.entries
               || CASE WHEN old_enabled AND NOT EXISTS (
                       SELECT 1 FROM jsonb_array_elements_text(replacement.entries) item(value) WHERE item.value = asset_root::text
                   ) THEN to_jsonb(ARRAY[asset_root]) ELSE '[]'::jsonb END
               || CASE WHEN old_enabled AND NOT EXISTS (
                       SELECT 1 FROM jsonb_array_elements_text(replacement.entries) item(value) WHERE item.value = market_root::text
                   ) THEN to_jsonb(ARRAY[market_root]) ELSE '[]'::jsonb END AS entries
        FROM replacements replacement
    )
    UPDATE system_tenant_package package SET menu_ids = navigation.entries::text,
        updater = 'migration:0177', update_time = CURRENT_TIMESTAMP
    FROM navigation WHERE package.id = navigation.id AND package.menu_ids::jsonb IS DISTINCT FROM navigation.entries;

    DELETE FROM system_role_menu WHERE menu_id = old_id;
    DELETE FROM system_menu WHERE id = old_id;
    IF inserted_total > 0 AND to_regclass('system_role_menu_seq') IS NOT NULL THEN
        PERFORM setval('system_role_menu_seq', greatest(
            (SELECT last_value FROM system_role_menu_seq), (SELECT coalesce(max(id), 1) FROM system_role_menu)));
    END IF;
END $retire_asset_management$;
COMMIT;
