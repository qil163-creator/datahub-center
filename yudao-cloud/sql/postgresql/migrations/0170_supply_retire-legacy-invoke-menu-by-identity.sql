-- Migration: 0170 按实际菜单身份修复旧调用按钮退役，保留被复用的菜单编号。
-- Purpose: 替代 0168 的固定编号假设；只归并确认属于旧调用按钮的授权，保留工单协调等复用 6911 的菜单及关联。
-- Depends on: 0167_dataforge_fill-plan-category.sql 已执行或等价完整平台状态；系统菜单、角色菜单、租户套餐表和 6304 接口测试入口已存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；支持 0168 失败、已执行以及 0169 前后的状态。新库使用全量基线。
-- Repeatable: 是；旧按钮缺失时完全不写权限数据；仅迁移有效旧授权，按租户和角色去重，重放不新增授权。
-- Rollback: 失败整笔回滚；提交后旧按钮及关联的删除不可逆，须从执行前备份恢复三张权限表；不分配主键或修改序列。
-- 恢复次序例外：0168 报错后先在原连接单独执行 ROLLBACK，再执行本脚本替代 0168，不重跑 0168；成功后满足 0169 的等价权限前置状态，可继续 0169。
-- 风险：事务短时锁定三张权限表；执行前备份并暂停菜单、角色和套餐维护。只清理旧按钮，不修改业务数据。
-- 授权边界：停用或逻辑删除的旧按钮、已删除角色关联和套餐仅清残留，不恢复权限；其他用途的 6911 及其授权保持不变。
-- 无对应菜单的孤立引用无法确认历史身份，本脚本保留其原状，不按历史编号猜测并删除。
-- 提交后若实际清理了旧按钮，应按输出的旧编号清理 permission_menu_ids:supply:data-api:invoke 和 menu_role_ids 对应的旧编号、6304 缓存并重新登录。
-- 缓存键须包含实际配置前缀以及脚手架租户维度，并覆盖租户忽略上下文使用的无租户键；仅重启服务不会清除 Redis 缓存。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

DO $retire_legacy_invoke_by_identity$
DECLARE
    old_menu system_menu%ROWTYPE;
    old_id bigint;
    old_menu_active boolean := false;
    package record;
    entries jsonb;
    next_entries text;
    first_old bigint;
    first_target bigint;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM system_menu WHERE id = 6304 AND deleted = 0 AND status = 0
        AND type = 2 AND parent_id = 5990 AND permission = 'supply:data-api:invoke'
        AND trim(both '/' from coalesce(component, '')) = 'supply/apitest/index'
    ) OR (SELECT count(*) FROM system_menu WHERE deleted = 0
        AND trim(both '/' from coalesce(component, '')) = 'supply/apitest/index') <> 1 THEN
        RAISE EXCEPTION '0170 需要唯一有效且绑定原调用权限的 6304 接口测试菜单';
    END IF;

    -- 先用业务身份查找，不把历史编号当作删除依据；同一父菜单下的重复权限必须人工核验。
    IF (SELECT count(*) FROM system_menu WHERE parent_id = 6302
        AND permission = 'supply:data-api:invoke') > 1 THEN
        RAISE EXCEPTION '0170 API 管理下存在多个旧调用权限，禁止猜测授权来源';
    END IF;
    SELECT * INTO old_menu FROM system_menu WHERE parent_id = 6302
        AND permission = 'supply:data-api:invoke';
    IF FOUND THEN
        IF old_menu.type IS DISTINCT FROM 3 OR coalesce(old_menu.component, '') <> ''
            OR old_menu.deleted IS NULL OR old_menu.deleted NOT IN (0, 1)
            OR old_menu.status IS NULL OR old_menu.status NOT IN (0, 1) THEN
            RAISE EXCEPTION '0170 旧调用菜单 % 的类型、组件或状态异常，禁止自动清理', old_menu.id;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 6302 AND type = 2
            AND parent_id = 5990 AND permission = 'supply:data-api:query'
            AND trim(both '/' from coalesce(component, '')) = 'supply/dataapi/index') THEN
            RAISE EXCEPTION '0170 无法确认 6302 为 API 管理父菜单，禁止自动清理';
        END IF;
        old_id := old_menu.id;
        old_menu_active := old_menu.deleted = 0 AND old_menu.status = 0;
    END IF;

    -- 仍绑定调用权限却不在旧按钮位置的 6911 属于歧义状态，不能当成其他用途静默跳过。
    IF EXISTS (SELECT 1 FROM system_menu WHERE id = 6911
        AND permission = 'supply:data-api:invoke' AND id IS DISTINCT FROM old_id) THEN
        RAISE EXCEPTION '0170 菜单 6911 仍绑定调用权限但身份不符，须核验其类型、父菜单和组件';
    END IF;
    IF old_id IS NULL THEN
        RAISE NOTICE '0170 未发现旧调用按钮；现有菜单、角色关联和套餐配置全部保留';
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM system_menu WHERE parent_id = old_id) THEN
        RAISE EXCEPTION '0170 旧调用菜单 % 仍有子项，禁止连带删除或留下孤立菜单', old_id;
    END IF;

    IF old_menu_active THEN
        UPDATE system_role_menu SET menu_id = 6304, updater = 'migration:0170', update_time = CURRENT_TIMESTAMP
        WHERE id IN (
            SELECT min(old_grant.id) FROM system_role_menu old_grant
            WHERE old_grant.menu_id = old_id AND old_grant.deleted = 0
            AND NOT EXISTS (SELECT 1 FROM system_role_menu target_grant
                WHERE target_grant.role_id = old_grant.role_id AND target_grant.tenant_id = old_grant.tenant_id
                AND target_grant.menu_id = 6304 AND target_grant.deleted = 0)
            GROUP BY old_grant.role_id, old_grant.tenant_id
        );
    END IF;

    -- 只替换已确认的旧编号，保留其他条目的顺序与数字/字符串类型；紧凑 JSON 避免超出字段长度。
    FOR package IN SELECT id, menu_ids, deleted FROM system_tenant_package LOOP
        entries := package.menu_ids::jsonb;
        IF jsonb_typeof(entries) IS DISTINCT FROM 'array' THEN
            RAISE EXCEPTION '0170 套餐 % 的菜单集合不是数组，禁止猜测授权', package.id;
        END IF;
        SELECT min(ordinal) FILTER (WHERE value #>> '{}' = old_id::text),
               min(ordinal) FILTER (WHERE value #>> '{}' = '6304')
        INTO first_old, first_target
        FROM jsonb_array_elements(entries) WITH ORDINALITY entry(value, ordinal);
        IF first_old IS NULL THEN CONTINUE; END IF;

        SELECT '[' || coalesce(string_agg((CASE
            WHEN value #>> '{}' = old_id::text THEN CASE WHEN jsonb_typeof(value) = 'string'
                THEN to_jsonb('6304'::text) ELSE to_jsonb(6304::bigint) END
            ELSE value END)::text, ',' ORDER BY ordinal), '') || ']'
        INTO next_entries
        FROM jsonb_array_elements(entries) WITH ORDINALITY entry(value, ordinal)
        WHERE (value #>> '{}' IS DISTINCT FROM old_id::text
            AND (value #>> '{}' IS DISTINCT FROM '6304' OR ordinal = first_target))
        OR (value #>> '{}' = old_id::text AND old_menu_active AND package.deleted = 0
            AND first_target IS NULL AND ordinal = first_old);
        UPDATE system_tenant_package SET menu_ids = next_entries,
            updater = 'migration:0170', update_time = CURRENT_TIMESTAMP WHERE id = package.id;
    END LOOP;

    DELETE FROM system_role_menu WHERE menu_id = old_id;
    DELETE FROM system_menu WHERE id = old_id;
    RAISE NOTICE '0170 已清理旧调用菜单 % 及其残留引用；有效授权归并到 6304，需清理对应权限缓存', old_id;
END $retire_legacy_invoke_by_identity$;

COMMIT;
