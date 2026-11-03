-- Migration: 0168 清理旧管理端调用按钮并归并接口测试授权。
-- Purpose: 退役 API 管理下已删除实现的 6911 按钮，将其原有有效授权归并到 6304 接口测试入口，保留现行权限标识。
-- Depends on: 0167_dataforge_fill-plan-category.sql 已执行，或等价完整平台状态；6304 接口测试菜单及系统角色菜单、租户套餐表已存在。
-- Target: 达到 0167 的 PostgreSQL 平台库，schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；旧菜单缺失时不新增授权，目标关联按租户和角色去重，套餐仅替换旧编号，重放不扩大授权。
-- Rollback: 失败整笔回滚；提交后无法区分原授权来源，须从执行前备份恢复三张权限表及旧应用。保留原关联主键，不分配新编号或修改序列。
-- 风险：只删除旧按钮及其角色关联，不删除 API、应用授权、操作日志或业务表；事务获取三张权限表的短时写锁，等待超过十秒即失败。
-- 授权边界：逻辑删除的旧菜单、角色关联和套餐仅清残留，不恢复权限；停用但未删除的套餐保留原配置，其他角色或套餐不新增权限。
-- 执行前备份上述权限表并暂停菜单、角色及套餐维护；提交后精确清理 permission_menu_ids 与 menu_role_ids Redis 权限缓存并重新登录，仅重启服务不会清除 Redis 缓存。
-- 默认缓存键：permission_menu_ids:supply:data-api:invoke，以及 menu_role_ids:<租户>:6304、menu_role_ids:<租户>:6911；租户忽略上下文的无租户键也要清理，部署配置了 spring.cache.redis.key-prefix 时加实际前缀。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

DO $retire_legacy_invoke_menu$
DECLARE
    old_menu system_menu%ROWTYPE;
    old_menu_active boolean := false;
    package record;
    entries jsonb;
    next_entries text;
    first_old bigint;
    first_target bigint;
BEGIN
    -- 必须命中当前接口测试入口，禁止把旧调用权限迁移给其他页面或覆盖停用菜单。
    IF NOT EXISTS (
        SELECT 1 FROM system_menu WHERE id = 6304 AND deleted = 0 AND status = 0
        AND type = 2 AND parent_id = 5990 AND permission = 'supply:data-api:invoke'
        AND trim(both '/' from coalesce(component, '')) = 'supply/apitest/index'
    ) OR (SELECT count(*) FROM system_menu WHERE deleted = 0
        AND trim(both '/' from coalesce(component, '')) = 'supply/apitest/index') <> 1 THEN
        RAISE EXCEPTION '0168 需要唯一有效且绑定原调用权限的 6304 接口测试菜单';
    END IF;

    SELECT * INTO old_menu FROM system_menu WHERE id = 6911;
    IF FOUND THEN
        IF old_menu.type IS DISTINCT FROM 3 OR old_menu.parent_id IS DISTINCT FROM 6302
            OR old_menu.permission IS DISTINCT FROM 'supply:data-api:invoke'
            OR coalesce(old_menu.component, '') <> ''
            OR old_menu.deleted IS NULL OR old_menu.deleted NOT IN (0, 1)
            OR old_menu.status IS DISTINCT FROM 0 THEN
            RAISE EXCEPTION '0168 旧 6911 菜单已改为其他用途或停用，禁止自动清理';
        END IF;
        old_menu_active := old_menu.deleted = 0;
    END IF;
    IF EXISTS (SELECT 1 FROM system_menu WHERE parent_id = 6911) THEN
        RAISE EXCEPTION '0168 旧 6911 菜单仍有子项，禁止连带删除或留下孤立菜单';
    END IF;

    -- 只搬移有效旧菜单上的一条未删除关联；按租户和角色去重，保留原主键，已有目标授权时直接清旧关联。
    IF old_menu_active THEN
        UPDATE system_role_menu SET menu_id = 6304, updater = 'migration:0168', update_time = CURRENT_TIMESTAMP
        WHERE id IN (
            SELECT min(old_grant.id) FROM system_role_menu old_grant
            WHERE old_grant.menu_id = 6911 AND old_grant.deleted = 0
            AND NOT EXISTS (SELECT 1 FROM system_role_menu target_grant
                WHERE target_grant.role_id = old_grant.role_id AND target_grant.tenant_id = old_grant.tenant_id
                AND target_grant.menu_id = 6304 AND target_grant.deleted = 0)
            GROUP BY old_grant.role_id, old_grant.tenant_id
        );
    END IF;

    -- 套餐保留其余条目的顺序和类型；已删除套餐或旧菜单仅清残留，停用套餐保留原配置供日后启用。
    FOR package IN SELECT id, menu_ids, deleted FROM system_tenant_package LOOP
        entries := package.menu_ids::jsonb;
        IF jsonb_typeof(entries) IS DISTINCT FROM 'array' THEN
            RAISE EXCEPTION '0168 套餐 % 的菜单集合不是数组，禁止猜测授权', package.id;
        END IF;
        SELECT min(ordinal) FILTER (WHERE value #>> '{}' = '6911'),
               min(ordinal) FILTER (WHERE value #>> '{}' = '6304')
        INTO first_old, first_target
        FROM jsonb_array_elements(entries) WITH ORDINALITY entry(value, ordinal);
        IF first_old IS NULL THEN CONTINUE; END IF;

        -- 用紧凑 JSON 避免增加分隔空格而超过 menu_ids 的 4096 字符限制；目标编号已存在时只保留首项。
        SELECT '[' || coalesce(string_agg((CASE
            WHEN value #>> '{}' = '6911' THEN CASE WHEN jsonb_typeof(value) = 'string'
                THEN to_jsonb('6304'::text) ELSE to_jsonb(6304::bigint) END
            ELSE value END)::text, ',' ORDER BY ordinal), '') || ']'
        INTO next_entries
        FROM jsonb_array_elements(entries) WITH ORDINALITY entry(value, ordinal)
        WHERE (value #>> '{}' IS DISTINCT FROM '6911'
            AND (value #>> '{}' IS DISTINCT FROM '6304' OR ordinal = first_target))
        OR (value #>> '{}' = '6911' AND old_menu_active AND package.deleted = 0
            AND first_target IS NULL AND ordinal = first_old);
        UPDATE system_tenant_package SET menu_ids = next_entries,
            updater = 'migration:0168', update_time = CURRENT_TIMESTAMP WHERE id = package.id;
    END LOOP;

    DELETE FROM system_role_menu WHERE menu_id = 6911;
    DELETE FROM system_menu WHERE id = 6911;
END $retire_legacy_invoke_menu$;

COMMIT;
