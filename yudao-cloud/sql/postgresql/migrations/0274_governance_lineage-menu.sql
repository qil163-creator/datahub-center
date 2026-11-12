-- Migration: 0274 数据血缘菜单与查询权限
-- Purpose: 在“数据治理 > 元数据管理”目录下新增“数据血缘”页面（组件 governance/lineage/index）及按钮权限
--          governance:lineage:query（节点检索、表级/字段级血缘图、影响分析、上报来源列表共用），并授权给超级管理员。
--          0180 退役的旧血缘页面曾使用同一组件路径与权限前缀；本迁移按组件、路径和权限定位，遇到未知占用即中止。
-- Depends on: 0273_governance_lineage-store.sql；存在 /govern 顶级目录及其下 path=metadata 的“元数据管理”目录。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；页面按组件路径定位、按钮按权限去重、授权按角色与菜单判重，重复执行不新增记录。
-- Rollback: 删除组件为 governance/lineage/index 的页面、权限为 governance:lineage:query 的按钮及其 system_role_menu 授权；
--           不涉及业务数据。原始 SQL 不触发缓存失效，提交后需清理权限菜单相关 Redis 缓存或重启 system 服务。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE
    govern_id bigint;
    dir_id bigint;
    page_id bigint;
    button_id bigint;
    high_id bigint;
BEGIN
    SELECT id INTO govern_id FROM system_menu
    WHERE deleted = 0 AND parent_id = 0 AND type = 1 AND trim(both '/' from coalesce(path, '')) = 'govern'
    ORDER BY id LIMIT 1;
    IF govern_id IS NULL THEN RAISE EXCEPTION '0274 缺少数据治理顶级目录 /govern'; END IF;
    SELECT id INTO dir_id FROM system_menu
    WHERE deleted = 0 AND parent_id = govern_id AND type = 1 AND trim(both '/' from coalesce(path, '')) = 'metadata'
    ORDER BY id LIMIT 1;
    IF dir_id IS NULL THEN RAISE EXCEPTION '0274 缺少元数据管理目录 /govern/metadata'; END IF;

    SELECT id INTO page_id FROM system_menu
    WHERE deleted = 0 AND trim(both '/' from coalesce(component, '')) = 'governance/lineage/index'
    ORDER BY id LIMIT 1;
    IF page_id IS NULL THEN
        IF EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0 AND parent_id = dir_id
                                               AND trim(both '/' from coalesce(path, '')) = 'lineage') THEN
            RAISE EXCEPTION '0274 菜单路径冲突：/govern/metadata/lineage 已被其他菜单占用';
        END IF;
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                        (SELECT last_value FROM system_menu_id_seq)) + 1 INTO page_id FROM system_menu;
        INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                                status, visible, keep_alive, always_show, creator, updater, deleted)
        VALUES (page_id, '数据血缘', '', 2, 7, dir_id, 'lineage', 'carbon:flow-data', 'governance/lineage/index',
                'GovernanceLineage', 0, true, true, true, 'migration:0274', 'migration:0274', 0);
    ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = page_id AND parent_id = dir_id AND type = 2
                                                 AND trim(both '/' from coalesce(path, '')) = 'lineage') THEN
        RAISE EXCEPTION '0274 菜单组件身份冲突：governance/lineage/index 已挂在其他位置';
    END IF;

    SELECT id INTO button_id FROM system_menu
    WHERE deleted = 0 AND permission = 'governance:lineage:query'
    ORDER BY id LIMIT 1;
    IF button_id IS NULL THEN
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                        (SELECT last_value FROM system_menu_id_seq)) + 1 INTO button_id FROM system_menu;
        INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, status, visible,
                                keep_alive, always_show, creator, updater, deleted)
        VALUES (button_id, '血缘查询', 'governance:lineage:query', 3, 1, page_id, '', '#', 0, true, true, true,
                'migration:0274', 'migration:0274', 0);
    ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = button_id AND type = 3 AND parent_id = page_id) THEN
        RAISE EXCEPTION '0274 权限冲突：governance:lineage:query 已被其他菜单使用';
    END IF;

    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                    (SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
    PERFORM setval('system_menu_seq', high_id);
    PERFORM setval('system_menu_id_seq', high_id);

    -- 与 0270 一致，仅授权超级管理员；沿用角色自身的脚手架租户归属。
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
    SELECT high_id + row_number() OVER (ORDER BY r.id, r.tenant_id, m.id), r.id, m.id, r.tenant_id,
           'migration:0274', 'migration:0274', 0
      FROM system_role r JOIN system_menu m ON m.id IN (page_id, button_id) AND m.deleted = 0
     WHERE r.code = 'super_admin' AND r.deleted = 0
       AND NOT EXISTS (SELECT 1 FROM system_role_menu old WHERE old.role_id = r.id AND old.tenant_id = r.tenant_id
                                                              AND old.menu_id = m.id AND old.deleted = 0);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', high_id);
END $menus$;
COMMIT;
