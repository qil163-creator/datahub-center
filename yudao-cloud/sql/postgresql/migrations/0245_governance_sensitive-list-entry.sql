-- Migration: 0245 为数据安全增加敏感清单入口。
-- Purpose: 复用资产敏感清单页面及权限，仅向已有该页面查询权限的角色和套餐增加入口。
-- Depends on: 0244_governance_access-audit-menu-icon.sql；0227_asset_sensitive-list.sql 已建立资产敏感清单菜单。
-- Target: 已有 PostgreSQL 数据库；新库使用同步后的 datahub-center.sql。
-- Repeatable: 是；仅首次创建入口时迁移对应授权，重放不会恢复后来撤销的授权。
-- Rollback: 按执行前备份恢复菜单、角色菜单授权和租户套餐；原资产入口及业务数据不受影响。
-- 事务边界：单事务；身份冲突或套餐菜单格式错误时回滚；执行后重新登录以刷新权限缓存。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

DO $migration$
DECLARE
    asset_page_id bigint;
    safety_id bigint;
    new_page_id bigint;
    high_id bigint;
    matches integer;
BEGIN
    SELECT count(*), min(id) INTO matches, asset_page_id FROM system_menu
    WHERE deleted = 0 AND type = 2 AND permission = 'asset:sensitive-list:query'
      AND trim(both '/' from coalesce(component, '')) = 'asset/sensitive/index';
    IF matches <> 1 THEN RAISE EXCEPTION '0245 未找到唯一的资产敏感清单菜单'; END IF;

    SELECT count(*), min(safety.id) INTO matches, safety_id FROM system_menu safety
    JOIN system_menu governance ON governance.id = safety.parent_id
    WHERE safety.deleted = 0 AND safety.type = 1 AND safety.path = 'safety'
      AND governance.deleted = 0 AND governance.type = 1 AND governance.parent_id = 0
      AND trim(both '/' from coalesce(governance.path, '')) = 'govern';
    IF matches <> 1 THEN RAISE EXCEPTION '0245 未找到唯一的数据安全菜单'; END IF;

    SELECT count(*), min(id) INTO matches, new_page_id FROM system_menu
    WHERE trim(both '/' from coalesce(component, '')) = 'governance/security/sensitive/index';
    IF matches > 1 OR EXISTS (SELECT 1 FROM system_menu WHERE id = new_page_id
       AND (deleted IS DISTINCT FROM 0 OR type IS DISTINCT FROM 2 OR parent_id IS DISTINCT FROM safety_id
            OR permission IS DISTINCT FROM '' OR path IS DISTINCT FROM 'sensitive-list'
            OR component_name IS DISTINCT FROM 'GovernanceSensitiveList'))
       OR EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0 AND parent_id = safety_id
                  AND path = 'sensitive-list' AND id IS DISTINCT FROM new_page_id)
       OR EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0
                  AND component_name = 'GovernanceSensitiveList' AND id IS DISTINCT FROM new_page_id)
    THEN RAISE EXCEPTION '0245 数据安全敏感清单菜单身份冲突'; END IF;

    IF matches = 0 THEN
        IF EXISTS (SELECT 1 FROM system_tenant_package WHERE deleted = 0
                   AND jsonb_typeof(menu_ids::jsonb) <> 'array') THEN
            RAISE EXCEPTION '0245 租户套餐菜单必须为数组';
        END IF;
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                        (SELECT last_value FROM system_menu_id_seq)) + 1 INTO new_page_id FROM system_menu;
        INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon,
                                 component, component_name, creator, updater)
        VALUES (new_page_id, '敏感清单', '', 2, 7, safety_id, 'sensitive-list', 'lucide:shield-check',
                'governance/security/sensitive/index', 'GovernanceSensitiveList', 'migration:0245', 'migration:0245');

        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id
        FROM system_role_menu;
        INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id, creator, updater, deleted)
        SELECT high_id + row_number() OVER (ORDER BY source.role_id, source.tenant_id),
               source.role_id, new_page_id, source.tenant_id, 'migration:0245', 'migration:0245', 0
        FROM (SELECT DISTINCT role_id, tenant_id FROM system_role_menu
              WHERE deleted = 0 AND menu_id = asset_page_id) source;

        UPDATE system_tenant_package package
        SET menu_ids = (package.menu_ids::jsonb || jsonb_build_array(new_page_id))::text,
            updater = 'migration:0245', update_time = CURRENT_TIMESTAMP
        WHERE package.deleted = 0 AND EXISTS (
            SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) item(value)
            WHERE item.value = asset_page_id::text);

        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                        (SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
        PERFORM setval('system_menu_seq', high_id, true);
        PERFORM setval('system_menu_id_seq', high_id, true);
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id
        FROM system_role_menu;
        PERFORM setval('system_role_menu_seq', high_id, true);
    END IF;
END $migration$;

COMMIT;
