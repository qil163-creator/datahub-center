-- Migration: 0184 将我的资产作为数据资产下的独立菜单创建。
-- Purpose: 修复0183依赖固定数据集市父目录的问题，保留管理员已有菜单排列，仅在数据资产根目录末尾新增我的资产。
-- Depends on: 0182_governance_metadata-standard-p0.sql 已完成；0183_asset_my-assets.sql 可以已完成或因自定义菜单整体回滚，无需先重跑0183。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；已存在的我的资产只校验身份与数据资产归属，不补授权、不恢复人工停用或软删除、不移动位置。
-- Rollback: 单事务失败完整回滚；提交后按执行前权限表备份恢复新增菜单和授权，序列前进不回退，不修改资产或批准记录。
-- 执行前回滚此前失败事务，停止菜单、角色、套餐写入与编号分配并备份权限表；提交后刷新权限缓存并重新登录。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE INDEX IF NOT EXISTS idx_market_my_approved_assets
    ON dh_market_resource_apply (apply_user_id, create_time DESC, id DESC)
    WHERE deleted = 0 AND status IN (2, 5, 6);
COMMENT ON INDEX idx_market_my_approved_assets IS '我的资产按当前申请人查询已批准授权并按申请时间分页，包含暂停及到期历史';

LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;
DO $my_assets_root_menu$
DECLARE
    asset_root bigint;
    page_id bigint;
    ancestor_id bigint;
    ancestor_parent bigint;
    visited bigint[];
    matches integer;
    page_sort integer;
    source_ids bigint[];
    navigation_ids bigint[];
    menu_high bigint;
    role_high bigint;
    inserted integer;
BEGIN
    -- 根目录按稳定路由身份识别，显示名称、现有子项顺序和数据集市所在层级均不作为前置条件。
    SELECT count(*), min(id) INTO matches, asset_root FROM system_menu
    WHERE deleted = 0 AND parent_id = 0 AND trim(both '/' from coalesce(path, '')) = 'asset';
    IF matches <> 1 OR NOT EXISTS (
        SELECT 1 FROM system_menu WHERE id = asset_root AND type = 1 AND coalesce(permission, '') = ''
    ) THEN
        RAISE EXCEPTION '0184 数据资产根目录必须唯一、类型为目录且不得附带业务权限';
    END IF;

    SELECT count(*), min(id) INTO matches, page_id FROM system_menu
    WHERE trim(both '/' from coalesce(component, '')) = 'asset/market/my-assets/index';
    IF matches > 1 THEN RAISE EXCEPTION '0184 我的资产页面组件身份重复'; END IF;
    IF matches = 1 THEN
        IF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = page_id AND type = 2
            AND path = 'my-assets' AND permission = 'asset:market:my-assets:query' AND component_name = 'AssetMarketMyAssets') THEN
            RAISE EXCEPTION '0184 我的资产页面路由或权限身份冲突';
        END IF;
        -- 已有页面可以被管理员移动到数据资产内其他目录，重放不覆盖该决定。
        SELECT parent_id INTO ancestor_id FROM system_menu WHERE id = page_id;
        visited := ARRAY[page_id];
        WHILE ancestor_id <> 0 LOOP
            IF ancestor_id = ANY(visited) THEN RAISE EXCEPTION '0184 我的资产菜单祖先不能成环'; END IF;
            SELECT parent_id INTO ancestor_parent FROM system_menu
            WHERE id = ancestor_id AND type = 1 AND deleted = 0 AND coalesce(permission, '') = '';
            IF NOT FOUND THEN RAISE EXCEPTION '0184 我的资产祖先必须为有效且无业务权限的目录'; END IF;
            visited := array_append(visited, ancestor_id);
            IF ancestor_parent = 0 AND ancestor_id <> asset_root THEN
                RAISE EXCEPTION '0184 我的资产必须归属数据资产根目录';
            END IF;
            ancestor_id := ancestor_parent;
        END LOOP;
        IF NOT asset_root = ANY(visited) THEN RAISE EXCEPTION '0184 我的资产必须归属数据资产根目录'; END IF;
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0 AND
        ((parent_id = asset_root AND path = 'my-assets') OR permission = 'asset:market:my-assets:query')) THEN
        RAISE EXCEPTION '0184 我的资产路径或权限已被其他功能占用';
    END IF;
    IF EXISTS (SELECT 1 FROM system_tenant_package WHERE jsonb_typeof(menu_ids::jsonb) IS DISTINCT FROM 'array') THEN
        RAISE EXCEPTION '0184 套餐菜单编号必须为JSON数组';
    END IF;

    -- 只从当前有效的明确查询权限初始化，公共导航和资产管理权限均不作为授权来源。
    SELECT coalesce(array_agg(id), ARRAY[]::bigint[]) INTO source_ids FROM system_menu
    WHERE deleted = 0 AND status = 0 AND type IN (2, 3) AND permission = 'asset:market:query';
    SELECT coalesce(max(id), 0) INTO menu_high FROM system_menu;
    IF to_regclass('system_menu_seq') IS NOT NULL THEN
        SELECT greatest(menu_high, last_value) INTO menu_high FROM system_menu_seq;
    END IF;
    IF to_regclass('system_menu_id_seq') IS NOT NULL THEN
        SELECT greatest(menu_high, last_value) INTO menu_high FROM system_menu_id_seq;
    END IF;
    page_id := menu_high + 1;
    SELECT coalesce(max(sort), 0) + 1 INTO page_sort FROM system_menu WHERE parent_id = asset_root AND deleted = 0;
    INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, component,
                            component_name, status, visible, keep_alive, always_show, creator, updater, deleted)
    VALUES(page_id, '我的资产', 'asset:market:my-assets:query', 2, page_sort, asset_root, 'my-assets',
           'carbon:folder-shared', 'asset/market/my-assets/index', 'AssetMarketMyAssets',
           0, true, false, true, 'migration:0184', 'migration:0184', 0);
    navigation_ids := ARRAY[asset_root, page_id];

    SELECT coalesce(max(id), 0) INTO role_high FROM system_role_menu;
    IF to_regclass('system_role_menu_seq') IS NOT NULL THEN
        SELECT greatest(role_high, last_value) INTO role_high FROM system_role_menu_seq;
    END IF;
    INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
    SELECT role_high + row_number() OVER (ORDER BY pending.role_id, pending.tenant_id, pending.menu_id),
        pending.role_id, pending.menu_id, pending.tenant_id, 'migration:0184', 'migration:0184', 0
    FROM (
        SELECT DISTINCT source.role_id, source.tenant_id, navigation.id AS menu_id
        FROM system_role_menu source CROSS JOIN unnest(navigation_ids) navigation(id)
        WHERE source.deleted = 0 AND source.menu_id = ANY(source_ids)
          AND NOT EXISTS (SELECT 1 FROM system_role_menu existing WHERE existing.deleted = 0
            AND existing.role_id = source.role_id AND existing.tenant_id = source.tenant_id AND existing.menu_id = navigation.id)
    ) pending;
    GET DIAGNOSTICS inserted = ROW_COUNT;
    IF inserted > 0 AND to_regclass('system_role_menu_seq') IS NOT NULL THEN
        PERFORM setval('system_role_menu_seq', greatest((SELECT last_value FROM system_role_menu_seq), role_high + inserted));
    END IF;
    -- 脚手架套餐仅追加新页面及数据资产导航，保留原有JSON元素类型和顺序。
    WITH permitted AS (
        SELECT package.id, package.menu_ids::jsonb AS original
        FROM system_tenant_package package
        WHERE package.deleted = 0 AND EXISTS (
            SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value)
            WHERE entry.value = ANY(source_ids::text[])
        )
    ), expanded AS (
        SELECT permitted.id, permitted.original || to_jsonb(ARRAY(
            SELECT navigation.id FROM unnest(navigation_ids) WITH ORDINALITY navigation(id, position)
            WHERE NOT EXISTS (SELECT 1 FROM jsonb_array_elements_text(permitted.original) entry(value)
                              WHERE entry.value = navigation.id::text)
            ORDER BY navigation.position
        )) AS entries FROM permitted
    )
    UPDATE system_tenant_package package SET menu_ids = expanded.entries::text,
        updater = 'migration:0184', update_time = CURRENT_TIMESTAMP
    FROM expanded WHERE package.id = expanded.id AND package.menu_ids::jsonb IS DISTINCT FROM expanded.entries;
END $my_assets_root_menu$;

-- 已执行0183的数据库也要同步默认序列；只推进编号，不改变已有页面位置、状态或授权。
DO $my_assets_menu_sequences$
DECLARE
    high_watermark bigint;
BEGIN
    SELECT greatest(coalesce(max(id), 1), 1) INTO high_watermark FROM system_menu;
    IF to_regclass('system_menu_seq') IS NOT NULL THEN
        SELECT greatest(high_watermark, last_value) INTO high_watermark FROM system_menu_seq;
    END IF;
    IF to_regclass('system_menu_id_seq') IS NOT NULL THEN
        SELECT greatest(high_watermark, last_value) INTO high_watermark FROM system_menu_id_seq;
    END IF;
    IF to_regclass('system_menu_seq') IS NOT NULL THEN PERFORM setval('system_menu_seq', high_watermark); END IF;
    IF to_regclass('system_menu_id_seq') IS NOT NULL THEN PERFORM setval('system_menu_id_seq', high_watermark); END IF;
END $my_assets_menu_sequences$;
COMMIT;
