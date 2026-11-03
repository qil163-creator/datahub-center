-- Migration: 0183 我的资产消费者页面及当前用户授权分页索引。
-- Purpose: 新增独立的我的资产查询权限与导航；仅从现有集市查询授权初始化，不授予管理、审批或供给管理权限。
-- Depends on: 0182_governance_metadata-standard-p0.sql 已执行，0176至0179的数据资产与数据集市导航结构已建立。
-- Target: 已有 PostgreSQL 平台库；全新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；索引按名称保护，页面仅首次创建时初始化角色和套餐授权，重放不恢复人工撤销或停用。
-- Rollback: 事务失败完整回滚；提交后可停用新页面并按执行前备份恢复角色与套餐，不自动删除授权历史；序列前进不回退。
-- 执行前停止 system 菜单、角色、套餐写入与编号分配，备份相应权限表；提交后刷新相关权限缓存并重新登录。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE INDEX IF NOT EXISTS idx_market_my_approved_assets
    ON dh_market_resource_apply (apply_user_id, create_time DESC, id DESC)
    WHERE deleted = 0 AND status IN (2, 5, 6);
COMMENT ON INDEX idx_market_my_approved_assets IS '我的资产按当前申请人查询已批准授权并按申请时间分页，包含暂停及到期历史';

LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;
DO $my_assets_menu$
DECLARE
    asset_root bigint;
    market_root bigint;
    page_id bigint;
    matches integer;
    source_ids bigint[];
    navigation_ids bigint[];
    menu_high bigint;
    role_high bigint;
    inserted integer;
BEGIN
    SELECT count(*), min(id) INTO matches, asset_root FROM system_menu
    WHERE deleted = 0 AND type = 1 AND parent_id = 0 AND permission = '' AND trim(both '/' from path) = 'asset';
    IF matches <> 1 THEN RAISE EXCEPTION '0183 数据资产根目录必须唯一且不得附带业务权限'; END IF;
    SELECT count(*), min(id) INTO matches, market_root FROM system_menu
    WHERE deleted = 0 AND type = 1 AND parent_id = asset_root AND permission = '' AND trim(both '/' from path) = 'market';
    IF matches <> 1 THEN RAISE EXCEPTION '0183 数据集市目录必须唯一、归属数据资产且不得附带业务权限'; END IF;
    IF NOT EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0 AND type = 2 AND parent_id = market_root
        AND trim(both '/' from component) = 'asset/market/index' AND permission = 'asset:market:query') THEN
        RAISE EXCEPTION '0183 缺少正式数据集市页面，禁止猜测权限来源';
    END IF;

    SELECT count(*), min(id) INTO matches, page_id FROM system_menu
    WHERE trim(both '/' from coalesce(component, '')) = 'asset/market/my-assets/index';
    IF matches > 1 THEN RAISE EXCEPTION '0183 我的资产页面组件身份重复'; END IF;
    IF matches = 1 THEN
        IF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = page_id AND type = 2 AND parent_id = market_root
            AND path = 'my-assets' AND permission = 'asset:market:my-assets:query' AND component_name = 'AssetMarketMyAssets') THEN
            RAISE EXCEPTION '0183 我的资产页面归属或权限身份冲突';
        END IF;
        -- 已创建的页面不补授权、不恢复停用与删除，保留管理员后续调整。
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0 AND
        ((parent_id = market_root AND path = 'my-assets') OR permission = 'asset:market:my-assets:query')) THEN
        RAISE EXCEPTION '0183 我的资产路径或权限已被其他功能占用';
    END IF;
    IF EXISTS (SELECT 1 FROM system_tenant_package WHERE jsonb_typeof(menu_ids::jsonb) IS DISTINCT FROM 'array') THEN
        RAISE EXCEPTION '0183 套餐菜单编号必须为JSON数组';
    END IF;

    SELECT coalesce(array_agg(id), ARRAY[]::bigint[]) INTO source_ids FROM system_menu
    WHERE deleted = 0 AND status = 0 AND permission = 'asset:market:query';
    SELECT coalesce(max(id), 0) INTO menu_high FROM system_menu;
    IF to_regclass('system_menu_seq') IS NOT NULL THEN
        SELECT greatest(menu_high, last_value) INTO menu_high FROM system_menu_seq;
    END IF;
    page_id := menu_high + 1;
    INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, component,
                            component_name, status, visible, keep_alive, always_show, creator, updater, deleted)
    VALUES(page_id, '我的资产', 'asset:market:my-assets:query', 2, 3, market_root, 'my-assets',
           'carbon:folder-shared', 'asset/market/my-assets/index', 'AssetMarketMyAssets',
           0, true, false, true, 'migration:0183', 'migration:0183', 0);
    navigation_ids := ARRAY[asset_root, market_root, page_id];

    SELECT coalesce(max(id), 0) INTO role_high FROM system_role_menu;
    IF to_regclass('system_role_menu_seq') IS NOT NULL THEN
        SELECT greatest(role_high, last_value) INTO role_high FROM system_role_menu_seq;
    END IF;
    INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
    SELECT role_high + row_number() OVER (ORDER BY pending.role_id, pending.tenant_id, pending.menu_id),
        pending.role_id, pending.menu_id, pending.tenant_id, 'migration:0183', 'migration:0183', 0
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
    IF to_regclass('system_menu_seq') IS NOT NULL THEN
        PERFORM setval('system_menu_seq', greatest((SELECT last_value FROM system_menu_seq), page_id));
    END IF;

    -- 保留脚手架套餐能力，仅为已有集市查询许可的套餐追加新页面和无权限祖先。
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
        updater = 'migration:0183', update_time = CURRENT_TIMESTAMP
    FROM expanded WHERE package.id = expanded.id AND package.menu_ids::jsonb IS DISTINCT FROM expanded.entries;
END $my_assets_menu$;
COMMIT;
