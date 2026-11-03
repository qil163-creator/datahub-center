-- Migration: 0194 删除命名词根、命名策略及命名验证功能。
-- Purpose: 删除词根库和命名策略表及序列，清理命名词根页面、全部页签权限、角色授权和租户套餐中的菜单引用。
-- Depends on: 0193_governance_remove-standard-compliance.sql 已执行，或已核验平台达到对应状态；系统菜单、角色菜单和租户套餐表已存在。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；菜单按功能标识匹配，表与序列使用 IF EXISTS，重复执行不修改其他功能和审计字段。
-- Rollback: 失败整笔回滚；提交后词根、命名策略版本及授权不可恢复，须从执行前备份恢复两张表、序列、菜单、角色授权和套餐，并恢复对应应用版本。
-- 不可逆操作：永久删除全部词根和命名策略，包括停用、历史版本及逻辑删除记录；执行前备份上述对象，部署已移除命名服务调用的应用版本。
-- 删除表获取排他锁；菜单授权清理期间阻止并发写入。锁等待超过十秒或语句执行超过两分钟则回滚。
-- 不使用级联删除；发现其他功能子菜单或数据库依赖时拒绝执行，避免连带删除。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

-- 按功能标识识别页面及按钮，不把可能被其他功能复用的历史菜单编号作为删除依据。
CREATE TEMPORARY TABLE governance_0194_retired_menus (id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO governance_0194_retired_menus (id)
SELECT menu.id FROM system_menu menu
WHERE permission LIKE 'governance:standard-word-root:%'
   OR permission LIKE 'governance:standard-naming-policy:%'
   OR trim(both '/' from coalesce(component, '')) = 'governance/standardwordroot'
   OR trim(both '/' from coalesce(component, '')) LIKE 'governance/standardwordroot/%'
   OR component_name = 'GovernanceStandardWordRoot'
   OR (type = 2 AND name = '命名词根'
       AND trim(both '/' from coalesce(path, '')) = 'standard-word-root');

-- 清理无权限码的专属按钮，包含停用和逻辑删除记录，保留其他功能子项的拒绝检查。
WITH RECURSIVE buttons (id) AS (
    SELECT id FROM governance_0194_retired_menus
    UNION
    SELECT child.id FROM system_menu child JOIN buttons parent ON child.parent_id = parent.id
    WHERE child.type = 3 AND coalesce(child.permission, '') = ''
)
INSERT INTO governance_0194_retired_menus (id)
SELECT id FROM buttons ON CONFLICT (id) DO NOTHING;

DO $menu_preflight$
BEGIN
    IF EXISTS (
        SELECT 1 FROM system_menu child JOIN governance_0194_retired_menus parent ON child.parent_id = parent.id
        WHERE NOT EXISTS (SELECT 1 FROM governance_0194_retired_menus retired WHERE retired.id = child.id)
    ) THEN
        RAISE EXCEPTION '0194 命名词根菜单下存在其他功能子项，禁止连带删除';
    END IF;
END $menu_preflight$;

-- 保留套餐中其他条目的顺序、重复项及数字或字符串类型；使用紧凑 JSON 避免超过字段长度。
UPDATE system_tenant_package package
SET menu_ids = (
        SELECT '[' || coalesce(string_agg(entry.value::text, ',' ORDER BY entry.ordinality), '') || ']'
        FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY entry(value, ordinality)
        WHERE NOT EXISTS (
            SELECT 1 FROM governance_0194_retired_menus retired WHERE retired.id::text = entry.value #>> '{}'
        )
    ),
    updater = 'migration:0194', update_time = CURRENT_TIMESTAMP
WHERE EXISTS (
    SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value)
    JOIN governance_0194_retired_menus retired ON retired.id::text = entry.value
);

DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM governance_0194_retired_menus);
DELETE FROM system_menu WHERE id IN (SELECT id FROM governance_0194_retired_menus);

-- 索引、约束随所属表删除；其他对象依赖会使整笔事务失败。
DROP TABLE IF EXISTS "datahub-center".dh_governance_standard_naming_policy;
DROP TABLE IF EXISTS "datahub-center".dh_governance_standard_word_root;
DROP SEQUENCE IF EXISTS "datahub-center".dh_governance_standard_naming_policy_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_governance_standard_word_root_seq;

COMMIT;
