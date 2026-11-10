-- Migration: 0271 数据填报类目管理菜单
-- Purpose: 在基础管理的类目管理下新增数据填报类目页面和独立操作权限，复用现有采集计划分类数据。
-- Depends on: 平台库已升级至 0270，已有 0167 采集计划分类表和 0216 基础管理类目菜单。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；按组件和权限定位菜单并检查角色授权，重复执行不会新增重复记录。
-- Rollback: 删除本迁移创建的菜单及对应角色授权；不影响采集计划分类数据。需保留用户手动配置的授权时先备份。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

DO $menus$
DECLARE
    basic_id bigint;
    category_id bigint;
    page_id bigint;
    action_id bigint;
    high_id bigint;
    granted_ids bigint[];
    action record;
BEGIN
    SELECT id INTO basic_id FROM system_menu
    WHERE parent_id = 0 AND type = 1 AND deleted = 0 AND trim(both '/' from path) = 'basic'
    ORDER BY id LIMIT 1;
    SELECT id INTO category_id FROM system_menu
    WHERE parent_id = basic_id AND type = 1 AND deleted = 0 AND trim(both '/' from path) = 'category'
    ORDER BY id LIMIT 1;
    IF category_id IS NULL THEN
        RAISE EXCEPTION '缺少基础管理类目菜单，请先执行 0216 及其前置迁移';
    END IF;

    SELECT id INTO page_id FROM system_menu
    WHERE trim(both '/' from component) = 'dataforge/fill/category/index' AND deleted = 0
    ORDER BY id LIMIT 1;
    IF page_id IS NULL THEN
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
            (SELECT last_value FROM system_menu_id_seq)) + 1 INTO page_id FROM system_menu;
        INSERT INTO system_menu
            (id, name, permission, type, sort, parent_id, path, icon, component,
             component_name, status, visible, keep_alive, always_show, creator, updater, deleted)
        VALUES (page_id, '数据填报类目', 'dataforge:fill-plan-category:query', 2, 30,
            category_id, 'fill-category', 'lucide:folder-tree', 'dataforge/fill/category/index',
            'DataforgeFillCategory', 0, true, true, true, 'migration:0271', 'migration:0271', 0);
    ELSE
        UPDATE system_menu SET parent_id = category_id,
            permission = 'dataforge:fill-plan-category:query', updater = 'migration:0271',
            update_time = CURRENT_TIMESTAMP WHERE id = page_id;
    END IF;
    granted_ids := ARRAY[basic_id, category_id, page_id];

    FOR action IN SELECT * FROM (VALUES
        ('create', '新增类目', 1), ('update', '修改类目', 2), ('delete', '删除类目', 3)
    ) permissions(code, label, sort) LOOP
        SELECT id INTO action_id FROM system_menu
        WHERE parent_id = page_id AND permission = 'dataforge:fill-plan-category:' || action.code
            AND deleted = 0 ORDER BY id LIMIT 1;
        IF action_id IS NULL THEN
            SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                (SELECT last_value FROM system_menu_id_seq)) + 1 INTO action_id FROM system_menu;
            INSERT INTO system_menu
                (id, name, permission, type, sort, parent_id, path, status, visible,
                 keep_alive, always_show, creator, updater, deleted)
            VALUES (action_id, action.label, 'dataforge:fill-plan-category:' || action.code,
                3, action.sort, page_id, '', 0, true, true, true,
                'migration:0271', 'migration:0271', 0);
        END IF;
        granted_ids := array_append(granted_ids, action_id);
    END LOOP;

    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
        (SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
    PERFORM setval('system_menu_seq', high_id);
    PERFORM setval('system_menu_id_seq', high_id);

    -- 沿用现有超级管理员及脚手架角色菜单的租户归属。
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq))
        INTO high_id FROM system_role_menu;
    INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id, creator, updater, deleted)
    SELECT high_id + row_number() OVER (ORDER BY role.id, role.tenant_id, menu.id),
        role.id, menu.id, role.tenant_id, 'migration:0271', 'migration:0271', 0
    FROM system_role role CROSS JOIN system_menu menu
    WHERE role.code = 'super_admin' AND role.status = 0 AND role.deleted = 0
        AND menu.id = ANY(granted_ids) AND menu.deleted = 0
        AND NOT EXISTS (SELECT 1 FROM system_role_menu old
            WHERE old.role_id = role.id AND old.tenant_id = role.tenant_id
                AND old.menu_id = menu.id AND old.deleted = 0);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq))
        INTO high_id FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', high_id);
END $menus$;

COMMIT;
