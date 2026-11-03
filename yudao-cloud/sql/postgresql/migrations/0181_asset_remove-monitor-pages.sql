-- Migration: 0181 删除数据资产统计和资产使用情况页面。
-- Purpose: 清理两个退役看板、专属按钮及角色和套餐引用，移除清理后空置的数据资产监控目录。
-- Depends on: 0180_governance_remove-retired-workbenches.sql 已执行；平台三张菜单权限表已存在。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；按组件和专属按钮权限识别，不依赖固定编号，重放不改动其他授权或审计字段。
-- Rollback: 单事务失败完整回滚；提交后菜单和授权配置须从执行前的三张权限表备份恢复。
-- 执行前备份菜单、角色菜单和套餐表，暂停这些配置的维护，并与移除看板的新前后端同步发布。
-- 本迁移只删除菜单配置；资产、申请、交付、使用日志及领域审计数据全部保留。
-- 提交后按部署真实 Redis 前缀清理受影响的 permission_menu_ids、menu_role_ids 缓存，并重新登录刷新菜单。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

DO $retire_asset_monitor_pages$
DECLARE
    retired_ids bigint[];
    empty_directory_ids bigint[];
BEGIN
    IF EXISTS (SELECT 1 FROM system_tenant_package WHERE json_typeof(menu_ids::json) IS DISTINCT FROM 'array') THEN
        RAISE EXCEPTION '0181 套餐菜单编号必须为 JSON 数组';
    END IF;
    WITH RECURSIVE roots AS (
        SELECT id FROM system_menu WHERE trim(both '/' from coalesce(component, '')) IN (
            'asset/monitor/statistics/index', 'asset/monitor/usage/index')
        UNION
        SELECT id FROM system_menu WHERE type = 3 AND permission = 'asset:monitor:query'
    ), descendants AS (
        SELECT id FROM roots
        UNION
        SELECT child.id FROM system_menu child JOIN descendants parent ON child.parent_id = parent.id
    ) SELECT coalesce(array_agg(id), ARRAY[]::bigint[]) INTO retired_ids FROM descendants;

    -- 专用按钮随页面移除；出现其他页面、目录或异常类型时整笔中止，避免连带删除。
    IF EXISTS (SELECT 1 FROM system_menu WHERE id = ANY(retired_ids) AND NOT (
        type = 3 OR (type = 2 AND trim(both '/' from coalesce(component, '')) IN (
            'asset/monitor/statistics/index', 'asset/monitor/usage/index')))) THEN
        RAISE EXCEPTION '0181 退役看板下包含未知页面或目录，禁止连带删除';
    END IF;

    -- 仅清理资产根目录下无剩余子项的监控目录；保留仍承载其他菜单的目录及授权。
    SELECT coalesce(array_agg(directory.id), ARRAY[]::bigint[]) INTO empty_directory_ids
    FROM system_menu directory JOIN system_menu parent ON parent.id = directory.parent_id
    WHERE directory.type = 1 AND trim(both '/' from coalesce(directory.path, '')) = 'monitor'
        AND coalesce(directory.component, '') = '' AND coalesce(directory.permission, '') = ''
        AND parent.type = 1 AND parent.parent_id = 0
        AND trim(both '/' from coalesce(parent.path, '')) = 'asset'
        AND NOT EXISTS (SELECT 1 FROM system_menu child
                        WHERE child.parent_id = directory.id AND NOT (child.id = ANY(retired_ids)));
    retired_ids := retired_ids || empty_directory_ids;

    -- 使用 json 保留其他元素的原始表示；紧凑拼接不会因 JSONB 格式化扩大套餐字段长度。
    WITH cleaned AS (
        SELECT package.id,
            (SELECT '[' || coalesce(string_agg(entry.value::text, ',' ORDER BY entry.ordinality), '') || ']'
             FROM json_array_elements(package.menu_ids::json) WITH ORDINALITY entry(value, ordinality)
             WHERE NOT EXISTS (SELECT 1 FROM unnest(retired_ids) retired(id)
                               WHERE entry.value #>> '{}' = retired.id::text)) AS entries
        FROM system_tenant_package package
    )
    UPDATE system_tenant_package package SET menu_ids = cleaned.entries,
        updater = 'migration:0181', update_time = CURRENT_TIMESTAMP
    FROM cleaned WHERE package.id = cleaned.id AND package.menu_ids::jsonb IS DISTINCT FROM cleaned.entries::jsonb;
    DELETE FROM system_role_menu WHERE menu_id = ANY(retired_ids);
    DELETE FROM system_menu WHERE id = ANY(retired_ids);
END $retire_asset_monitor_pages$;
COMMIT;
