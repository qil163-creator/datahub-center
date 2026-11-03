-- Migration: 0178 拆分公共目录导航与原查询授权。
-- Purpose: 数据集市和资产地图作为登录后公共导航；原查询授权保留为按钮，不派生任何维护、预览或交付权限。
-- Depends on: 0177_asset_retire-standalone-management-menu.sql 已执行；两页及其无权限祖先目录存在。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；仅在页面仍承载原查询权限时迁移授权及套餐，重放不恢复已撤销的按钮、角色授权或套餐能力。
-- Rollback: 单事务内失败回滚；提交后需按执行前权限表备份恢复页面权限、查询按钮和新增关联。序列前进不回退。
-- 应与支持公共导航配置及对象数据范围的新版本同步发布；不执行任何资产数据修改。
-- 执行前停止全部旧版 system 实例及权限写入，菜单编号分配也须停止；迁移提交前不得恢复写入。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

DO $catalog_navigation$
DECLARE
    target record;
    page_id bigint;
    query_id bigint;
    ancestor_id bigint;
    matches integer;
    visited bigint[];
    changed boolean := false;
    migrate_query boolean;
    inserted integer;
    inserted_total integer := 0;
    menu_high_watermark bigint;
BEGIN
    IF EXISTS (SELECT 1 FROM system_tenant_package WHERE jsonb_typeof(menu_ids::jsonb) IS DISTINCT FROM 'array') THEN
        RAISE EXCEPTION '0178 套餐菜单编号必须为 JSON 数组';
    END IF;
    -- 在停写前提下合并表与两套序列的高水位，避免复用已删除菜单的历史编号。
    SELECT coalesce(max(id), 0) INTO menu_high_watermark FROM system_menu;
    IF to_regclass('system_menu_seq') IS NOT NULL THEN
        SELECT greatest(menu_high_watermark, last_value) INTO menu_high_watermark FROM system_menu_seq;
    END IF;
    IF to_regclass('system_menu_id_seq') IS NOT NULL THEN
        SELECT greatest(menu_high_watermark, last_value) INTO menu_high_watermark FROM system_menu_id_seq;
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
            RAISE EXCEPTION '0178 目录页面身份或原查询权限不正确：%', target.component;
        END IF;
        SELECT permission = target.permission INTO migrate_query FROM system_menu WHERE id = page_id;
        SELECT parent_id INTO ancestor_id FROM system_menu WHERE id = page_id;
        visited := ARRAY[page_id];
        WHILE ancestor_id <> 0 LOOP
            IF ancestor_id = ANY(visited) OR NOT EXISTS (
                SELECT 1 FROM system_menu WHERE id = ancestor_id AND type = 1 AND deleted = 0 AND permission = ''
            ) THEN
                RAISE EXCEPTION '0178 公共目录祖先必须为无权限目录且不能成环：%', target.component;
            END IF;
            visited := array_append(visited, ancestor_id);
            SELECT parent_id INTO ancestor_id FROM system_menu WHERE id = ancestor_id;
        END LOOP;

        SELECT count(*), min(id) INTO matches, query_id FROM system_menu
        WHERE parent_id = page_id AND type = 3 AND permission = target.permission;
        IF matches > 1 THEN RAISE EXCEPTION '0178 查询权限按钮身份不唯一：%', target.permission; END IF;
        -- 页面已转为公共导航后，不因人工删除查询按钮而重新创建；软删除与停用状态也保持原样。
        IF matches = 0 AND NOT migrate_query THEN CONTINUE; END IF;
        IF matches = 0 THEN
            menu_high_watermark := menu_high_watermark + 1;
            query_id := menu_high_watermark;
            INSERT INTO system_menu(id, name, permission, type, sort, parent_id, status, visible, creator, updater)
            SELECT query_id, target.name, target.permission, 3, 0, page_id, status, false, 'migration:0178', 'migration:0178'
            FROM system_menu WHERE id = page_id;
            changed := true;
        END IF;

        IF migrate_query THEN
            -- 原页面权限作为一次性迁移标记；重放不能从公共导航授权恢复已撤销的查询授权。
            INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
            SELECT (SELECT coalesce(max(id), 0) FROM system_role_menu)
                       + row_number() OVER (ORDER BY pending.role_id, pending.tenant_id),
                   pending.role_id, query_id, pending.tenant_id, 'migration:0178', 'migration:0178', 0
            FROM (
                SELECT DISTINCT original.role_id, original.tenant_id FROM system_role_menu original
                WHERE original.menu_id = page_id AND original.deleted = 0
                    AND NOT EXISTS (SELECT 1 FROM system_role_menu current_grant
                        WHERE current_grant.role_id = original.role_id AND current_grant.tenant_id = original.tenant_id
                            AND current_grant.menu_id = query_id AND current_grant.deleted = 0)
            ) pending;
            GET DIAGNOSTICS inserted = ROW_COUNT;
            inserted_total := inserted_total + inserted;

            -- 查询能力从页面迁入按钮时同步套餐许可，保留已有元素及顺序，不扩大其他套餐。
            UPDATE system_tenant_package package
            SET menu_ids = (package.menu_ids::jsonb || to_jsonb(query_id))::text,
                updater = 'migration:0178', update_time = CURRENT_TIMESTAMP
            WHERE EXISTS (SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) item(value)
                          WHERE item.value = page_id::text)
                AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) item(value)
                                WHERE item.value = query_id::text);
        END IF;

        UPDATE system_menu SET permission = '', updater = 'migration:0178', update_time = CURRENT_TIMESTAMP
        WHERE id = page_id AND permission <> '';
    END LOOP;
    IF changed THEN
        -- 菜单同时存在 ORM 与数据库默认序列，只前进到当前最大编号，避免后续主键冲突。
        IF to_regclass('system_menu_seq') IS NOT NULL THEN
            PERFORM setval('system_menu_seq', greatest((SELECT last_value FROM system_menu_seq),
                (SELECT max(id) FROM system_menu)));
        END IF;
        IF to_regclass('system_menu_id_seq') IS NOT NULL THEN
            PERFORM setval('system_menu_id_seq', greatest((SELECT last_value FROM system_menu_id_seq),
                (SELECT max(id) FROM system_menu)));
        END IF;
    END IF;
    IF inserted_total > 0 AND to_regclass('system_role_menu_seq') IS NOT NULL THEN
        PERFORM setval('system_role_menu_seq', greatest((SELECT last_value FROM system_role_menu_seq),
            (SELECT coalesce(max(id), 1) FROM system_role_menu)));
    END IF;
END $catalog_navigation$;
COMMIT;
