-- Migration: 0179 恢复数据集市和资产地图的角色菜单配置。
-- Purpose: 以当前查询按钮授权与套餐许可收敛页面授权，撤销公共导航拆分，不扩大查询或任何写权限。
-- Depends on: 0177_asset_retire-standalone-management-menu.sql 已执行；兼容已执行或未执行 0178 的页面状态，不要求补跑 0178。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；仅页面权限为空时补偿，恢复原查询权限后重放不新增授权、不恢复撤销、不再次调整套餐。
-- Rollback: 单事务失败完整回滚；提交后不自动恢复已收敛的导航授权，须按执行前菜单、角色菜单和套餐备份恢复。序列前进不回退。
-- 执行前停止全部旧版 system 实例、权限写入与编号分配，备份三个权限表；按有序迁移执行，禁止重放 0178 来回退本脚本。
-- 与移除公共导航白名单的新版本同步发布；可能移除无有效查询授权的旧导航关联，不修改资产数据或写权限按钮。
-- 原始 SQL 不触发 Spring 缓存失效；提交后按部署真实 Redis 前缀及租户后缀清除 permission_menu_ids 中两个查询权限的缓存。
-- 同时清除页面、查询来源及所补祖先涉及的 menu_role_ids 缓存键，再重启相关服务清理本地权限缓存。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

DO $restore_role_navigation$
DECLARE
    target record;
    page_id bigint;
    ancestor_id bigint;
    matches integer;
    visited bigint[];
    active_query_ids bigint[];
    retired_query_ids bigint[];
    role_high_watermark bigint;
    inserted integer;
    inserted_total integer := 0;
BEGIN
    SELECT coalesce(max(id), 0) INTO role_high_watermark FROM system_role_menu;
    IF to_regclass('system_role_menu_seq') IS NOT NULL THEN
        SELECT greatest(role_high_watermark, last_value) INTO role_high_watermark FROM system_role_menu_seq;
    END IF;
    FOR target IN SELECT * FROM (VALUES
        ('asset/market/index', 'AssetMarketMart', 'asset:market:query', '集市接口查询'),
        ('asset/market/map/index', 'AssetMarketMap', 'asset:data-asset:query', '资产管理查询')
    ) AS targets(component, component_name, permission, name)
    LOOP
        SELECT count(*), min(id) INTO matches, page_id FROM system_menu
        WHERE trim(both '/' from coalesce(component, '')) = target.component;
        IF matches <> 1 OR NOT EXISTS (
            SELECT 1 FROM system_menu WHERE id = page_id AND type = 2 AND deleted = 0
                AND component_name = target.component_name AND permission IN ('', target.permission)
        ) THEN
            RAISE EXCEPTION '0179 页面身份或查询权限不正确：%', target.component;
        END IF;
        -- 未执行公共导航拆分，或已经补偿的页面不做权限写入，避免重放恢复人工撤销。
        IF EXISTS (SELECT 1 FROM system_menu WHERE id = page_id AND permission = target.permission) THEN
            CONTINUE;
        END IF;
        SELECT parent_id INTO ancestor_id FROM system_menu WHERE id = page_id;
        visited := ARRAY[page_id];
        WHILE ancestor_id <> 0 LOOP
            IF ancestor_id = ANY(visited) OR NOT EXISTS (
                SELECT 1 FROM system_menu WHERE id = ancestor_id AND type = 1 AND deleted = 0 AND permission = ''
            ) THEN
                RAISE EXCEPTION '0179 页面祖先必须为无权限目录且不能成环：%', target.component;
            END IF;
            visited := array_append(visited, ancestor_id);
            SELECT parent_id INTO ancestor_id FROM system_menu WHERE id = ancestor_id;
        END LOOP;
        IF EXISTS (SELECT 1 FROM system_tenant_package WHERE jsonb_typeof(menu_ids::jsonb) IS DISTINCT FROM 'array') THEN
            RAISE EXCEPTION '0179 套餐菜单编号必须为 JSON 数组';
        END IF;

        SELECT coalesce(array_agg(id), ARRAY[]::bigint[]) INTO active_query_ids FROM system_menu
        WHERE permission = target.permission AND status = 0 AND deleted = 0;
        -- 只有来源、审计时间与初始形状均未变化且没有后代的迁移按钮可退役，人工节点保持原样。
        SELECT coalesce(array_agg(menu.id), ARRAY[]::bigint[]) INTO retired_query_ids FROM system_menu menu
        JOIN system_menu page ON page.id = page_id
        WHERE menu.parent_id = page_id AND menu.type = 3 AND menu.permission = target.permission AND menu.deleted = 0
            AND menu.creator = 'migration:0178' AND menu.updater = 'migration:0178'
            AND menu.create_time = menu.update_time AND menu.name = target.name AND menu.sort = 0
            AND menu.status = page.status AND menu.visible = false AND menu.keep_alive = true AND menu.always_show = true
            AND menu.path = '' AND menu.icon = '#' AND menu.component IS NULL AND menu.component_name IS NULL
            AND NOT EXISTS (SELECT 1 FROM system_menu child WHERE child.parent_id = menu.id);

        -- 旧公共页面只承载导航，必须先删除没有当前查询授权的关联，不能直接恢复其权限。
        UPDATE system_role_menu page_grant SET deleted = 1, updater = 'migration:0179', update_time = CURRENT_TIMESTAMP
        WHERE page_grant.menu_id = page_id AND page_grant.deleted = 0 AND NOT EXISTS (
            SELECT 1 FROM system_role_menu query_grant WHERE query_grant.menu_id = ANY(active_query_ids)
                AND query_grant.deleted = 0 AND query_grant.role_id = page_grant.role_id
                AND query_grant.tenant_id = page_grant.tenant_id
        );
        INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
        SELECT role_high_watermark + row_number() OVER (ORDER BY pending.role_id, pending.tenant_id, pending.menu_id),
               pending.role_id, pending.menu_id, pending.tenant_id, 'migration:0179', 'migration:0179', 0
        FROM (
            -- 相同权限的现有查询来源只补页面和空权限祖先，保持全仓同权限授权的或关系。
            SELECT DISTINCT query_grant.role_id, query_grant.tenant_id, navigation.id AS menu_id
            FROM system_role_menu query_grant CROSS JOIN unnest(visited) navigation(id)
            WHERE query_grant.menu_id = ANY(active_query_ids) AND query_grant.deleted = 0
                AND NOT EXISTS (SELECT 1 FROM system_role_menu page_grant
                    WHERE page_grant.menu_id = navigation.id AND page_grant.deleted = 0
                        AND page_grant.role_id = query_grant.role_id AND page_grant.tenant_id = query_grant.tenant_id)
        ) pending;
        GET DIAGNOSTICS inserted = ROW_COUNT;
        inserted_total := inserted_total + inserted;
        role_high_watermark := role_high_watermark + inserted;

        -- 套餐按当前查询许可收敛；保留其余元素的类型和顺序，只清理本次明确退役的按钮引用。
        WITH allowances AS (
            SELECT package.id, package.menu_ids::jsonb AS original,
                EXISTS (SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value)
                        WHERE entry.value = ANY(active_query_ids::text[])) AS has_query
            FROM system_tenant_package package
        ), cleaned AS (
            SELECT allowance.id, allowance.has_query,
                (SELECT coalesce(jsonb_agg(entry.value ORDER BY entry.ordinality), '[]'::jsonb)
                 FROM jsonb_array_elements(allowance.original) WITH ORDINALITY entry(value, ordinality)
                 WHERE coalesce(NOT (entry.value #>> '{}' = ANY(retired_query_ids::text[])), true)
                    AND (entry.value #>> '{}' IS DISTINCT FROM page_id::text OR allowance.has_query)) AS entries
            FROM allowances allowance
        ), restored AS (
            SELECT cleaned.id, cleaned.entries || CASE WHEN cleaned.has_query THEN to_jsonb(ARRAY(
                SELECT navigation.id FROM unnest(visited) WITH ORDINALITY navigation(id, position)
                WHERE NOT EXISTS (SELECT 1 FROM jsonb_array_elements_text(cleaned.entries) entry(value)
                                  WHERE entry.value = navigation.id::text)
                ORDER BY navigation.position DESC
            )) ELSE '[]'::jsonb END AS entries FROM cleaned
        )
        UPDATE system_tenant_package package SET menu_ids = restored.entries::text,
            updater = 'migration:0179', update_time = CURRENT_TIMESTAMP
        FROM restored WHERE package.id = restored.id AND package.menu_ids::jsonb IS DISTINCT FROM restored.entries;

        UPDATE system_role_menu SET deleted = 1, updater = 'migration:0179', update_time = CURRENT_TIMESTAMP
        WHERE menu_id = ANY(retired_query_ids) AND deleted = 0;
        UPDATE system_menu SET deleted = 1, updater = 'migration:0179', update_time = CURRENT_TIMESTAMP
        WHERE id = ANY(retired_query_ids) AND deleted = 0;
        UPDATE system_menu SET permission = target.permission, updater = 'migration:0179', update_time = CURRENT_TIMESTAMP
        WHERE id = page_id AND permission = '';
    END LOOP;
    IF inserted_total > 0 AND to_regclass('system_role_menu_seq') IS NOT NULL THEN
        PERFORM setval('system_role_menu_seq', greatest((SELECT last_value FROM system_role_menu_seq), role_high_watermark));
    END IF;
END $restore_role_navigation$;
COMMIT;
