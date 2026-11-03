-- Migration: 0254 数据开发离线任务与实时任务入口
-- Purpose: 在原数据开发目录下新增离线和实时任务页面，保留工作台及既有任务操作权限。
-- Depends on: 0253_dataforge_full-sync-menu.sql（编号链）；现有 /develop/dev 目录及 dataforge/dev/index 工作台。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线包含本迁移。
-- Repeatable: 是；按页面组件校验身份后补齐，已存在菜单和角色授权保持不变。
-- Rollback: 删除 migration:0254 创建的两个页面及其角色菜单关联；不删除工作台、任务或操作权限。
-- 仅将原工作台已有菜单可见范围延伸到同权限的新入口，不新增保存、运行或原生执行授权。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;

DO $menus$
DECLARE
    directory_id bigint;
    workbench_id bigint;
    page_id bigint;
    high_id bigint;
    matches integer;
    item record;
BEGIN
    SELECT count(*), min(d.id) INTO matches, directory_id
    FROM system_menu d JOIN system_menu root ON root.id = d.parent_id
    WHERE d.deleted = 0 AND d.type = 1 AND d.path = 'dev'
      AND root.deleted = 0 AND trim(both '/' from root.path) = 'develop';
    IF matches <> 1 THEN
        RAISE EXCEPTION '0254 数据开发目录缺失或重复';
    END IF;
    SELECT count(*), min(id) INTO matches, workbench_id
    FROM system_menu
    WHERE deleted = 0 AND type = 2 AND parent_id = directory_id
      AND trim(both '/' from coalesce(component, '')) = 'dataforge/dev/index'
      AND permission = 'dataforge:dev-task:query';
    IF matches <> 1 THEN
        RAISE EXCEPTION '0254 原数据开发工作台缺失或权限归属不一致';
    END IF;

    FOR item IN SELECT * FROM (VALUES
        ('offline', '离线任务', 'dataforge/dev/offline/index', 'DataforgeDevOffline', 'lucide:calendar-clock', 2),
        ('realtime', '实时任务', 'dataforge/dev/realtime/index', 'DataforgeDevRealtime', 'lucide:radio', 3)
    ) entries(path, label, component, component_name, icon, sort) LOOP
        SELECT count(*), min(id) INTO matches, page_id
        FROM system_menu WHERE deleted = 0 AND component = item.component;
        IF matches > 1 THEN
            RAISE EXCEPTION '0254 页面组件重复：%', item.component;
        END IF;
        IF matches = 0 THEN
            IF EXISTS (SELECT 1 FROM system_menu
                       WHERE deleted = 0 AND parent_id = directory_id AND path = item.path) THEN
                RAISE EXCEPTION '0254 菜单路径冲突：%', item.path;
            END IF;
            SELECT greatest(coalesce(max(id), 0),
                   (SELECT last_value FROM system_menu_seq),
                   (SELECT last_value FROM system_menu_id_seq)) + 1 INTO page_id FROM system_menu;
            INSERT INTO system_menu
                (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                 status, visible, keep_alive, always_show, creator, updater, deleted)
            VALUES
                (page_id, item.label, 'dataforge:dev-task:query', 2, item.sort, directory_id,
                 item.path, item.icon, item.component, item.component_name, 0, true, true, true,
                 'migration:0254', 'migration:0254', 0);
        ELSIF NOT EXISTS (SELECT 1 FROM system_menu
                          WHERE id = page_id AND parent_id = directory_id AND path = item.path
                            AND permission = 'dataforge:dev-task:query' AND type = 2) THEN
            RAISE EXCEPTION '0254 菜单身份冲突：%', item.component;
        END IF;

        SELECT greatest(coalesce(max(id), 0),
               (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
        INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id, creator, updater, deleted)
        SELECT high_id + row_number() OVER (ORDER BY source.role_id, source.tenant_id),
               source.role_id, page_id, source.tenant_id, 'migration:0254', 'migration:0254', 0
        FROM (SELECT DISTINCT role_id, tenant_id FROM system_role_menu
              WHERE menu_id = workbench_id AND deleted = 0) source
        WHERE NOT EXISTS (SELECT 1 FROM system_role_menu existing
                          WHERE existing.role_id = source.role_id AND existing.tenant_id = source.tenant_id
                            AND existing.menu_id = page_id AND existing.deleted = 0);
    END LOOP;

    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
           (SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
    PERFORM setval('system_menu_seq', high_id);
    PERFORM setval('system_menu_id_seq', high_id);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', high_id);
END $menus$;
COMMIT;
