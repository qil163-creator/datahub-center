-- Migration: 0272 各业务模块概览页菜单
-- Purpose: 参照建模概览，为基础管理、数据研发、数据治理、数据资产、数据服务各新增一个“概览”页面菜单，
--          排在各模块首位；页面只读聚合各模块既有列表与统计接口，数据权限仍由这些接口各自校验，
--          因此菜单本身不设按钮权限码。
-- Depends on: 平台库已升级至 0271；已存在 /basic、/develop、/govern、/asset、/supply 五个顶级目录。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；按组件路径定位菜单、按角色与菜单判重授权，重复执行不会新增重复记录。
-- Rollback: 删除 component 为下列五个页面的菜单及其 system_role_menu 授权即可；不涉及业务数据：
--           dataforge/basic-home/index、dataforge/develop-home/index、governance/govern-home/index、
--           asset/asset-home/index、supply/supply-home/index。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

DO $menus$
DECLARE
    page record;
    module_id bigint;
    page_id bigint;
    conflict_id bigint;
    first_sort integer;
    high_id bigint;
    granted_ids bigint[] := ARRAY[]::bigint[];
BEGIN
    FOR page IN SELECT * FROM (VALUES
        ('basic',   '基础概览', 'dataforge/basic-home/index',   'BasicHome'),
        ('develop', '研发概览', 'dataforge/develop-home/index', 'DataforgeDevelopHome'),
        ('govern',  '治理概览', 'governance/govern-home/index', 'GovernanceHome'),
        ('asset',   '资产概览', 'asset/asset-home/index',       'AssetHome'),
        ('supply',  '服务概览', 'supply/supply-home/index',     'SupplyHome')
    ) pages(module_path, title, component, component_name) LOOP
        SELECT id INTO module_id FROM system_menu
        WHERE parent_id = 0 AND type = 1 AND deleted = 0
            AND trim(both '/' from path) = page.module_path
        ORDER BY id LIMIT 1;
        IF module_id IS NULL THEN
            RAISE EXCEPTION '缺少顶级目录 /%，禁止猜测概览页所属模块', page.module_path;
        END IF;

        SELECT id INTO page_id FROM system_menu
        WHERE deleted = 0 AND trim(both '/' from component) = page.component
        ORDER BY id LIMIT 1;

        -- 同一目录下已有其他页面占用 home 路径时停止，避免覆盖既有菜单
        SELECT id INTO conflict_id FROM system_menu
        WHERE parent_id = module_id AND deleted = 0
            AND trim(both '/' from path) = 'home'
            AND id IS DISTINCT FROM page_id
        ORDER BY id LIMIT 1;
        IF conflict_id IS NOT NULL THEN
            RAISE EXCEPTION '/%/home 已被菜单 % 占用，请人工确认后再执行', page.module_path, conflict_id;
        END IF;

        IF page_id IS NULL THEN
            -- 排在该模块现有子菜单之前
            SELECT LEAST(0, coalesce(min(sort), 1) - 1) INTO first_sort FROM system_menu
            WHERE parent_id = module_id AND deleted = 0;
            SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                (SELECT last_value FROM system_menu_id_seq)) + 1 INTO page_id FROM system_menu;
            INSERT INTO system_menu
                (id, name, permission, type, sort, parent_id, path, icon, component,
                 component_name, status, visible, keep_alive, always_show, creator, updater, deleted)
            VALUES (page_id, page.title, '', 2, first_sort, module_id, 'home',
                'lucide:layout-dashboard', page.component, page.component_name, 0, true, true, true,
                'migration:0272', 'migration:0272', 0);
        ELSE
            UPDATE system_menu SET parent_id = module_id, component_name = page.component_name,
                updater = 'migration:0272', update_time = CURRENT_TIMESTAMP
            WHERE id = page_id;
        END IF;
        granted_ids := array_append(granted_ids, page_id);
    END LOOP;

    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
        (SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
    PERFORM setval('system_menu_seq', high_id);
    PERFORM setval('system_menu_id_seq', high_id);

    -- 授权给超级管理员，以及已拥有对应顶级目录的启用角色；沿用角色自身的脚手架租户归属。
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq))
        INTO high_id FROM system_role_menu;
    INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id, creator, updater, deleted)
    SELECT high_id + row_number() OVER (ORDER BY grants.role_id, grants.tenant_id, grants.menu_id),
        grants.role_id, grants.menu_id, grants.tenant_id, 'migration:0272', 'migration:0272', 0
    FROM (
        SELECT DISTINCT role.id AS role_id, role.tenant_id, menu.id AS menu_id
        FROM system_role role
        JOIN system_menu menu ON menu.id = ANY(granted_ids) AND menu.deleted = 0
        WHERE role.status = 0 AND role.deleted = 0
            AND (role.code = 'super_admin' OR EXISTS (
                SELECT 1 FROM system_role_menu owned
                WHERE owned.role_id = role.id AND owned.tenant_id = role.tenant_id
                    AND owned.menu_id = menu.parent_id AND owned.deleted = 0))
    ) grants
    WHERE NOT EXISTS (SELECT 1 FROM system_role_menu old
        WHERE old.role_id = grants.role_id AND old.tenant_id = grants.tenant_id
            AND old.menu_id = grants.menu_id AND old.deleted = 0);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq))
        INTO high_id FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', high_id);
END $menus$;

COMMIT;
